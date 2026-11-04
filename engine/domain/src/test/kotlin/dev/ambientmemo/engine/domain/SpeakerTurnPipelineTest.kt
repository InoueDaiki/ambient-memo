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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

/**
 * 区間の中の話者の判定のうち、キャンセルと、窓の配列が 0 で塗りつぶされることだけを残す。
 * 判定と行の分割は engine/spec/speaker-turns.json にある。
 */
class SpeakerTurnPipelineTest {
    private val clock = FakeClock(50_000)
    private val utterances = InMemoryUtteranceRepository()
    private val gaps = InMemoryCaptureGapRepository()
    private val speakers = InMemorySpeakerRepository()
    private val captureConfig = CaptureConfig(
        maxUtteranceMillis = 30_000,
        minSilenceMillis = 100,
        preRollMillis = 0,
        minSpeechMillis = 0,
    )

    // 窓の動きを見るテストなので、しきい値は既定値の調整に左右されない値に固定する
    private val windowConfig = SpeakerConfig(
        selfThreshold = 0.6f,
        selfExitThreshold = 0.55f,
        unknownFloor = 0.45f,
        provisionalThreshold = 0.6f,
        minNewSpeakerRunMillis = 1_000,
        maxProvisionalSpeakers = 64,
    )

    // 1 つの区間 + 区切りの無音で 6.8 秒。窓の発話の部分は 6 秒で、開始は 0, 750, ..., 4_500 の 7 つ
    private val windowCount = 7

    private fun self(similarity: Float) = floatArrayOf(similarity, sqrt(1 - similarity * similarity), 0f)

    private fun pipeline(embedder: FakeSpeakerEmbedder, transcriber: Transcriber) = CapturePipeline(
        audioSource = FakeAudioSource(SyntheticAudio.tone(6_800) + SyntheticAudio.silence(200)),
        voiceActivityDetector = ScriptedVoiceActivityDetector(listOf(0L..<6_800L)),
        transcriber = transcriber,
        utterances = utterances,
        captureGaps = gaps,
        clock = clock,
        config = captureConfig,
        speakerIdentifier = SpeakerIdentifier(embedder, speakers, clock, windowConfig),
    )

    @Test
    fun `窓の途中でキャンセルすると、窓と区間の音声を消して欠落を記録する`() = runTest {
        speakers.saveSelfEmbedding(SpeakerEmbedding(floatArrayOf(1f, 0f, 0f), "fake-1"), 3, 0)
        val started = CompletableDeferred<Unit>()
        val embedder = FakeSpeakerEmbedder("fake-1") { index, _ ->
            if (index == 2) {
                started.complete(Unit)
                awaitCancellation()
            }
            self(0.9f)
        }
        val transcriber = FakeTranscriber { _, _ -> Transcription("本文") }

        val job = launch { pipeline(embedder, transcriber).run() }
        started.await()
        job.cancelAndJoin()

        assertTrue(utterances.listOverlapping(0, Long.MAX_VALUE).isEmpty())
        assertEquals(
            listOf(CaptureGapReason.PIPELINE_STOPPED),
            gaps.listOverlapping(0, Long.MAX_VALUE).map { it.reason },
        )
        assertEquals(3, embedder.received.size)
        assertTrue(embedder.received.all { it.isReleased })
        assertTrue(transcriber.received.all { it.isReleased })
    }

    @Test
    fun `窓の音声は特徴量の後に 0 で塗りつぶされる`() = runTest {
        speakers.saveSelfEmbedding(SpeakerEmbedding(floatArrayOf(1f, 0f, 0f), "fake-1"), 3, 0)
        val copies = mutableListOf<ShortArray>()
        val embedder = FakeSpeakerEmbedder("fake-1") { _, window ->
            copies += window.samples
            assertNotNull(window.samples.firstOrNull { it != 0.toShort() })
            self(0.9f)
        }

        pipeline(embedder, FakeTranscriber { _, _ -> Transcription("本文") }).run()

        assertEquals(windowCount, copies.size)
        assertTrue(copies.all { samples -> samples.all { it == 0.toShort() } })
    }
}
