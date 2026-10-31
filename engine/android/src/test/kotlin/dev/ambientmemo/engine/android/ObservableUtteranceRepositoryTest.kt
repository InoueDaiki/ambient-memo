@file:Suppress("FunctionNaming") // テスト名は日本語の文にする

package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.NewUtterance
import dev.ambientmemo.engine.domain.UtteranceRepository
import dev.ambientmemo.engine.domain.testing.InMemoryUtteranceRepository
import dev.ambientmemo.engine.domain.testing.UtteranceRepositoryContract
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ObservableUtteranceRepositoryContractTest : UtteranceRepositoryContract() {
    override fun repository(): UtteranceRepository =
        ObservableUtteranceRepository(InMemoryUtteranceRepository()) {}
}

class ObservableUtteranceRepositoryTest {
    @Test
    fun `保存のたびに知らせる`() = runTest {
        var changes = 0
        val repository = ObservableUtteranceRepository(InMemoryUtteranceRepository()) { changes++ }

        repository.insert(NewUtterance(0, 100, "本文", null, 100))

        assertEquals(1, changes)
    }

    @Test
    fun `まとめて保存しても 1 回だけ知らせる`() = runTest {
        var changes = 0
        val repository = ObservableUtteranceRepository(InMemoryUtteranceRepository()) { changes++ }

        repository.insertAll(listOf(NewUtterance(0, 100, "前", null, 100), NewUtterance(100, 200, "後", null, 200)))

        assertEquals(1, changes)
    }
}
