package dev.ambientmemo.engine.domain

import dev.ambientmemo.engine.domain.testing.FakeAudioSource
import dev.ambientmemo.engine.domain.testing.FakeClock
import dev.ambientmemo.engine.domain.testing.FakeSpeakerEmbedder
import dev.ambientmemo.engine.domain.testing.InMemorySpeakerRepository
import dev.ambientmemo.engine.domain.testing.ScriptedVoiceActivityDetector
import dev.ambientmemo.engine.domain.testing.SyntheticAudio
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.math.sqrt

class SelfEnrollmentTest {
    private val model = "fake-1"
    private val speakers = InMemorySpeakerRepository()
    private val captureConfig = CaptureConfig(
        maxUtteranceMillis = 1_000,
        minSilenceMillis = 100,
        preRollMillis = 0,
        minSpeechMillis = 0,
    )
    private val config = EnrollmentConfig(requiredSamples = 2, minSampleMillis = 400, maxListeningMillis = 5_000)

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

    private fun enrollment(
        audio: ShortArray,
        speech: List<LongRange>,
        embedder: SpeakerEmbedder,
        source: AudioSource = FakeAudioSource(audio),
    ) = SelfEnrollment(
        audioSource = source,
        voiceActivityDetector = ScriptedVoiceActivityDetector(speech),
        embedder = embedder,
        speakers = speakers,
        clock = FakeClock(9_000),
        captureConfig = captureConfig,
        config = config,
    )

    @Test
    fun `複数の発話の平均を自分として記録する`() = runTest {
        val (audio, speech) = bursts(2)
        val embedder = FakeSpeakerEmbedder(model) { index, _ ->
            if (index == 0) floatArrayOf(3f, 0f) else floatArrayOf(0f, 4f)
        }

        val outcome = enrollment(audio, speech, embedder).run()

        assertEquals(EnrollmentOutcome.ENROLLED, outcome)
        val stored = speakers.listEmbeddings(model, 10).single()
        val half = 1 / sqrt(2f)
        assertTrue(stored.isSelf)
        assertEquals(2, stored.sampleCount)
        assertArrayEquals(floatArrayOf(half, half), stored.vector, 0.001f)
    }

    @Test
    fun `発話が揃ったらそこで読み取りを止める`() = runTest {
        val (audio, speech) = bursts(10)
        val embedder = FakeSpeakerEmbedder(model)

        enrollment(audio, speech, embedder).run()

        assertEquals(2, embedder.received.size)
    }

    @Test
    fun `音声はすべて解放する`() = runTest {
        val (audio, speech) = bursts(2)
        val embedder = FakeSpeakerEmbedder(model)

        enrollment(audio, speech, embedder).run()

        assertTrue(embedder.received.all { it.isReleased })
    }

    @Test
    fun `発話が足りなければ何も記録しない`() = runTest {
        val (audio, speech) = bursts(1)

        val outcome = enrollment(audio, speech, FakeSpeakerEmbedder(model)).run()

        assertEquals(EnrollmentOutcome.NOT_ENOUGH_SPEECH, outcome)
        assertTrue(speakers.listSpeakers().isEmpty())
    }

    @Test
    fun `短い発話は登録に使わない`() = runTest {
        val audio = SyntheticAudio.tone(300) + SyntheticAudio.silence(200) + SyntheticAudio.tone(300) +
            SyntheticAudio.silence(200)
        val embedder = FakeSpeakerEmbedder(model)

        val outcome = enrollment(audio, listOf(0L..<300, 500L..<800), embedder).run()

        assertEquals(EnrollmentOutcome.NOT_ENOUGH_SPEECH, outcome)
        assertTrue(embedder.received.isEmpty())
    }

    @Test
    fun `特徴量を作れなかった発話は数えない`() = runTest {
        val (audio, speech) = bursts(3)
        val embedder = FakeSpeakerEmbedder(model) { index, _ ->
            when (index) {
                0 -> null
                1 -> floatArrayOf(0f, 0f)
                else -> floatArrayOf(1f, 0f)
            }
        }

        assertEquals(EnrollmentOutcome.NOT_ENOUGH_SPEECH, enrollment(audio, speech, embedder).run())
    }

    @Test
    fun `時間の上限で諦める`() = runTest {
        val silent = SyntheticAudio.silence(100)
        var frames = 0
        val endless = object : AudioSource {
            override val sampleRateHz = 16_000

            override fun frames(): Flow<AudioFrame> = flow {
                while (true) {
                    emit(AudioFrame(frames++ * 100L, silent))
                }
            }
        }

        val outcome = enrollment(ShortArray(0), emptyList(), FakeSpeakerEmbedder(model), endless).run()

        assertEquals(EnrollmentOutcome.NOT_ENOUGH_SPEECH, outcome)
        assertEquals(50, frames)
    }

    @Test
    fun `途中で切れた音声の最後の発話も使う`() = runTest {
        val audio = SyntheticAudio.tone(500) + SyntheticAudio.silence(200) + SyntheticAudio.tone(500)

        val outcome = enrollment(audio, listOf(0L..<500, 700L..<1_200), FakeSpeakerEmbedder(model)).run()

        assertEquals(EnrollmentOutcome.ENROLLED, outcome)
    }

    @Test
    fun `特徴量の失敗は例外にして、音声を解放し、何も記録しない`() {
        val (audio, speech) = bursts(2)
        val embedder = FakeSpeakerEmbedder(model) { _, _ -> throw IOException("失敗") }

        assertThrows(IOException::class.java) {
            runTest { enrollment(audio, speech, embedder).run() }
        }

        assertTrue(embedder.received.all { it.isReleased })
        runTest { assertTrue(speakers.listSpeakers().isEmpty()) }
    }

    @Test
    fun `キャンセルすると音声を解放し、前の登録を残す`() = runTest {
        val previous = SpeakerEmbedding(floatArrayOf(1f, 0f), model)
        speakers.saveSelfEmbedding(previous, 3, 0)
        val (audio, speech) = bursts(2)
        val started = CompletableDeferred<Unit>()
        val embedder = FakeSpeakerEmbedder(model) { _, _ ->
            started.complete(Unit)
            awaitCancellation()
        }

        val job = launch { enrollment(audio, speech, embedder).run() }
        started.await()
        job.cancelAndJoin()

        assertTrue(embedder.received.all { it.isReleased })
        assertArrayEquals(previous.vector, speakers.listEmbeddings(model, 10).single().vector)
    }
}
