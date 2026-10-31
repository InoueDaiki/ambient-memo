package dev.ambientmemo.engine.domain

import dev.ambientmemo.engine.domain.testing.SyntheticAudio
import dev.ambientmemo.engine.domain.testing.TEST_SAMPLE_RATE_HZ
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UtteranceSegmenterTest {
    private val frameMillis = 10L
    private val config = CaptureConfig(
        maxUtteranceMillis = 1_000,
        minSilenceMillis = 100,
        preRollMillis = 50,
        minSpeechMillis = 30,
    )
    private val segmenter = UtteranceSegmenter(config, TEST_SAMPLE_RATE_HZ)
    private val emitted = mutableListOf<SpeechSegment>()
    private var position = 0L

    /** [millis] の長さのフレームを順に流す。音の値はフレームの位置から決める。 */
    private fun feed(millis: Long, isSpeech: Boolean) {
        repeat((millis / frameMillis).toInt()) {
            val samples = ShortArray((frameMillis * TEST_SAMPLE_RATE_HZ / 1_000).toInt()) {
                (position / frameMillis + 1).toShort()
            }
            emitted += segmenter.accept(AudioFrame(position, samples), isSpeech)
            position += frameMillis
        }
    }

    @Test
    fun `無音だけなら発話を作らない`() {
        feed(500, isSpeech = false)

        assertTrue(emitted.isEmpty())
        assertNull(segmenter.flush())
    }

    @Test
    fun `語頭の音声を含め、末尾の無音を除いて発話を切り出す`() {
        feed(200, isSpeech = false)
        feed(300, isSpeech = true)
        feed(100, isSpeech = false)

        val segment = emitted.single()
        assertEquals(150, segment.startedAtMillis)
        assertEquals(500, segment.endedAtMillis)
        assertEquals(350 * 16, segment.sampleCount)
        // 語頭の音声は、無音の 150ms 以降のフレームのもの
        assertEquals(16, segment.samples.first().toInt())
        assertEquals(50, segment.samples.last().toInt())
    }

    @Test
    fun `語頭が溜まる前に発話が始まっても、音声の先頭から切り出す`() {
        feed(20, isSpeech = false)
        feed(100, isSpeech = true)
        feed(100, isSpeech = false)

        assertEquals(0, emitted.single().startedAtMillis)
    }

    @Test
    fun `語頭のバッファが一周した後でも古い順に並ぶ`() {
        feed(73 * frameMillis, isSpeech = false)
        feed(100, isSpeech = true)
        feed(100, isSpeech = false)

        val head = emitted.single().samples.filterIndexed { index, _ -> index % 160 == 0 }.take(5)
        assertEquals(listOf(69, 70, 71, 72, 73), head.map { it.toInt() })
    }

    @Test
    fun `短い無音では発話を区切らない`() {
        feed(100, isSpeech = true)
        feed(90, isSpeech = false)
        feed(100, isSpeech = true)
        feed(100, isSpeech = false)

        val segment = emitted.single()
        assertEquals(0, segment.startedAtMillis)
        assertEquals(290, segment.endedAtMillis)
    }

    @Test
    fun `短すぎる発話は捨てる`() {
        feed(20, isSpeech = true)
        feed(100, isSpeech = false)

        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `上限を超える発話は、時刻が連続するように分割する`() {
        feed(2_500, isSpeech = true)
        feed(100, isSpeech = false)

        assertEquals(listOf(0L, 1_000L, 2_000L), emitted.map { it.startedAtMillis })
        assertEquals(listOf(1_000L, 2_000L, 2_500L), emitted.map { it.endedAtMillis })
        assertTrue(emitted.all { it.sampleCount <= 16_000 })
    }

    @Test
    fun `上限より長いフレームも上限で分割する`() {
        val samples = ShortArray(40_000) { it.toShort() }

        val segments = segmenter.accept(AudioFrame(0, samples), isSpeech = true)
        val tail = segmenter.flush()

        assertEquals(listOf(16_000, 16_000), segments.map { it.sampleCount })
        assertEquals(8_000, tail?.sampleCount)
        assertEquals(16_000.toShort(), segments[1].samples.first())
    }

    @Test
    fun `分割の続きは短くても捨てない`() {
        feed(1_010, isSpeech = true)
        feed(100, isSpeech = false)

        assertEquals(listOf(1_000L, 10L), emitted.map { it.endedAtMillis - it.startedAtMillis })
    }

    @Test
    fun `上限が無音の途中に来たら、末尾の無音を除いて区切り、続きを作らない`() {
        feed(960, isSpeech = true)
        feed(100, isSpeech = false)

        val segment = emitted.single()
        assertEquals(960, segment.endedAtMillis)
        assertNull(segmenter.flush())
    }

    @Test
    fun `発話の終わりの無音は次の発話の語頭になる`() {
        feed(100, isSpeech = true)
        feed(100, isSpeech = false)
        feed(100, isSpeech = true)
        feed(100, isSpeech = false)

        assertEquals(listOf(0L, 150L), emitted.map { it.startedAtMillis })
    }

    @Test
    fun `flush は開いている発話を閉じる`() {
        feed(100, isSpeech = true)

        assertEquals(100, segmenter.flush()?.endedAtMillis)
        assertNull(segmenter.flush())
    }

    @Test
    fun `discard は発話の途中の音声を消し、失われた時間を返す`() {
        feed(100, isSpeech = true)
        feed(50, isSpeech = false)

        assertEquals(CaptureSpan(0, 100), segmenter.discard())
        assertNull(segmenter.flush())
    }

    @Test
    fun `discard の後は語頭の音声も残らない`() {
        feed(200, isSpeech = false)
        segmenter.discard()
        position = 1_000

        feed(100, isSpeech = true)

        assertEquals(1_000, segmenter.flush()?.startedAtMillis)
    }

    @Test
    fun `渡したフレームの配列が書き換えられても発話は変わらない`() {
        val samples = SyntheticAudio.tone(100)
        val expected = samples.first { it != 0.toShort() }
        segmenter.accept(AudioFrame(0, samples), isSpeech = true)
        samples.fill(0)

        val segment = segmenter.flush()

        assertTrue(segment!!.samples.contains(expected))
    }
}
