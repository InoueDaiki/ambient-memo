package dev.ambientmemo.engine.domain

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile

/** 自分の声の登録の既定値。仮で、端末のマイクで見直す。 */
data class EnrollmentConfig(
    /** 平均する発話の数。 */
    val requiredSamples: Int = DEFAULT_REQUIRED_SAMPLES,
    /** これより短い区間は登録に使わない。 */
    val minSampleMillis: Long = DEFAULT_MIN_SAMPLE_MILLIS,
    /** これだけ聞いても集まらなければ諦める。 */
    val maxListeningMillis: Long = DEFAULT_MAX_LISTENING_MILLIS,
) {
    init {
        require(requiredSamples > 0) { "requiredSamples は正の値です" }
        require(minSampleMillis >= 0) { "minSampleMillis は 0 以上です" }
        require(maxListeningMillis > 0) { "maxListeningMillis は正の値です" }
    }

    private companion object {
        const val DEFAULT_REQUIRED_SAMPLES = 3

        // 実音声で 1.5 秒ほど。検証では 3 秒の EER が 0.40%、1.5 秒が 1.40%
        const val DEFAULT_MIN_SAMPLE_MILLIS = 3_000L
        const val DEFAULT_MAX_LISTENING_MILLIS = 45_000L
    }
}

enum class EnrollmentOutcome {
    /** 自分の特徴量を記録した。 */
    ENROLLED,

    /** 時間内に発話が足りなかった。何も記録していない。 */
    NOT_ENOUGH_SPEECH,
}

/**
 * 自分の声を聞き取り、複数の発話の特徴量の平均を自分として記録する。
 *
 * 音声は [UtteranceSegmenter] の作業用バッファと発話の [SpeechSegment] だけに持ち、特徴量を作ったら捨てる。
 * 例外やキャンセルでは何も記録しない。特徴量が揃うまで保存しないので、途中で止めても前の登録は残る。
 */
@Suppress("LongParameterList") // 依存はポートだけなので、まとめずに引数で受ける
class SelfEnrollment(
    private val audioSource: AudioSource,
    private val voiceActivityDetector: VoiceActivityDetector,
    private val embedder: SpeakerEmbedder,
    private val speakers: SpeakerRepository,
    private val clock: Clock,
    private val captureConfig: CaptureConfig = CaptureConfig(),
    private val config: EnrollmentConfig = EnrollmentConfig(),
) {
    suspend fun run(): EnrollmentOutcome {
        val sampleRateHz = audioSource.sampleRateHz
        val segmenter = UtteranceSegmenter(captureConfig, sampleRateHz)
        val maxSamples = config.maxListeningMillis * sampleRateHz / MILLIS_PER_SECOND
        val vectors = mutableListOf<FloatArray>()
        try {
            voiceActivityDetector.reset()
            var heardSamples = 0L
            audioSource.frames().takeWhile { frame ->
                val isSpeech = voiceActivityDetector.isSpeech(frame)
                embedAll(segmenter.accept(frame, isSpeech), vectors)
                heardSamples += frame.samples.size
                vectors.size < config.requiredSamples && heardSamples < maxSamples
            }.collect()
            if (vectors.size < config.requiredSamples) embedAll(listOfNotNull(segmenter.flush()), vectors)
        } finally {
            segmenter.discard()
        }
        return record(vectors)
    }

    /** 途中で失敗しても、残りの発話を含めてすべて解放する。 */
    private suspend fun embedAll(segments: List<SpeechSegment>, vectors: MutableList<FloatArray>) {
        try {
            segments.forEach { segment -> vectorOf(segment)?.let(vectors::add) }
        } finally {
            segments.forEach(SpeechSegment::close)
        }
    }

    private suspend fun vectorOf(segment: SpeechSegment): FloatArray? {
        val isLongEnough = segment.endedAtMillis - segment.startedAtMillis >= config.minSampleMillis
        return if (isLongEnough) embedder.embed(segment)?.let { normalizedOrNull(it.vector) } else null
    }

    private suspend fun record(vectors: List<FloatArray>): EnrollmentOutcome {
        val mean = meanOf(vectors).takeIf { vectors.size >= config.requiredSamples }
            ?: return EnrollmentOutcome.NOT_ENOUGH_SPEECH
        speakers.saveSelfEmbedding(SpeakerEmbedding(mean, embedder.modelVersion), vectors.size, clock.nowMillis())
        return EnrollmentOutcome.ENROLLED
    }
}
