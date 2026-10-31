@file:Suppress("FunctionNaming") // テスト名は日本語の文にする

package dev.ambientmemo.engine.llm

import dev.ambientmemo.engine.domain.Llm
import dev.ambientmemo.engine.domain.LlmInputTooLongException
import dev.ambientmemo.engine.domain.LlmRequest
import dev.ambientmemo.engine.domain.testing.LlmContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val TIMEOUT_MILLIS = 10_000L
private val REQUEST = LlmRequest("指示", "入力", maxOutputTokens = 16)

/** 実行基盤の代わり。[respond] が戻るまで generate を返さない。 */
private class StubNativeLlm(private val respond: (StubNativeLlm) -> String = { "はい" }) : NativeLlm {
    val closed = AtomicInteger()
    val cancelled = CountDownLatch(1)
    val started = CountDownLatch(1)

    override fun generate(request: LlmRequest): String {
        started.countDown()
        return respond(this)
    }

    override fun cancel() = cancelled.countDown()

    override fun close() {
        closed.incrementAndGet()
    }
}

class LocalLlmContractTest : LlmContract() {
    override fun llm(): Llm = LocalLlm("stub-1", { StubNativeLlm() }).apply { load() }
}

class LocalLlmTest {
    @Test
    fun `読み込む前に生成すると失敗する`() = runTest {
        val llm = LocalLlm("stub-1", { StubNativeLlm() })

        val failure = runCatching { llm.generate(REQUEST) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun `読み込みは1回だけで、解放は何度呼んでも1回だけ`() {
        val native = StubNativeLlm()
        val loads = AtomicInteger()
        val llm = LocalLlm("stub-1", {
            loads.incrementAndGet()
            native
        })

        llm.load()
        llm.load()
        llm.close()
        llm.close()

        assertEquals(1, loads.get())
        assertEquals(1, native.closed.get())
    }

    @Test
    fun `解放した後は生成できず、読み込み直せば使える`() = runTest {
        val llm = LocalLlm("stub-1", { StubNativeLlm() })
        llm.load()
        llm.close()

        assertTrue(runCatching { llm.generate(REQUEST) }.isFailure)

        llm.load()
        assertEquals("はい", llm.generate(REQUEST))
        llm.close()
    }

    @Test
    fun `入力が長すぎる例外はそのまま伝える`() = runTest {
        val native = StubNativeLlm { throw LlmInputTooLongException() }
        val llm = LocalLlm("stub-1", { native }).apply { load() }

        assertTrue(runCatching { llm.generate(REQUEST) }.exceptionOrNull() is LlmInputTooLongException)
    }

    @Test
    fun `キャンセルすると実行基盤に止めるよう頼み、止まるまで待つ`() = runBlocking {
        val mayFinish = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val native = StubNativeLlm {
            mayFinish.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            finished.countDown()
            ""
        }
        val llm = LocalLlm("stub-1", { native }).apply { load() }
        val running = async(Dispatchers.Default) { llm.generate(REQUEST) }
        assertTrue(native.started.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

        val canceller = async(Dispatchers.Default) { running.cancelAndJoin() }
        assertTrue(native.cancelled.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        assertFalse(canceller.isCompleted)

        mayFinish.countDown()
        withTimeout(TIMEOUT_MILLIS) { canceller.await() }
        assertEquals(0, finished.count)
    }

    @Test
    fun `動いている処理があれば、止まってから解放する`() = runBlocking {
        val native = StubNativeLlm {
            it.cancelled.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            check(it.closed.get() == 0) { "動いている間に解放しました" }
            ""
        }
        val llm = LocalLlm("stub-1", { native }).apply { load() }
        val running = async(Dispatchers.Default) { llm.generate(REQUEST) }
        assertTrue(native.started.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

        llm.close()

        assertEquals("", withTimeout(TIMEOUT_MILLIS) { running.await() })
        assertEquals(1, native.closed.get())
    }

    @Test
    fun `読み込みの失敗は呼び出し側へ伝える`() {
        val llm = LocalLlm("stub-1", { error("読めない") })

        assertThrows(IllegalStateException::class.java) { llm.load() }
    }
}
