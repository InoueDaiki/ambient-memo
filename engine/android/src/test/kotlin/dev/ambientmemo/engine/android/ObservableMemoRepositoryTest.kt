package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.NewMemo
import dev.ambientmemo.engine.domain.NewTodo
import dev.ambientmemo.engine.domain.NewUtterance
import dev.ambientmemo.engine.domain.TodoExtraction
import dev.ambientmemo.engine.domain.TodoKind
import dev.ambientmemo.engine.domain.TodoStatus
import dev.ambientmemo.engine.domain.testing.InMemoryMemoRepository
import dev.ambientmemo.engine.domain.testing.InMemoryUtteranceRepository
import dev.ambientmemo.engine.domain.testing.MemoRepositoryContract
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ObservableMemoRepositoryContractTest : MemoRepositoryContract() {
    override fun stores(): Stores {
        val utterances = InMemoryUtteranceRepository()
        return Stores(utterances, ObservableMemoRepository(InMemoryMemoRepository(utterances)) {})
    }
}

class ObservableMemoRepositoryTest {
    private val utterances = InMemoryUtteranceRepository()
    private val events = mutableListOf<MemoChange>()
    private val repository = ObservableMemoRepository(InMemoryMemoRepository(utterances)) { events += it }

    private suspend fun memo(todos: List<NewTodo>) = NewMemo(
        startedAtMillis = 1,
        endedAtMillis = 2,
        title = "題名",
        summary = "要約",
        todoExtraction = TodoExtraction.COMPLETE,
        modelVersion = "fake-1",
        createdAtMillis = 3,
        utteranceIds = listOf(utterances.insert(NewUtterance(1, 2, "本文", null, 2)).id),
        todos = todos,
    )

    @Test
    fun `ToDo のないメモはメモの変更だけを知らせる`() = runTest {
        repository.recordMemo(memo(emptyList()))

        assertEquals(listOf(MemoChange.MEMOS), events)
    }

    @Test
    fun `ToDo のあるメモは両方を知らせる`() = runTest {
        repository.recordMemo(memo(listOf(NewTodo("作業", TodoKind.TODO, null, TodoStatus.OPEN))))

        assertEquals(listOf(MemoChange.MEMOS, MemoChange.TODOS), events)
    }

    @Test
    fun `記録しなかったメモと、変わらなかった完了は知らせない`() = runTest {
        val rejected = memo(emptyList())
        repository.recordMemo(rejected)
        events.clear()

        repository.recordMemo(rejected)
        repository.completeTodo(1, 10)

        assertEquals(emptyList<MemoChange>(), events)
    }
}
