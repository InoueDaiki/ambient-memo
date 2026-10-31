package dev.ambientmemo.engine.domain

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.abs

/**
 * 音声から発話を切り出し、文字起こしを保存する。
 *
 * 読み取り側は [run] の呼び出し元のコンテキストで動き、発話区間を [SegmentBacklog] に置くだけにする。
 * 文字起こしは [processingContext] で別に動かすので、遅れても読み取りは止まらない。
 *
 * 音声のバッファは次のどの経路でも解放する。
 * - 文字起こしが終わった発話: 成功、失敗、中断を問わず [process] の `use` で解放する
 * - 追いつかずに捨てた発話: [SegmentBacklog] が解放する
 * - 中断で残った発話と語頭のバッファ: [run] の `finally` で解放する
 */
@Suppress("LongParameterList") // 依存はポートだけなので、まとめずに引数で受ける
class CapturePipeline(
    private val audioSource: AudioSource,
    private val voiceActivityDetector: VoiceActivityDetector,
    private val transcriber: Transcriber,
    private val utterances: UtteranceRepository,
    private val captureGaps: CaptureGapRepository,
    private val clock: Clock,
    private val config: CaptureConfig = CaptureConfig(),
    private val processingContext: CoroutineContext = EmptyCoroutineContext,
    private val transcriptionFilter: TranscriptionFilter = TranscriptionFilter { _, _ -> true },
) {
    /**
     * 音声の終わりまで処理する。
     *
     * 音声が終わるか読み取りに失敗すると、溜まった発話を処理してから戻る。失敗の例外はその後に投げる。
     * キャンセルすると、未処理の音声を捨てて欠落を記録する。
     */
    suspend fun run() {
        val sampleRateHz = audioSource.sampleRateHz
        val segmenter = UtteranceSegmenter(config, sampleRateHz)
        val backlog = SegmentBacklog(millisToSamples(config.maxBacklogMillis, sampleRateHz).toLong())
        try {
            coroutineScope {
                val worker = launch(processingContext) { processBacklog(backlog) }
                val failure = capture(segmenter, backlog)
                backlog.close()
                worker.join()
                failure?.let { throw it }
            }
        } finally {
            withContext(NonCancellable) { abandon(segmenter, backlog) }
        }
    }

    /** 読み取りの失敗は例外を投げずに返す。溜まった発話の処理を先に終わらせるため。 */
    @Suppress("TooGenericExceptionCaught") // ポートの実装が何を投げるかは決めていない
    private suspend fun capture(segmenter: UtteranceSegmenter, backlog: SegmentBacklog): Exception? {
        var failure: Exception? = null
        try {
            voiceActivityDetector.reset()
            var expectedStartMillis: Long? = null
            audioSource.frames().collect { frame ->
                expectedStartMillis?.let { detectDiscontinuity(it, frame, segmenter, backlog) }
                val isSpeech = voiceActivityDetector.isSpeech(frame)
                segmenter.accept(frame, isSpeech).forEach(backlog::offer)
                expectedStartMillis = frame.startedAtMillis +
                    samplesToMillis(frame.samples.size, audioSource.sampleRateHz)
                yield()
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            failure = e
        }
        segmenter.flush()?.let(backlog::offer)
        return failure
    }

    private fun detectDiscontinuity(
        expectedStartMillis: Long,
        frame: AudioFrame,
        segmenter: UtteranceSegmenter,
        backlog: SegmentBacklog,
    ) {
        val jumpMillis = frame.startedAtMillis - expectedStartMillis
        if (abs(jumpMillis) <= config.discontinuityToleranceMillis) return

        // 途切れの前後を1つの発話につなげない
        segmenter.flush()?.let(backlog::offer)
        voiceActivityDetector.reset()
        if (jumpMillis > 0) {
            backlog.addGap(
                CaptureGap(expectedStartMillis, frame.startedAtMillis, CaptureGapReason.SOURCE_DISCONTINUITY),
            )
        }
    }

    private suspend fun processBacklog(backlog: SegmentBacklog) {
        while (backlog.awaitWork()) {
            backlog.drainGaps().forEach { captureGaps.insert(it) }
            backlog.poll()?.let { process(it, backlog) }
        }
    }

    /**
     * 1つの発話を処理する。失敗しても欠落を記録して次へ進む。音声を残さないので、やり直しはできない。
     *
     * 実装の内側の `withTimeout` などが投げた CancellationException で処理全体を止めないように、
     * キャンセルかどうかは例外の型ではなく、このコルーチンの状態で判断する。
     */
    @Suppress("TooGenericExceptionCaught") // ポートの実装が何を投げるかは決めていない
    private suspend fun process(segment: SpeechSegment, backlog: SegmentBacklog) = segment.use {
        try {
            val transcription = transcriber.transcribe(segment)
            val durationMillis = segment.endedAtMillis - segment.startedAtMillis
            if (transcription.text.isNotBlank() && transcriptionFilter.shouldSave(transcription.text, durationMillis)) {
                utterances.insert(segment.toUtterance(transcription))
            }
        } catch (e: Exception) {
            val isStopping = !currentCoroutineContext().isActive
            val reason = if (isStopping) CaptureGapReason.PIPELINE_STOPPED else CaptureGapReason.PROCESSING_FAILED
            backlog.addGap(CaptureGap(segment.startedAtMillis, segment.endedAtMillis, reason))
            if (isStopping) throw e
        }
    }

    private fun SpeechSegment.toUtterance(transcription: Transcription) = NewUtterance(
        startedAtMillis = startedAtMillis,
        endedAtMillis = endedAtMillis,
        text = transcription.text,
        confidence = transcription.confidence,
        createdAtMillis = clock.nowMillis(),
    )

    /** 例外やキャンセルで抜けたときに残った音声を消す。正常に終わったときは語頭のバッファだけを消す。 */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 元の例外を隠さないため
    private suspend fun abandon(segmenter: UtteranceSegmenter, backlog: SegmentBacklog) {
        val lost = segmenter.discard()
        backlog.abandonAll(CaptureGapReason.PIPELINE_STOPPED)
        if (lost != null) {
            backlog.addGap(CaptureGap(lost.startedAtMillis, lost.endedAtMillis, CaptureGapReason.PIPELINE_STOPPED))
        }
        try {
            backlog.drainGaps().forEach { captureGaps.insert(it) }
        } catch (e: Exception) {
            // ここに来るのは失敗かキャンセルの途中だけ。音声はもう消してあり、欠落の記録は諦める
        }
    }
}
