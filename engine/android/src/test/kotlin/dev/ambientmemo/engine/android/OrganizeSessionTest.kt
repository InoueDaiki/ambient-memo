@file:Suppress("FunctionNaming") // テスト名は日本語の文にする

package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.LlmRequest
import dev.ambientmemo.engine.domain.NewUtterance
import dev.ambientmemo.engine.domain.OrganizeJob
import dev.ambientmemo.engine.domain.testing.FakeClock
import dev.ambientmemo.engine.domain.testing.InMemoryMemoRepository
import dev.ambientmemo.engine.domain.testing.InMemorySpeakerRepository
import dev.ambientmemo.engine.domain.testing.InMemoryUtteranceRepository
import dev.ambientmemo.engine.llm.LocalLlm
import dev.ambientmemo.engine.llm.NativeLlm
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.TimeZone

private const val HOUR = 3_600_000L

class OrganizeSessionTest {
    private class CountingNative(private val fails: Boolean = false) : NativeLlm {
        var calls = 0
        var closed = 0

        override fun generate(request: LlmRequest): String {
            calls++
            if (fails) throw IOException("実行基盤の失敗")
            return "- 要約"
        }

        override fun cancel() = Unit

        override fun close() {
            closed++
        }
    }

    private val utterances = InMemoryUtteranceRepository()
    private val memos = InMemoryMemoRepository(utterances)
    private val speakers = InMemorySpeakerRepository()
    private var loads = 0

    private suspend fun say(startedAtMillis: Long) {
        utterances.insert(NewUtterance(startedAtMillis, startedAtMillis + 1, "発話", null, startedAtMillis + 1))
    }

    private fun session(
        native: CountingNative,
        isModelReady: Boolean = true,
        load: () -> NativeLlm = {
            loads++
            native
        },
    ) = OrganizeSession(
        isModelReady = { isModelReady },
        openLlm = { LocalLlm("stub-1", load) },
        jobFor = { llm -> OrganizeJob(memos, speakers, llm, FakeClock(10 * HOUR), TimeZone.getTimeZone("UTC")) },
    )

    @Test
    fun `整理が終わったらモデルを解放する`() = runTest {
        say(0)
        val native = CountingNative()

        val result = session(native).run { true }

        assertEquals(OrganizeRunResult.COMPLETED, result)
        assertEquals(1, memos.listMemos().size)
        assertEquals(1, native.closed)
    }

    @Test
    fun `失敗しても解放し、やり直す結果を返す`() = runTest {
        say(0)
        val native = CountingNative(fails = true)

        val result = session(native).run { true }

        assertEquals(OrganizeRunResult.FAILED, result)
        assertEquals(1, native.closed)
    }

    @Test
    fun `モデルがなければ読み込まずに終わる`() = runTest {
        val result = session(CountingNative(), isModelReady = false).run { true }

        assertEquals(OrganizeRunResult.MODEL_MISSING, result)
        assertEquals(0, loads)
    }

    @Test
    fun `始める時点で止めるなら読み込まない`() = runTest {
        val result = session(CountingNative()).run { false }

        assertEquals(OrganizeRunResult.STOPPED, result)
        assertEquals(0, loads)
    }

    @Test
    fun `読み込めなければ失敗を返し、聞き取りへ例外を持ち込まない`() = runTest {
        val session = session(CountingNative(), load = { throw IOException("メモリ不足") })

        assertEquals(OrganizeRunResult.FAILED, session.run { true })
    }

    @Test
    fun `充電が切れたらまとまりの区切りで止め、次の実行が続きから処理する`() = runTest {
        say(0)
        say(2 * HOUR)
        val native = CountingNative()
        var checks = 0

        val stopped = session(native).run { ++checks < 3 } // 読み込み前、1 つ目の前、2 つ目の前

        assertEquals(OrganizeRunResult.STOPPED, stopped)
        assertEquals(1, memos.listMemos().size)
        assertEquals(1, native.closed)

        session(native).run { true }

        assertEquals(2, memos.listMemos().size)
        assertTrue(memos.listUnorganizedUtterances().isEmpty())
    }
}
