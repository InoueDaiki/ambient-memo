package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.NewMemo
import dev.ambientmemo.engine.domain.NewUtterance
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
    private var changes = 0
    private val repository = ObservableMemoRepository(InMemoryMemoRepository(utterances)) { changes++ }

    private suspend fun memo() = NewMemo(
        startedAtMillis = 1,
        endedAtMillis = 2,
        title = "題名",
        summary = "要約",
        modelVersion = "fake-1",
        createdAtMillis = 3,
        utteranceIds = listOf(utterances.insert(NewUtterance(1, 2, "本文", null, 2)).id),
    )

    @Test
    fun `記録したメモを知らせる`() = runTest {
        repository.recordMemo(memo())

        assertEquals(1, changes)
    }

    @Test
    fun `記録しなかったメモは知らせない`() = runTest {
        val rejected = memo()
        repository.recordMemo(rejected)
        changes = 0

        repository.recordMemo(rejected)

        assertEquals(0, changes)
    }
}
