@file:Suppress("FunctionNaming") // テスト名は日本語の文にする

package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.EnrollmentOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EnrollmentRunnerTest {
    private suspend fun failureOf(runner: EnrollmentRunner): EnrollmentFailure? =
        runCatching { runner.run() }.exceptionOrNull().let { (it as? SelfEnrollmentException)?.failure }

    @Test
    fun `登録できれば失敗なしで終わる`() = runTest {
        val runner = EnrollmentRunner(this, { null }, { EnrollmentOutcome.ENROLLED })

        assertEquals(null, failureOf(runner))
        assertFalse(runner.isRunning)
    }

    @Test
    fun `声が足りなければ理由を返す`() = runTest {
        val runner = EnrollmentRunner(this, { null }, { EnrollmentOutcome.NOT_ENOUGH_SPEECH })

        assertEquals(EnrollmentFailure.NOT_ENOUGH_SPEECH, failureOf(runner))
    }

    @Test
    fun `前提を満たさなければ聞き取りを始めない`() = runTest {
        var started = false
        val runner = EnrollmentRunner(
            this,
            { EnrollmentFailure.LISTENING },
            {
                started = true
                EnrollmentOutcome.ENROLLED
            },
        )

        assertEquals(EnrollmentFailure.LISTENING, failureOf(runner))
        assertFalse(started)
        assertFalse(runner.isRunning)
    }

    @Test
    fun `マイクを開けなければ理由を返す`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runner = EnrollmentRunner(scope, { null }, { throw AudioCaptureException("失敗") })

        assertEquals(EnrollmentFailure.MICROPHONE_UNAVAILABLE, failureOf(runner))
    }

    @Test
    fun `止めると cancelled になり、もう一度動かせる`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val runner = EnrollmentRunner(scope, { null }, {
            if (++calls == 1) {
                started.complete(Unit)
                awaitCancellation()
            }
            EnrollmentOutcome.ENROLLED
        })

        val first = async { failureOf(runner) }
        started.await()
        assertTrue(runner.isRunning)
        runner.cancel()

        assertEquals(EnrollmentFailure.CANCELLED, first.await())
        assertEquals(null, failureOf(runner))
    }

    @Test
    fun `前提の確認の途中でも止められる`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var started = false
        val runner = EnrollmentRunner(scope, { awaitCancellation() }, {
            started = true
            EnrollmentOutcome.ENROLLED
        })

        val first = async { failureOf(runner) }
        runCurrent()
        runner.cancel()

        assertEquals(EnrollmentFailure.CANCELLED, first.await())
        assertFalse(started)
    }

    @Test
    fun `動いている間はもう1本を始めない`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runner = EnrollmentRunner(scope, { null }, { awaitCancellation() })

        val first = async { failureOf(runner) }
        runCurrent()

        assertEquals(EnrollmentFailure.LISTENING, failureOf(runner))
        runner.cancel()
        assertEquals(EnrollmentFailure.CANCELLED, first.await())
    }
}
