package dev.ambientmemo.engine.adapters

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.ambientmemo.engine.adapters.db.AmbientMemoDatabase
import dev.ambientmemo.engine.domain.MergedEmbedding
import dev.ambientmemo.engine.domain.NewUtterance
import dev.ambientmemo.engine.domain.SpeakerConsolidation
import dev.ambientmemo.engine.domain.SpeakerEmbedding
import dev.ambientmemo.engine.domain.SpeakerMerge
import dev.ambientmemo.engine.domain.testing.FakeClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Properties

/** 話者の統合が本物の SQLite で 1 つのトランザクションになることと、以前の版の DB をまとめ直せることを確かめる。 */
class SpeakerMergeIntegrationTest {
    private val model = "model-1"
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), AmbientMemoDatabase.Schema)
    private val database = AmbientMemoDatabase(driver)
    private val speakers = SqliteSpeakerRepository(database, Dispatchers.Unconfined)
    private val utterances = SqliteUtteranceRepository(database, Dispatchers.Unconfined)

    @AfterEach
    fun close() = driver.close()

    private fun axis(index: Int) = FloatArray(32) { if (it == index) 1f else 0f }

    private suspend fun provisional(index: Int) =
        speakers.createProvisionalSpeaker(SpeakerEmbedding(axis(index), model), 1, 2_000)

    private suspend fun utter(speakerId: Long?) = utterances.insert(NewUtterance(0, 1, "本文", null, 1, speakerId, 0.5))

    @Test
    fun `リセットすると発話は残り、話者だけが外れる`() = runTest {
        val self = speakers.saveSelfEmbedding(SpeakerEmbedding(axis(0), model), 3, 1)
        val other = provisional(1)
        val rows = listOf(utter(self.id), utter(other.id), utter(null))

        speakers.resetSpeakers()

        assertTrue(speakers.listSpeakers().isEmpty())
        rows.forEach { row ->
            val stored = checkNotNull(utterances.find(row.id))
            assertEquals("本文", stored.text)
            assertNull(stored.speakerId)
            assertNull(stored.speakerConfidence)
        }
    }

    @Test
    fun `途中で失敗すると何も変わらない`() = runTest {
        val into = provisional(1)
        val first = provisional(2)
        val second = provisional(3)
        val row = utter(first.id)
        val broken = MergedEmbedding(SpeakerEmbedding(axis(4), model), sampleCount = 0, voicedMillis = 0)

        assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking {
                speakers.mergeSpeakers(
                    listOf(SpeakerMerge(first.id, into.id, null), SpeakerMerge(second.id, into.id, broken)),
                    5,
                )
            }
        }

        assertEquals(first.id, utterances.find(row.id)?.speakerId)
        assertEquals(3, speakers.listSpeakers().size)
    }

    @Test
    fun `同じ統合をもう一度渡しても壊れない`() = runTest {
        val into = provisional(1)
        val from = provisional(2)
        val row = utter(from.id)
        val merged = MergedEmbedding(SpeakerEmbedding(axis(5), model), 2, 4_000)
        val merge = SpeakerMerge(from.id, into.id, merged)

        speakers.mergeSpeakers(listOf(merge), 5)
        val again = MergedEmbedding(SpeakerEmbedding(axis(6), model), 9, 9_000)
        speakers.mergeSpeakers(listOf(SpeakerMerge(from.id, into.id, again)), 6)

        assertEquals(into.id, utterances.find(row.id)?.speakerId)
        val stored = speakers.listEmbeddings(model, 10).single()
        assertEquals(2, stored.sampleCount)
        assertEquals(1f, stored.vector[5])
    }

    @Test
    fun `消した話者の番号を再び使う`() = runTest {
        val into = provisional(1)
        val from = provisional(2)

        speakers.mergeSpeakers(listOf(SpeakerMerge(from.id, into.id, null)), 5)

        assertEquals("話者2", provisional(3).displayName)
    }

    @Test
    fun `話者 19 人の DB をまとめ直しても、発話は 1 行も失わない`() = runTest {
        val ids = List(19) { provisional(it % 3).id }
        val rows = ids.map { utter(it).id }

        SpeakerConsolidation(speakers, model, FakeClock(9)).run()

        val left = speakers.listSpeakers().map { it.id }.toSet()
        assertEquals(3, left.size)
        assertTrue(rows.all { utterances.find(it)?.speakerId in left })
        assertEquals(19, utterances.countBySpeaker().values.sum())
        assertEquals(listOf("話者1", "話者2", "話者3"), speakers.listSpeakers().map { it.displayName })
    }
}
