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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

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
        utterances: UtteranceRepository = this.utterances,
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

    @Test
    fun `処理した発話ごとに結果を記録し、本文は渡さない`() = runTest {
        val (audio, speech) = bursts(4)
        val events = mutableListOf<DiagnosticEvent>()
        val transcriber = FakeTranscriber { index, _ ->
            check(index != 3) { "失敗" }
            Transcription(listOf("保存する", "雑音", " ")[index])
        }

        CapturePipeline(
            audioSource = FakeAudioSource(audio),
            voiceActivityDetector = ScriptedVoiceActivityDetector(speech),
            transcriber = transcriber,
            utterances = utterances,
            captureGaps = gaps,
            clock = clock,
            config = config,
            transcriptionFilter = { text, _ -> text != "雑音" },
            diagnostics = events::add,
        ).run()

        val segments = events.filterIsInstance<SegmentProcessed>()
        assertEquals(
            listOf(SegmentOutcome.SAVED, SegmentOutcome.FILTERED, SegmentOutcome.BLANK, SegmentOutcome.FAILED),
            segments.map { it.outcome },
        )
        assertEquals(listOf(4, 2, 1, 0), segments.map { it.textLength })
        assertEquals(listOf(1, 0, 0, 0), segments.map { it.turns })
    }

    @Test
    fun `保存しないと判定した発話は欠落にせず、バッファを解放する`() = runTest {
        val (audio, speech) = bursts(2)
        val transcriber = FakeTranscriber()

        CapturePipeline(
            audioSource = FakeAudioSource(audio),
            voiceActivityDetector = ScriptedVoiceActivityDetector(speech),
            transcriber = transcriber,
            utterances = utterances,
            captureGaps = gaps,
            clock = clock,
            config = config,
            transcriptionFilter = { text, _ -> text == "発話2" },
        ).run()

        assertEquals(listOf("発話2"), savedUtterances().map { it.text })
        assertTrue(savedGaps().isEmpty())
        assertTrue(transcriber.received.all { it.isReleased })
    }

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
    fun `話者を判定して発話と一緒に保存し、バッファを解放する`() = runTest {
        val (audio, speech) = bursts(1)
        val embedder = FakeSpeakerEmbedder()

        speakerPipeline(audio, speech, identifier(embedder)).run()

        val saved = savedUtterances().single()
        assertEquals(1L, saved.speakerId)
        assertEquals(1.0, saved.speakerConfidence)
        assertTrue(embedder.received.single().isReleased)
    }

    @Test
    fun `特徴量の失敗では文字起こしを失わず、話者を不明にして次へ進む`() = runTest {
        val (audio, speech) = bursts(2)
        val embedder = FakeSpeakerEmbedder { index, _ ->
            if (index == 0) throw IOException("失敗")
            floatArrayOf(1f, 0f, 0f)
        }

        speakerPipeline(audio, speech, identifier(embedder)).run()

        assertEquals(listOf(null, 1L), savedUtterances().map { it.speakerId })
        assertTrue(savedGaps().isEmpty())
        assertTrue(embedder.received.all { it.isReleased })
    }

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
    fun `保存しない発話の特徴量は作らない`() = runTest {
        val (audio, speech) = bursts(1)
        val embedder = FakeSpeakerEmbedder()

        CapturePipeline(
            audioSource = FakeAudioSource(audio),
            voiceActivityDetector = ScriptedVoiceActivityDetector(speech),
            transcriber = FakeTranscriber(),
            utterances = utterances,
            captureGaps = gaps,
            clock = clock,
            config = config,
            transcriptionFilter = { _, _ -> false },
            speakerIdentifier = identifier(embedder),
        ).run()

        assertTrue(embedder.received.isEmpty())
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
    fun `文字起こしには音声が渡る`() = runTest {
        var hadSound = false
        val transcriber = FakeTranscriber { _, segment ->
            hadSound = segment.samples.any { it != 0.toShort() }
            Transcription("本文", 0.5)
        }

        pipeline(FakeAudioSource(SyntheticAudio.tone(300)), listOf(0L..<300L), transcriber).run()

        assertTrue(hadSound)
        assertEquals(0.5, savedUtterances().single().confidence)
    }

    @Test
    fun `保存の時刻は時計から取る`() = runTest {
        pipeline(FakeAudioSource(SyntheticAudio.tone(300)), listOf(0L..<300L)).run()

        assertEquals(50_000, savedUtterances().single().createdAtMillis)
    }

    @Test
    fun `長い発話は分割して順番に保存する`() = runTest {
        pipeline(FakeAudioSource(SyntheticAudio.tone(2_500)), listOf(0L..<2_500L)).run()

        assertEquals(listOf(0L, 1_000L, 2_000L), savedUtterances().map { it.startedAtMillis })
    }

    @Test
    fun `音声の途中で終わっても開いている発話を保存する`() = runTest {
        pipeline(FakeAudioSource(SyntheticAudio.tone(300)), listOf(0L..<1_000L)).run()

        assertEquals(1, savedUtterances().size)
    }

    @Test
    fun `空の文字起こしは保存せず、欠落にもしない`() = runTest {
        val transcriber = FakeTranscriber { _, _ -> Transcription("  ") }

        pipeline(FakeAudioSource(SyntheticAudio.tone(300)), listOf(0L..<300L), transcriber).run()

        assertTrue(savedUtterances().isEmpty())
        assertTrue(savedGaps().isEmpty())
        assertTrue(transcriber.received.single().isReleased)
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
    fun `文字起こしに失敗しても欠落を記録して次の発話へ進み、バッファを解放する`() = runTest {
        val (audio, speech) = bursts(2)
        val transcriber = FakeTranscriber { index, _ ->
            if (index == 0) throw IOException("失敗")
            Transcription("次の発話")
        }

        pipeline(FakeAudioSource(audio), speech, transcriber).run()

        assertEquals(listOf("次の発話"), savedUtterances().map { it.text })
        assertEquals(listOf(CaptureGap(0, 500, CaptureGapReason.PROCESSING_FAILED)), savedGaps())
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
    fun `保存に失敗しても欠落を記録して続ける`() = runTest {
        val (audio, speech) = bursts(2)
        val failingOnce = object : UtteranceRepository by utterances {
            private var failed = false

            override suspend fun insertAll(utterances: List<NewUtterance>): List<Utterance> {
                if (!failed) {
                    failed = true
                    throw IOException("書き込めない")
                }
                return this@CapturePipelineTest.utterances.insertAll(utterances)
            }
        }
        val transcriber = FakeTranscriber()

        pipeline(FakeAudioSource(audio), speech, transcriber, utterances = failingOnce).run()

        assertEquals(1, savedUtterances().size)
        assertEquals(CaptureGapReason.PROCESSING_FAILED, savedGaps().single().reason)
        assertTrue(transcriber.received.all { it.isReleased })
    }

    @Test
    fun `欠落も保存できなければ例外で止まり、バッファは解放する`() {
        val (audio, speech) = bursts(3)
        val transcriber = FakeTranscriber { _, _ -> throw IOException("失敗") }
        val failingGaps = object : CaptureGapRepository by gaps {
            override suspend fun insert(gap: CaptureGap) = error("DB が壊れた")
        }

        val thrown = assertThrows(IllegalStateException::class.java) {
            runTest { pipeline(FakeAudioSource(audio), speech, transcriber, captureGaps = failingGaps).run() }
        }

        assertEquals("DB が壊れた", thrown.message)
        assertTrue(transcriber.received.all { it.isReleased })
    }

    @Test
    fun `読み取りに失敗すると溜まった発話を処理してから例外を投げる`() {
        val transcriber = FakeTranscriber()
        val failing = object : AudioSource {
            override val sampleRateHz = TEST_SAMPLE_RATE_HZ

            override fun frames(): Flow<AudioFrame> = flow {
                emitAll(FakeAudioSource(SyntheticAudio.tone(300)).frames())
                throw IOException("マイクが使えない")
            }
        }

        assertThrows(IOException::class.java) {
            runTest { pipeline(failing, listOf(0L..<1_000L), transcriber).run() }
        }

        assertEquals(1, transcriber.received.size)
        assertTrue(transcriber.received.single().isReleased)
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
    fun `フレームの時刻が飛ぶと発話を区切って欠落を記録する`() = runTest {
        val jumpPosition = 16 * 200
        val source = FakeAudioSource(SyntheticAudio.tone(400), frameStartMillis = { position, default ->
            if (position >= jumpPosition) default + 5_000 else default
        })
        val detector = ScriptedVoiceActivityDetector(listOf(0L..<10_000L))

        CapturePipeline(source, detector, FakeTranscriber(), utterances, gaps, clock, config).run()

        assertEquals(listOf(0L, 5_200L), savedUtterances().map { it.startedAtMillis })
        assertEquals(listOf(200L, 5_400L), savedUtterances().map { it.endedAtMillis })
        assertEquals(listOf(CaptureGap(200, 5_200, CaptureGapReason.SOURCE_DISCONTINUITY)), savedGaps())
        assertEquals(2, detector.resetCount)
    }

    @Test
    fun `時刻が戻ったときは発話を区切るが欠落にはしない`() = runTest {
        val jumpPosition = 16 * 200
        val source = FakeAudioSource(
            SyntheticAudio.tone(400),
            startMillis = 10_000,
            frameStartMillis = { position, default -> if (position >= jumpPosition) default - 5_000 else default },
        )

        pipeline(source, listOf(0L..<20_000L)).run()

        assertEquals(2, savedUtterances().size)
        assertTrue(savedGaps().isEmpty())
    }

    @Test
    fun `小さな時刻のずれでは発話を区切らない`() = runTest {
        val jumpPosition = 16 * 200
        val source = FakeAudioSource(SyntheticAudio.tone(400), frameStartMillis = { position, default ->
            if (position >= jumpPosition) default + 50 else default
        })

        pipeline(source, listOf(0L..<10_000L)).run()

        assertEquals(1, savedUtterances().size)
        assertTrue(savedGaps().isEmpty())
    }

    @Test
    fun `同じパイプラインをもう一度動かせる`() = runTest {
        val pipeline = pipeline(FakeAudioSource(SyntheticAudio.tone(300)), listOf(0L..<300L))

        pipeline.run()
        pipeline.run()

        assertEquals(2, savedUtterances().size)
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
