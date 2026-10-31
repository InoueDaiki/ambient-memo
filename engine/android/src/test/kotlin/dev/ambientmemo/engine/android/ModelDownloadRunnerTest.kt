@file:Suppress("FunctionNaming") // テスト名は日本語の文にする

package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.ModelDownloadException
import dev.ambientmemo.engine.domain.ModelDownloadFailure
import dev.ambientmemo.engine.domain.ModelDownloadPhase
import dev.ambientmemo.engine.domain.ModelDownloadProgress
import dev.ambientmemo.engine.domain.ModelDownloadState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

private const val TIMEOUT_MILLIS = 5_000L
private val PROGRESS = ModelDownloadProgress(ModelDownloadPhase.EXTRACTING, 1, 2)

class ModelDownloadRunnerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runs = AtomicInteger()

    private fun runner(download: suspend ((ModelDownloadProgress) -> Unit) -> Unit) =
        ModelDownloadRunner(scope = scope) { onProgress ->
            runs.incrementAndGet()
            download(onProgress)
        }

    private fun ModelDownloadRunner.begin() {
        assertTrue(markStarting())
        start()
    }

    private fun ModelDownloadRunner.awaitState(predicate: (ModelDownloadState) -> Boolean) = runBlocking {
        withTimeout(TIMEOUT_MILLIS) { state.first(predicate) }
    }

    @Test
    fun `作っただけでは取得を始めない`() {
        val runner = runner { }

        assertEquals(ModelDownloadState.Idle, runner.state.value)
        assertEquals(0, runs.get())
    }

    @Test
    fun `進み具合を公開し、終わったら止まった状態に戻す`() {
        val proceed = CompletableDeferred<Unit>()
        val runner = runner { onProgress ->
            onProgress(PROGRESS)
            proceed.await()
        }

        runner.begin()
        runner.awaitState { it == ModelDownloadState.Running(PROGRESS) }
        proceed.complete(Unit)

        runner.awaitState { it == ModelDownloadState.Idle }
    }

    @Test
    fun `失敗の理由を公開し、もう一度始められる`() {
        var fails = true
        val runner = runner {
            if (fails) throw ModelDownloadException(ModelDownloadFailure.INSUFFICIENT_STORAGE, shortageBytes = 7)
        }

        runner.begin()
        val failed = runner.awaitState { it is ModelDownloadState.Failed }
        assertEquals(ModelDownloadState.Failed(ModelDownloadFailure.INSUFFICIENT_STORAGE, 7), failed)

        fails = false
        runner.begin()
        runner.awaitState { it == ModelDownloadState.Idle }
    }

    @Test
    fun `想定外の例外でも取得中のままにしない`() {
        val runner = runner { error("想定外") }

        runner.begin()

        assertEquals(
            ModelDownloadState.Failed(ModelDownloadFailure.UNKNOWN),
            runner.awaitState { it is ModelDownloadState.Failed },
        )
    }

    @Test
    fun `動いている間は重ねて始めない`() {
        val started = CompletableDeferred<Unit>()
        val runner = runner {
            started.complete(Unit)
            awaitCancellation()
        }

        assertTrue(runner.markStarting())
        assertFalse(runner.markStarting())
        runner.start()
        runBlocking { withTimeout(TIMEOUT_MILLIS) { started.await() } }
        runner.start()

        assertFalse(runner.markStarting())
        assertEquals(1, runs.get())
        runner.cancel()
    }

    @Test
    fun `Service が動き出す前に止めたら取得しない`() {
        val runner = runner { }

        assertTrue(runner.markStarting())
        runner.cancel()
        runner.start()

        assertEquals(ModelDownloadState.Idle, runner.state.value)
        assertEquals(0, runs.get())
    }

    @Test
    fun `Service を開始できなければ失敗にする`() {
        val runner = runner { }

        assertTrue(runner.markStarting())
        runner.abandonStart(ModelDownloadFailure.UNKNOWN)

        assertEquals(ModelDownloadState.Failed(ModelDownloadFailure.UNKNOWN), runner.state.value)
        assertEquals(0, runs.get())
    }

    @Test
    fun `止めるとすぐ止まった状態になり、遅れて届く進み具合で戻らない`() {
        val cancelled = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val runner = runner { onProgress ->
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                cancelled.await()
                onProgress(PROGRESS)
                finished.complete(Unit)
            }
        }
        runner.begin()
        runBlocking { withTimeout(TIMEOUT_MILLIS) { started.await() } }

        runner.cancel()
        assertEquals(ModelDownloadState.Idle, runner.state.value)
        cancelled.complete(Unit)

        runBlocking { withTimeout(TIMEOUT_MILLIS) { finished.await() } }
        assertEquals(ModelDownloadState.Idle, runner.state.value)
    }

    @Test
    fun `止めた直後に始めても、前の取得が終わるまで次を動かさない`() {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val runner = runner {
            if (runs.get() > 1) return@runner
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                release.await()
            }
        }
        runner.begin()
        runBlocking { withTimeout(TIMEOUT_MILLIS) { started.await() } }

        runner.cancel()
        runner.begin()
        assertEquals(1, runs.get())

        release.complete(Unit)
        runner.awaitState { it == ModelDownloadState.Idle && runs.get() == 2 }
    }
}
