@file:Suppress("FunctionNaming") // テスト名は日本語の文にする

package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.AskFinished
import dev.ambientmemo.engine.domain.DiagnosticEvent
import dev.ambientmemo.engine.domain.DiagnosticsLog
import dev.ambientmemo.engine.domain.LlmLoaded
import dev.ambientmemo.engine.domain.LlmRequest
import dev.ambientmemo.engine.domain.MemoQuestionAnswerer
import dev.ambientmemo.engine.domain.NewMemo
import dev.ambientmemo.engine.domain.NewUtterance
import dev.ambientmemo.engine.domain.testing.FakeClock
import dev.ambientmemo.engine.domain.testing.InMemoryMemoRepository
import dev.ambientmemo.engine.domain.testing.InMemorySpeakerRepository
import dev.ambientmemo.engine.domain.testing.InMemoryUtteranceRepository
import dev.ambientmemo.engine.domain.testing.isSearchPlanRequest
import dev.ambientmemo.engine.llm.LocalLlm
import dev.ambientmemo.engine.llm.NativeLlm
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.TimeZone

class AskSessionTest {
    private class CountingNative(private val fails: Boolean = false) : NativeLlm {
        var closed = 0

        override fun generate(request: LlmRequest): String {
            if (fails) throw IOException("実行基盤の失敗")
            return if (request.isSearchPlanRequest) "語: 歯医者\n期間: なし" else "あさってです [1]"
        }

        override fun cancel() = Unit

        override fun close() {
            closed++
        }
    }

    private val utterances = InMemoryUtteranceRepository()
    private val memos = InMemoryMemoRepository(utterances)
    private val events = mutableListOf<DiagnosticEvent>()
    private var loads = 0

    private suspend fun recordMemo(): Long {
        val utterance = utterances.insert(NewUtterance(0, 1, "歯医者はあさって", null, 1))
        val memo = NewMemo(0, 1, "歯医者", "- 歯医者はあさって", "fake-1", 1, listOf(utterance.id))
        return checkNotNull(memos.recordMemo(memo)).id
    }

    private fun session(
        native: CountingNative,
        isModelReady: Boolean = true,
        load: () -> NativeLlm = {
            loads++
            native
        },
    ) = AskSession(
        isModelReady = { isModelReady },
        openLlm = { LocalLlm("stub-1", load) },
        answererFor = { llm ->
            MemoQuestionAnswerer(memos, InMemorySpeakerRepository(), llm, FakeClock(0), TimeZone.getTimeZone("UTC"))
        },
        diagnostics = DiagnosticsLog { events += it },
    )

    @Test
    fun `答えたらモデルを解放し、根拠と記録を残す`() = runTest {
        val id = recordMemo()
        val native = CountingNative()
        val steps = mutableListOf<AskStep>()

        val reply = session(native).run("歯医者はいつ?") { steps += it }

        assertEquals(AskEnding.ANSWERED, reply.ending)
        assertEquals("あさってです", reply.text)
        assertEquals(listOf(id), reply.sourceIds)
        assertEquals(listOf(AskStep.PREPARING, AskStep.SEARCHING, AskStep.ANSWERING), steps)
        assertEquals(1, native.closed)
        assertTrue(events.any { it is LlmLoaded && it.isLoaded })
        assertEquals(1, events.filterIsInstance<AskFinished>().single().sourceCount)
    }

    @Test
    fun `実行基盤が失敗しても解放し、失敗を返す`() = runTest {
        recordMemo()
        val native = CountingNative(fails = true)

        val reply = session(native).run("歯医者はいつ?") {}

        assertEquals(AskEnding.FAILED, reply.ending)
        assertEquals(1, native.closed)
        assertTrue(events.none { it is AskFinished })
    }

    @Test
    fun `読み込めなければ失敗を返す`() = runTest {
        val reply = session(CountingNative(), load = { throw IOException("読み込めない") }).run("歯医者はいつ?") {}

        assertEquals(AskEnding.FAILED, reply.ending)
        assertTrue(events.any { it is LlmLoaded && !it.isLoaded })
    }

    @Test
    fun `モデルがなければ読み込まず、準備も知らせない`() = runTest {
        val steps = mutableListOf<AskStep>()

        val reply = session(CountingNative(), isModelReady = false).run("歯医者はいつ?") { steps += it }

        assertEquals(AskEnding.MODEL_MISSING, reply.ending)
        assertEquals(0, loads)
        assertTrue(steps.isEmpty())
    }
}
