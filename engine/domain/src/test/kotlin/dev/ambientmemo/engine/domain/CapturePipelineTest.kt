package dev.ambientmemo.engine.domain

import dev.ambientmemo.engine.domain.testing.FakeAudioSource
import dev.ambientmemo.engine.domain.testing.FakeClock
import dev.ambientmemo.engine.domain.testing.FakeSpeakerEmbedder
import dev.ambientmemo.engine.domain.testing.FakeTranscriber
import dev.ambientmemo.engine.domain.testing.InMemoryCaptureGapRepository
import dev.ambientmemo.engine.domain.testing.InMemorySpeakerRepository
import dev.ambientmemo.engine.domain.testing.InMemoryUtteranceRepository
import dev.ambientmemo.engine.domain.testing.ScriptedVoiceActivityDetector
import dev.ambientmemo.engine.domain.testing.SyntheticAudio
import dev.ambientmemo.engine.domain.testing.TEST_SAMPLE_RATE_HZ
import dev.ambientmemo.engine.domain.testing.WavAudioSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * キャンセル、内側のタイムアウト、処理の遅れ、WAV ファイルの読み取りに依るテストだけを残す。
 * データで表せる流れは engine/spec/capture-pipeline.json と speaker-turns.json にある。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CapturePipelineTest {
    private val config = CaptureConfig(
        maxUtteranceMillis = 1_000,
        minSilenceMillis = 100,
        preRollMillis = 0,
        minSpeechMillis = 0,
        maxBacklogMillis = 2_000,
    )
    private val clock = FakeClock(50_000)
    private val utterances = InMemoryUtteranceRepository()
    private val gaps = InMemoryCaptureGapRepository()

    /** 500ms の発話と 200ms の無音を [count] 回くり返す。 */
    private fun bursts(count: Int): Pair<ShortArray, List<LongRange>> {
        var audio = ShortArray(0)
        val ranges = (0 until count).map { index ->
            audio += SyntheticAudio.tone(500) + SyntheticAudio.silence(200)
            val start = index * 700L
            start..<start + 500
        }
        return audio to ranges
    }

    private fun pipeline(
        source: AudioSource,
        speech: List<LongRange>,
        transcriber: Transcriber = FakeTranscriber(),
        captureGaps: CaptureGapRepository = gaps,
    ) = CapturePipeline(
        audioSource = source,
        voiceActivityDetector = ScriptedVoiceActivityDetector(speech),
        transcriber = transcriber,
        utterances = utterances,
        captureGaps = captureGaps,
        clock = clock,
        config = config,
    )

    private suspend fun savedUtterances() = utterances.listOverlapping(0, Long.MAX_VALUE)

    private suspend fun savedGaps() = gaps.listOverlapping(0, Long.MAX_VALUE)

    private suspend fun identifier(embedder: FakeSpeakerEmbedder): SpeakerIdentifier {
        val speakers = InMemorySpeakerRepository()
        speakers.saveSelfEmbedding(SpeakerEmbedding(floatArrayOf(1f, 0f, 0f), embedder.modelVersion), 3, 0)
        return SpeakerIdentifier(embedder, speakers, clock)
    }

    private fun speakerPipeline(
        audio: ShortArray,
        speech: List<LongRange>,
        identifier: SpeakerIdentifier,
    ) = CapturePipeline(
        audioSource = FakeAudioSource(audio),
        voiceActivityDetector = ScriptedVoiceActivityDetector(speech),
        transcriber = FakeTranscriber(),
        utterances = utterances,
        captureGaps = gaps,
        clock = clock,
        config = config,
        speakerIdentifier = identifier,
    )

    @Test
    fun `内側のタイムアウトで特徴量が止まっても、処理全体を止めない`() = runTest {
        val (audio, speech) = bursts(2)
        val embedder = FakeSpeakerEmbedder { index, _ ->
            if (index == 0) withTimeout(1) { awaitCancellation() }
            floatArrayOf(1f, 0f, 0f)
        }

        speakerPipeline(audio, speech, identifier(embedder)).run()

        assertEquals(2, savedUtterances().size)
    }

    @Test
    fun `特徴量の途中でキャンセルすると欠落を記録し、バッファを解放する`() = runTest {
        val (audio, speech) = bursts(1)
        val started = CompletableDeferred<Unit>()
        val embedder = FakeSpeakerEmbedder { _, _ ->
            started.complete(Unit)
            awaitCancellation()
        }
        val pipeline = speakerPipeline(audio + SyntheticAudio.silence(100), speech, identifier(embedder))

        val job = launch { pipeline.run() }
        started.await()
        job.cancelAndJoin()

        assertTrue(savedUtterances().isEmpty())
        assertEquals(listOf(CaptureGapReason.PIPELINE_STOPPED), savedGaps().map { it.reason })
        assertTrue(embedder.received.single().isReleased)
    }

    @Test
    fun `WAV の発話を検知して文字起こしを保存し、バッファを解放する`(@TempDir dir: Path) = runTest {
        val path = dir.resolve("speech.wav")
        val (audio, _) = bursts(2)
        Files.write(path, SyntheticAudio.wav(audio))
        val base = clock.nowMillis()
        val speech = listOf(base..<base + 500, base + 700..<base + 1_200)
        val transcriber = FakeTranscriber()

        pipeline(WavAudioSource(path, clock), speech, transcriber).run()

        val saved = savedUtterances()
        assertEquals(listOf("発話1", "発話2"), saved.map { it.text })
        assertEquals(listOf(base, base + 700), saved.map { it.startedAtMillis })
        assertEquals(listOf(base + 500, base + 1_200), saved.map { it.endedAtMillis })
        assertTrue(transcriber.received.all { it.isReleased })
        assertTrue(savedGaps().isEmpty())
    }

    @Test
    fun `追いつかないときは古い発話を捨てて欠落を記録し、処理を続ける`() = runTest {
        val (audio, speech) = bursts(8)
        val firstBlocked = CompletableDeferred<Unit>()
        val transcriber = FakeTranscriber { index, _ ->
            if (index == 0) firstBlocked.await()
            Transcription("発話${index + 1}")
        }

        val job = launch { pipeline(FakeAudioSource(audio), speech, transcriber).run() }
        runCurrent()
        firstBlocked.complete(Unit)
        job.join()

        // 1つ目は処理中で、上限の 2 秒に収まる後ろの4つが残る
        assertEquals(listOf(0L, 2_800L, 3_500L, 4_200L, 4_900L), savedUtterances().map { it.startedAtMillis })
        assertEquals(
            listOf(700L, 1_400L, 2_100L).map { CaptureGap(it, it + 500, CaptureGapReason.PROCESSING_BACKLOG) },
            savedGaps(),
        )
        assertTrue(transcriber.received.all { it.isReleased })
    }

    @Test
    fun `実装の内側のタイムアウトでは処理全体を止めない`() = runTest {
        val (audio, speech) = bursts(2)
        val transcriber = FakeTranscriber { index, _ ->
            if (index == 0) withTimeout(1) { awaitCancellation() }
            Transcription("次の発話")
        }

        pipeline(FakeAudioSource(audio), speech, transcriber).run()

        assertEquals(listOf("次の発話"), savedUtterances().map { it.text })
        assertEquals(CaptureGapReason.PROCESSING_FAILED, savedGaps().single().reason)
    }

    @Test
    fun `キャンセルすると処理中と待ちと開いている発話を捨てて、欠落を記録する`() = runTest {
        val (audio, speech) = bursts(3)
        val transcriber = FakeTranscriber { _, _ -> awaitCancellation() }
        val neverEnding = object : AudioSource {
            override val sampleRateHz = TEST_SAMPLE_RATE_HZ

            override fun frames(): Flow<AudioFrame> = flow {
                emitAll(FakeAudioSource(audio + SyntheticAudio.tone(100)).frames())
                awaitCancellation()
            }
        }
        val allSpeech = speech + listOf(2_100L..<2_200L)

        val job = launch { pipeline(neverEnding, allSpeech, transcriber).run() }
        runCurrent()
        job.cancelAndJoin()

        assertTrue(transcriber.received.single().isReleased)
        assertTrue(savedUtterances().isEmpty())
        assertEquals(
            setOf(CaptureGap(0, 500, CaptureGapReason.PIPELINE_STOPPED)) +
                CaptureGap(700, 1_200, CaptureGapReason.PIPELINE_STOPPED) +
                CaptureGap(1_400, 1_900, CaptureGapReason.PIPELINE_STOPPED) +
                CaptureGap(2_100, 2_200, CaptureGapReason.PIPELINE_STOPPED),
            savedGaps().toSet(),
        )
    }

    @Test
    fun `キャンセルの途中で欠落を保存できなくても終わる`() = runTest {
        val transcriber = FakeTranscriber { _, _ -> awaitCancellation() }
        val failingGaps = object : CaptureGapRepository by gaps {
            override suspend fun insert(gap: CaptureGap) = throw IOException("書き込めない")
        }
        val source = FakeAudioSource(SyntheticAudio.tone(300))

        val job = launch {
            pipeline(source, listOf(0L..<300L), transcriber, captureGaps = failingGaps).run()
        }
        runCurrent()
        job.cancelAndJoin()

        assertTrue(transcriber.received.single().isReleased)
    }

    @Test
    fun `処理中も読み取りは止まらない`() = runTest {
        val (audio, speech) = bursts(2)
        val transcriber = FakeTranscriber { _, _ ->
            delay(60_000)
            Transcription("本文")
        }

        pipeline(FakeAudioSource(audio), speech, transcriber).run()

        assertEquals(2, savedUtterances().size)
    }
}
