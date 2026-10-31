@file:Suppress("FunctionNaming") // テスト名は日本語の文にする

package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.AudioFrame
import dev.ambientmemo.engine.domain.AudioSource
import dev.ambientmemo.engine.domain.CaptureGapReason
import dev.ambientmemo.engine.domain.CapturePipeline
import dev.ambientmemo.engine.domain.testing.FakeClock
import dev.ambientmemo.engine.domain.testing.FakeTranscriber
import dev.ambientmemo.engine.domain.testing.InMemoryCaptureGapRepository
import dev.ambientmemo.engine.domain.testing.InMemoryUtteranceRepository
import dev.ambientmemo.engine.domain.testing.ScriptedVoiceActivityDetector
import dev.ambientmemo.engine.domain.testing.TEST_FRAME_SAMPLES
import dev.ambientmemo.engine.domain.testing.TEST_SAMPLE_RATE_HZ
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val TIMEOUT_MILLIS = 5_000L
private const val FRAME_MILLIS = 10L
private const val SPEECH_FRAMES = 50

/** [frameCount] 個のフレームを流した後、[finish] が終わるまで動き続けるマイクの代わり。 */
private class ScriptedMicrophone(
    private val frameCount: Int,
    private val finish: suspend () -> Unit = { awaitCancellation() },
) : AudioSource {
    override val sampleRateHz = TEST_SAMPLE_RATE_HZ

    override fun frames(): Flow<AudioFrame> = flow {
        repeat(frameCount) { emit(AudioFrame(it * FRAME_MILLIS, ShortArray(TEST_FRAME_SAMPLES))) }
        finish()
    }
}

class CaptureRunnerTest {
    private val utterances = InMemoryUtteranceRepository()
    private val gaps = InMemoryCaptureGapRepository()
    private val failures = mutableListOf<Exception>()

    private fun runner(source: () -> AudioSource) = CaptureRunner(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        onFailure = { synchronized(failures) { failures += it } },
        createPipeline = { audio ->
            CapturePipeline(
                audioSource = audio,
                voiceActivityDetector = ScriptedVoiceActivityDetector(listOf(0L..<10_000L)),
                transcriber = FakeTranscriber(),
                utterances = utterances,
                captureGaps = gaps,
                clock = FakeClock(),
            )
        },
        audioSource = source,
    )

    private suspend fun CaptureRunner.awaitState(predicate: (CaptureState) -> Boolean) =
        withTimeout(TIMEOUT_MILLIS) { state.first(predicate) }

    @Test
    fun `最初のフレームが届くと聞き取り中になる`() = runBlocking {
        val runner = runner { ScriptedMicrophone(frameCount = 1) }

        runner.start()

        runner.awaitState { it is CaptureState.Listening }
        runner.stop()
    }

    @Test
    fun `停止すると開いていた発話を欠落として記録して止まる`() = runBlocking {
        val inSpeech = CompletableDeferred<Unit>()
        val runner = runner {
            ScriptedMicrophone(SPEECH_FRAMES) {
                inSpeech.complete(Unit)
                awaitCancellation()
            }
        }
        runner.start()
        withTimeout(TIMEOUT_MILLIS) { inSpeech.await() }

        runner.stop()

        assertEquals(CaptureState.Stopped(interrupted = false), runner.state.value)
        val recorded = gaps.listOverlapping(0, TIMEOUT_MILLIS)
        assertEquals(listOf(CaptureGapReason.PIPELINE_STOPPED), recorded.map { it.reason })
        assertTrue(utterances.listRecent(1).isEmpty())
    }

    @Test
    fun `読み取りに失敗すると中断として止まる`() = runBlocking {
        val runner = runner {
            ScriptedMicrophone(frameCount = 1, finish = { throw AudioCaptureException("占有") })
        }

        runner.start()

        val stopped = runner.awaitState { it is CaptureState.Stopped }
        assertEquals(CaptureState.Stopped(interrupted = true), stopped)
        assertEquals(1, failures.size)
    }

    @Test
    fun `音声が頼まれずに終わったら中断として止まる`() = runBlocking {
        val runner = runner { ScriptedMicrophone(frameCount = 1, finish = {}) }

        runner.start()

        val stopped = runner.awaitState { it is CaptureState.Stopped }
        assertEquals(CaptureState.Stopped(interrupted = true), stopped)
    }

    @Test
    fun `マイクを開けないときは聞き取り中を通らずに止まる`() = runBlocking {
        val runner = CaptureRunner(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            onFailure = { failures += it },
            createPipeline = { error("呼ばれない") },
            audioSource = { throw AudioCaptureException("開けない") },
        )

        runner.start()

        assertEquals(CaptureState.Stopped(interrupted = true), runner.state.value)
        assertEquals(1, failures.size)
    }

    @Test
    fun `開始を頼んだ後に進めなければ止まった状態に戻る`() {
        val runner = runner { ScriptedMicrophone(frameCount = 1) }

        assertTrue(runner.markStarting())
        assertEquals(CaptureState.Starting, runner.state.value)
        assertFalse(runner.markStarting())

        runner.abandonStart()

        assertEquals(CaptureState.Stopped(interrupted = false), runner.state.value)
    }

    @Test
    fun `止めた後にもう一度開始できる`() = runBlocking {
        val runner = runner { ScriptedMicrophone(frameCount = 1) }
        runner.start()
        runner.awaitState { it is CaptureState.Listening }
        runner.stop()

        runner.start()

        runner.awaitState { it is CaptureState.Listening }
        runner.stop()
    }
}
