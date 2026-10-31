package dev.ambientmemo.engine.domain

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelsTest {
    @Test
    fun `解放すると音声を 0 で塗りつぶし、読めなくなる`() {
        val samples = shortArrayOf(1, 2, 3)
        val segment = SpeechSegment(0, 1, 16_000, samples)
        assertFalse(segment.isReleased)

        segment.close()

        assertTrue(segment.isReleased)
        assertArrayEquals(ShortArray(3), samples)
        assertThrows(IllegalStateException::class.java) { segment.samples }
    }

    @Test
    fun `解放は何度呼んでもよい`() {
        val segment = SpeechSegment(0, 1, 16_000, shortArrayOf(1))

        segment.close()
        segment.close()

        assertTrue(segment.isReleased)
    }

    @Test
    fun `時刻が逆転した発話と欠落は作れない`() {
        assertThrows(IllegalArgumentException::class.java) { NewUtterance(10, 9, "本文", null, 10) }
        assertThrows(IllegalArgumentException::class.java) {
            CaptureGap(10, 9, CaptureGapReason.PROCESSING_FAILED)
        }
    }

    @Test
    fun `範囲外の信頼度は受け付けない`() {
        assertThrows(IllegalArgumentException::class.java) { Transcription("本文", 1.5) }
        assertThrows(IllegalArgumentException::class.java) { NewUtterance(0, 1, "本文", -0.1, 1) }
    }

    @Test
    fun `矛盾する設定は受け付けない`() {
        assertThrows(IllegalArgumentException::class.java) {
            CaptureConfig(maxUtteranceMillis = 500, minSilenceMillis = 500)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CaptureConfig(maxUtteranceMillis = 30_000, maxBacklogMillis = 29_999)
        }
    }
}
