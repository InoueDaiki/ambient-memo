package dev.ambientmemo.engine.domain

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** バッファの解放は言語のメモリの扱いに依るので、ここに残す。値の検査は engine/spec/model-validation.json にある。 */
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
}
