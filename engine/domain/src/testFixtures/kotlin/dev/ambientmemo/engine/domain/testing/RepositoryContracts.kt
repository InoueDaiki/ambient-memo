@file:Suppress("FunctionNaming", "MagicNumber") // テスト名は日本語の文にする

package dev.ambientmemo.engine.domain.testing

import dev.ambientmemo.engine.domain.CaptureGap
import dev.ambientmemo.engine.domain.CaptureGapReason
import dev.ambientmemo.engine.domain.CaptureGapRepository
import dev.ambientmemo.engine.domain.CalendarDate
import dev.ambientmemo.engine.domain.MemoRepository
import dev.ambientmemo.engine.domain.NewMemo
import dev.ambientmemo.engine.domain.NewTodo
import dev.ambientmemo.engine.domain.NewUtterance
import dev.ambientmemo.engine.domain.TodoExtraction
import dev.ambientmemo.engine.domain.TodoKind
import dev.ambientmemo.engine.domain.TodoStatus
import dev.ambientmemo.engine.domain.UtteranceRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [UtteranceRepository] の Fake と本物の両方に流す契約テスト。 */
abstract class UtteranceRepositoryContract {
    protected abstract fun repository(): UtteranceRepository

    private fun utterance(startedAtMillis: Long, endedAtMillis: Long, text: String = "本文") =
        NewUtterance(startedAtMillis, endedAtMillis, text, confidence = null, createdAtMillis = endedAtMillis)

    @Test
    fun `保存した項目をそのまま読み出せる`() = runTest {
        val repository = repository()
        val new = NewUtterance(1_000, 2_500, "今日は晴れ 🌤 \"引用\" 'です'", 0.75, 3_000)

        val saved = repository.insert(new)

        assertEquals(new.startedAtMillis, saved.startedAtMillis)
        assertEquals(new.endedAtMillis, saved.endedAtMillis)
        assertEquals(new.text, saved.text)
        assertEquals(new.confidence, saved.confidence)
        assertEquals(new.createdAtMillis, saved.createdAtMillis)
        assertEquals(listOf(saved), repository.listOverlapping(0, 10_000))
    }

    @Test
    fun `信頼度がなくても保存できる`() = runTest {
        val repository = repository()

        repository.insert(utterance(0, 100))

        assertEquals(null, repository.listOverlapping(0, 100).single().confidence)
    }

    @Test
    fun `保存のたびに別の id が付く`() = runTest {
        val repository = repository()

        val first = repository.insert(utterance(0, 100))
        val second = repository.insert(utterance(0, 100))

        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `開始時刻の順に返し、同時刻は保存した順に返す`() = runTest {
        val repository = repository()
        repository.insert(utterance(300, 400, "c"))
        repository.insert(utterance(100, 200, "a"))
        repository.insert(utterance(100, 150, "b"))

        val texts = repository.listOverlapping(0, 1_000).map { it.text }

        assertEquals(listOf("a", "b", "c"), texts)
    }

    @Test
    fun `範囲と少しでも重なる発話を返し、端が接するだけのものは返さない`() = runTest {
        val repository = repository()
        repository.insert(utterance(0, 100, "前に接する"))
        repository.insert(utterance(50, 101, "前から重なる"))
        repository.insert(utterance(120, 130, "内側"))
        repository.insert(utterance(199, 300, "後ろへ重なる"))
        repository.insert(utterance(200, 300, "後ろに接する"))
        repository.insert(utterance(0, 1_000, "範囲を含む"))

        val texts = repository.listOverlapping(100, 200).map { it.text }

        assertEquals(listOf("範囲を含む", "前から重なる", "内側", "後ろへ重なる"), texts)
    }

    @Test
    fun `該当がなければ空を返す`() = runTest {
        assertTrue(repository().listOverlapping(0, 1_000).isEmpty())
    }

    @Test
    fun `最近の発話を新しい順に上限まで返し、同時刻は後の保存を先にする`() = runTest {
        val repository = repository()
        repository.insert(utterance(100, 200, "古い"))
        repository.insert(utterance(300, 400, "新しい"))
        repository.insert(utterance(200, 300, "同時刻の先"))
        repository.insert(utterance(200, 300, "同時刻の後"))

        val texts = repository.listRecent(3).map { it.text }

        assertEquals(listOf("新しい", "同時刻の後", "同時刻の先"), texts)
    }

    @Test
    fun `最近の発話がなければ空を返す`() = runTest {
        assertTrue(repository().listRecent(10).isEmpty())
    }
}

/** [CaptureGapRepository] の Fake と本物の両方に流す契約テスト。 */
abstract class CaptureGapRepositoryContract {
    protected abstract fun repository(): CaptureGapRepository

    @Test
    fun `すべての理由を保存して読み出せる`() = runTest {
        val repository = repository()
        val gaps = CaptureGapReason.entries.mapIndexed { index, reason ->
            CaptureGap(index * 100L, index * 100L + 50, reason)
        }

        gaps.forEach { repository.insert(it) }

        assertEquals(gaps, repository.listOverlapping(0, 10_000))
    }

    @Test
    fun `開始時刻の順に返す`() = runTest {
        val repository = repository()
        val later = CaptureGap(300, 400, CaptureGapReason.PROCESSING_FAILED)
        val earlier = CaptureGap(100, 200, CaptureGapReason.PROCESSING_BACKLOG)
        repository.insert(later)
        repository.insert(earlier)

        assertEquals(listOf(earlier, later), repository.listOverlapping(0, 1_000))
    }

    @Test
    fun `範囲と少しでも重なる欠落を返し、端が接するだけのものは返さない`() = runTest {
        val repository = repository()
        val reason = CaptureGapReason.PROCESSING_BACKLOG
        repository.insert(CaptureGap(0, 100, reason))
        val overlapping = CaptureGap(99, 200, reason)
        repository.insert(overlapping)
        repository.insert(CaptureGap(200, 300, reason))

        assertEquals(listOf(overlapping), repository.listOverlapping(100, 200))
    }

    @Test
    fun `同じ内容の欠落を重ねて保存できる`() = runTest {
        val repository = repository()
        val gap = CaptureGap(0, 100, CaptureGapReason.PIPELINE_STOPPED)

        repository.insert(gap)
        repository.insert(gap)

        assertEquals(listOf(gap, gap), repository.listOverlapping(0, 100))
    }
}

/** [MemoRepository] の Fake と本物の両方に流す契約テスト。発話は同じ保存先の [UtteranceRepository] から入れる。 */
@Suppress("TooManyFunctions") // テストの数なので分けない
abstract class MemoRepositoryContract {
    protected class Stores(val utterances: UtteranceRepository, val memos: MemoRepository)

    protected abstract fun stores(): Stores

    private suspend fun Stores.utterance(startedAtMillis: Long) =
        utterances.insert(NewUtterance(startedAtMillis, startedAtMillis + 10, "本文", null, startedAtMillis + 10)).id

    private fun memo(vararg utteranceIds: Long, todos: List<NewTodo> = emptyList()) = NewMemo(
        startedAtMillis = 100,
        endedAtMillis = 200,
        title = "題名",
        summary = "- 要約",
        todoExtraction = TodoExtraction.COMPLETE,
        modelVersion = "fake-1",
        createdAtMillis = 1_000,
        utteranceIds = utteranceIds.toList(),
        todos = todos,
    )

    @Test
    fun `未処理の発話を開始時刻の順に返す`() = runTest {
        val stores = stores()
        val later = stores.utterance(200)
        val earlier = stores.utterance(100)

        assertEquals(listOf(earlier, later), stores.memos.listUnorganizedUtterances().map { it.id })
    }

    @Test
    fun `記録したメモと ToDo と根拠をそのまま読み出せる`() = runTest {
        val stores = stores()
        val first = stores.utterance(100)
        val second = stores.utterance(200)
        val todos = listOf(
            NewTodo("期限のある作業", TodoKind.REMINDER, CalendarDate(2026, 11, 5), TodoStatus.OPEN),
            NewTodo("期限のない作業", TodoKind.TODO, null, TodoStatus.CANDIDATE),
        )

        val saved = stores.memos.recordMemo(memo(second, first, todos = todos))!!

        assertEquals(listOf(saved), stores.memos.listMemos())
        assertEquals(listOf(first, second), stores.memos.listUtteranceIds(saved.id))
        val read = stores.memos.listTodos(saved.id)
        assertEquals(todos.map { it.task }, read.map { it.task })
        assertEquals(todos.map { it.kind }, read.map { it.kind })
        assertEquals(todos.map { it.dueDate }, read.map { it.dueDate })
        assertEquals(todos.map { it.status }, read.map { it.status })
        assertTrue(read.all { it.memoId == saved.id })
    }

    @Test
    fun `要約に失敗したメモも読み出せる`() = runTest {
        val stores = stores()
        val failed = memo(stores.utterance(100)).copy(
            title = null,
            summary = null,
            todoExtraction = TodoExtraction.INCOMPLETE,
        )

        stores.memos.recordMemo(failed)

        val read = stores.memos.listMemos().single()
        assertNull(read.title)
        assertNull(read.summary)
        assertEquals(TodoExtraction.INCOMPLETE, read.todoExtraction)
    }

    @Test
    fun `記録した発話は未処理でなくなる`() = runTest {
        val stores = stores()
        val recorded = stores.utterance(100)
        val rest = stores.utterance(200)

        stores.memos.recordMemo(memo(recorded))

        assertEquals(listOf(rest), stores.memos.listUnorganizedUtterances().map { it.id })
    }

    @Test
    fun `処理済みの発話を含むメモは何も書かない`() = runTest {
        val stores = stores()
        val recorded = stores.utterance(100)
        val rest = stores.utterance(200)
        stores.memos.recordMemo(memo(recorded))

        val result = stores.memos.recordMemo(memo(rest, recorded))

        assertNull(result)
        assertEquals(1, stores.memos.listMemos().size)
        assertEquals(listOf(rest), stores.memos.listUnorganizedUtterances().map { it.id })
    }

    @Test
    fun `存在しない発話を含むメモは何も書かない`() = runTest {
        val stores = stores()
        val utterance = stores.utterance(100)

        assertNull(stores.memos.recordMemo(memo(utterance, utterance + 1)))

        assertTrue(stores.memos.listMemos().isEmpty())
        assertEquals(listOf(utterance), stores.memos.listUnorganizedUtterances().map { it.id })
    }

    @Test
    fun `メモを作らずに処理済みにできて、繰り返しても変わらない`() = runTest {
        val stores = stores()
        val skipped = stores.utterance(100)
        val rest = stores.utterance(200)

        stores.memos.markOrganized(listOf(skipped), 1_000)
        stores.memos.markOrganized(listOf(skipped), 2_000)

        assertEquals(listOf(rest), stores.memos.listUnorganizedUtterances().map { it.id })
        assertTrue(stores.memos.listMemos().isEmpty())
    }

    @Test
    fun `メモを開始時刻の順に返す`() = runTest {
        val stores = stores()
        stores.memos.recordMemo(memo(stores.utterance(300)).copy(startedAtMillis = 300, endedAtMillis = 400))
        stores.memos.recordMemo(memo(stores.utterance(100)).copy(startedAtMillis = 100, endedAtMillis = 200))

        assertEquals(listOf(100L, 300L), stores.memos.listMemos().map { it.startedAtMillis })
    }

    @Test
    fun `他のメモの ToDo と根拠は混ざらない`() = runTest {
        val stores = stores()
        val first = stores.utterance(100)
        val second = stores.utterance(200)
        val todo = NewTodo("作業", TodoKind.TODO, null, TodoStatus.OPEN)
        val memoWithTodo = stores.memos.recordMemo(memo(first, todos = listOf(todo)))!!
        val other = stores.memos.recordMemo(memo(second))!!

        assertTrue(stores.memos.listTodos(other.id).isEmpty())
        assertEquals(listOf(second), stores.memos.listUtteranceIds(other.id))
        assertEquals(1, stores.memos.listTodos(memoWithTodo.id).size)
    }
}
