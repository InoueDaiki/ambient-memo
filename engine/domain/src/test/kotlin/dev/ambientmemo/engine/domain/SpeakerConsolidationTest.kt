package dev.ambientmemo.engine.domain

import dev.ambientmemo.engine.domain.testing.FakeClock
import dev.ambientmemo.engine.domain.testing.InMemorySpeakerRepository
import dev.ambientmemo.engine.domain.testing.InMemoryUtteranceRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeakerConsolidationTest {
    private val model = "fake-1"
    private val utterances = InMemoryUtteranceRepository()
    private val speakers = InMemorySpeakerRepository(utterances)
    private val clock = FakeClock(9_000)
    private val consolidation = SpeakerConsolidation(speakers, model, clock)

    private fun unit(size: Int = 32, value: (Int) -> Float): FloatArray =
        checkNotNull(normalizedOrNull(FloatArray(size, value)))

    /** [group] の近くの声。同じ [group] どうしは近い。 */
    private fun voice(group: Int, variant: Int): FloatArray =
        unit { if (it == group) 1f else if (it == 10 + variant) 0.2f else 0f }

    private suspend fun provisional(vector: FloatArray, voicedMillis: Long = 4_000): Speaker =
        speakers.createProvisionalSpeaker(SpeakerEmbedding(vector, model), 1, voicedMillis)

    private suspend fun utter(speakerId: Long?) = utterances.insert(NewUtterance(0, 1, "本文", null, 1, speakerId, 0.5))

    @Test
    fun `増えた仮の話者を上限までまとめ、発話と特徴量を付け替え、番号を振り直す`() = runTest {
        val self = speakers.saveSelfEmbedding(SpeakerEmbedding(unit { if (it == 0) 1f else 0f }, model), 3, 0)
        val ids = List(19) { index -> provisional(voice(1 + index % 3, index)).id }
        ids.forEach { utter(it) }
        val selfRow = utter(self.id)
        val unknownRow = utter(null)

        val merged = consolidation.run()

        assertEquals(16, merged)
        val left = speakers.listSpeakers().filterNot { it.isSelf }
        assertEquals(listOf("話者1", "話者2", "話者3"), left.map { it.displayName })
        val rows = utterances.listRecent(100)
        assertEquals(left.map { it.id }.toSet(), rows.mapNotNull { it.speakerId }.filter { it != self.id }.toSet())
        assertEquals(self.id, rows.single { it.id == selfRow.id }.speakerId)
        assertEquals(null, rows.single { it.id == unknownRow.id }.speakerId)
        val stored = speakers.listEmbeddings(model, 100).filterNot { it.isSelf }
        assertEquals(3, stored.size)
        assertEquals(19, stored.sumOf { it.sampleCount })
        assertEquals(19 * 4_000L, stored.sumOf { it.voicedMillis })
    }

    @Test
    fun `同じ声のまとまりを近い順にまとめる`() = runTest {
        val first = List(5) { provisional(voice(1, it)).id }
        val second = List(5) { provisional(voice(2, it + 5)).id }
        first.forEach { utter(it) }
        second.forEach { utter(it) }

        consolidation.run()

        val rows = utterances.listRecent(100).sortedBy { it.id }
        assertEquals(1, rows.take(5).map { it.speakerId }.toSet().size)
        assertEquals(1, rows.drop(5).map { it.speakerId }.toSet().size)
        assertTrue(rows[0].speakerId != rows[5].speakerId)
    }

    @Test
    fun `もう一度動かしても何も変わらない`() = runTest {
        List(8) { provisional(voice(1 + it % 2, it)) }
        consolidation.run()
        val speakersBefore = speakers.listSpeakers()
        val vectorsBefore = speakers.listEmbeddings(model, 100).map { it.vector.toList() }

        assertEquals(0, consolidation.run())

        assertEquals(speakersBefore, speakers.listSpeakers())
        assertEquals(vectorsBefore, speakers.listEmbeddings(model, 100).map { it.vector.toList() })
    }

    @Test
    fun `上限以下なら何もしない`() = runTest {
        val ids = List(3) { provisional(voice(1, it)).id }

        assertEquals(0, consolidation.run())

        assertEquals(ids, speakers.listSpeakers().map { it.id })
    }

    @Test
    fun `手で名付けた話者と自分は統合しない`() = runTest {
        val self = speakers.saveSelfEmbedding(SpeakerEmbedding(voice(1, 0), model), 3, 0)
        val named = provisional(voice(1, 1))
        speakers.setManualName(named.id, "母")
        val inferred = provisional(voice(1, 2))
        speakers.setInferredName(inferred.id, "田中")
        repeat(6) { provisional(voice(1, 3 + it)) }

        consolidation.run()

        val left = speakers.listSpeakers()
        assertTrue(left.any { it.id == self.id })
        assertEquals("母", left.single { it.id == named.id }.displayName)
        assertEquals("田中", left.single { it.id == inferred.id }.displayName)
        assertEquals(listOf(self.id, named.id, inferred.id), left.take(3).map { it.id })
        assertTrue(left.count { it.nameSource == SpeakerNameSource.PROVISIONAL } <= 3)
    }

    @Test
    fun `手の名前と同じ番号は使わない`() = runTest {
        val named = provisional(voice(1, 0))
        speakers.setManualName(named.id, "話者1")
        repeat(5) { provisional(voice(2 + it, it)) }

        consolidation.run()

        val names = speakers.listSpeakers().map { it.displayName }
        assertEquals(names.toSet().size, names.size)
    }
}
