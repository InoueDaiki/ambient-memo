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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** キャンセルはコルーチンに依るので、ここに残す。登録の規則は engine/spec/self-enrollment.json にある。 */
class SelfEnrollmentTest {
    private val model = "fake-1"
    private val speakers = InMemorySpeakerRepository()

    @Test
    fun `キャンセルすると音声を解放し、前の登録を残す`() = runTest {
        val previous = SpeakerEmbedding(floatArrayOf(1f, 0f), model)
        speakers.saveSelfEmbedding(previous, 3, 0)
        val audio = (0 until 2).fold(ShortArray(0)) { all, _ ->
            all + SyntheticAudio.tone(500) + SyntheticAudio.silence(200)
        }
        val speech = listOf(0L..<500L, 700L..<1_200L)
        val started = CompletableDeferred<Unit>()
        val embedder = FakeSpeakerEmbedder(model) { _, _ ->
            started.complete(Unit)
            awaitCancellation()
        }
        val enrollment = SelfEnrollment(
            audioSource = FakeAudioSource(audio),
            voiceActivityDetector = ScriptedVoiceActivityDetector(speech),
            embedder = embedder,
            speakers = speakers,
            clock = FakeClock(9_000),
            captureConfig = CaptureConfig(
                maxUtteranceMillis = 1_000,
                minSilenceMillis = 100,
                preRollMillis = 0,
                minSpeechMillis = 0,
            ),
            config = EnrollmentConfig(requiredSamples = 2, minSampleMillis = 400, maxListeningMillis = 5_000),
        )

        val job = launch { enrollment.run() }
        started.await()
        job.cancelAndJoin()

        assertTrue(embedder.received.all { it.isReleased })
        assertArrayEquals(previous.vector, speakers.listEmbeddings(model, 10).single().vector)
    }
}
