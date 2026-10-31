package dev.ambientmemo.engine.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.TimeZone

class UtteranceChunkTest {
    private val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
    private val config = OrganizeConfig(minSilenceGapMillis = 1_000, maxChunkChars = 100)

    private fun utterance(id: Long, startedAt: Long, endedAt: Long = startedAt + 100, text: String = "本文") =
        Utterance(id, startedAt, endedAt, text, null, endedAt)

    private fun split(vararg utterances: Utterance) =
        splitIntoChunks(utterances.toList(), config, tokyo).map { chunk -> chunk.utterances.map { it.id } }

    @Test
    fun `発話がなければまとまりもない`() {
        assertTrue(split().isEmpty())
    }

    @Test
    fun `無音が基準より短ければつなげ、基準以上なら分ける`() {
        val chunks = split(
            utterance(1, 0, 100),
            utterance(2, 1_099, 1_200),
            utterance(3, 2_200, 2_300),
        )

        assertEquals(listOf(listOf(1L, 2L), listOf(3L)), chunks)
    }

    @Test
    fun `長い発話の中に短い発話が重なっても、無音を長い発話の終わりから測る`() {
        val chunks = split(
            utterance(1, 0, 5_000),
            utterance(2, 100, 200),
            utterance(3, 5_500, 5_600),
        )

        assertEquals(listOf(listOf(1L, 2L, 3L)), chunks)
    }

    @Test
    fun `文字数の上限で発話の切れ目で分ける`() {
        val text = "あ".repeat(40)

        val chunks = split(
            utterance(1, 0, text = text),
            utterance(2, 200, text = text),
            utterance(3, 400, text = text),
        )

        assertEquals(listOf(listOf(1L, 2L), listOf(3L)), chunks)
    }

    @Test
    fun `上限より長い発話も切らずに1つのまとまりにする`() {
        val chunks = split(utterance(1, 0), utterance(2, 200, text = "あ".repeat(500)), utterance(3, 400))

        assertEquals(listOf(listOf(1L), listOf(2L), listOf(3L)), chunks)
    }

    @Test
    fun `最後のまとまりは、基準の長さだけ静かになるまで除く`() {
        val chunks = splitIntoChunks(listOf(utterance(1, 0), utterance(2, 5_000, 5_100)), config, tokyo)

        assertEquals(2, chunks.withoutOngoing(nowMillis = 6_100, config).size)
        assertEquals(1, chunks.withoutOngoing(nowMillis = 6_099, config).size)
    }

    @Test
    fun `端末の時刻で文字起こしの行を作る`() {
        // 2026-11-01T15:30:00Z は日本時間の翌日 0:30
        val line = utterance(1, 1_793_547_000_000L, text = " 本文 ").toTranscriptLine(tokyo)

        assertEquals("[00:30] 本文", line)
    }

    @Test
    fun `空の発話は文字起こしに入れないが、まとまりには残す`() {
        val chunk = UtteranceChunk(listOf(utterance(1, 0, text = "  "), utterance(2, 200, text = "本文")))

        assertEquals("[09:00] 本文", chunk.toTranscript(tokyo))
        assertEquals(2, chunk.utterances.size)
        assertFalse(chunk.isBlank)
        assertTrue(UtteranceChunk(listOf(utterance(1, 0, text = " "))).isBlank)
    }
}
