@file:Suppress("FunctionNaming") // テスト名は日本語の文にする

package dev.ambientmemo.engine.domain

import dev.ambientmemo.engine.domain.testing.TEST_FRAME_SAMPLES
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 渡されたフレームを数え、[speaking] の間だけ発話と答える。 */
private class CountingDetector(var speaking: Boolean = false) : VoiceActivityDetector {
    var frames = 0
    var resets = 0

    override fun isSpeech(frame: AudioFrame): Boolean {
        frames++
        return speaking
    }

    override fun reset() {
        resets++
    }
}

/** 振幅が一定の矩形の音。RMS は振幅そのもの。 */
private fun frame(amplitude: Int) = AudioFrame(0, ShortArray(TEST_FRAME_SAMPLES) { amplitude.toShort() })

// -55 dBFS は振幅 58 ほど
private val quiet = frame(30)
private val loud = frame(1_000)

class QuietGateTest {
    @Test
    fun `フレームの大きさを dBFS で測る`() {
        assertEquals(0.0, frame(Short.MAX_VALUE.toInt() + 1).levelDbfs(), 0.01)
        assertEquals(-20.0, frame(3_277).levelDbfs(), 0.01)
        assertTrue(frame(0).levelDbfs() < -100)
    }

    @Test
    fun `静かなフレームは VAD に渡さず、無音と答える`() {
        val delegate = CountingDetector(speaking = true)
        val gate = QuietGatedVoiceActivityDetector(delegate)

        repeat(5) { assertFalse(gate.isSpeech(quiet)) }

        assertEquals(0, delegate.frames)
    }

    @Test
    fun `大きな音は VAD に渡し、その判定を返す`() {
        val delegate = CountingDetector(speaking = true)
        val gate = QuietGatedVoiceActivityDetector(delegate)

        assertTrue(gate.isSpeech(loud))
        assertEquals(1, delegate.frames)
    }

    @Test
    fun `VAD が発話と判定している間は、静かなフレームも渡す`() {
        val delegate = CountingDetector(speaking = true)
        val gate = QuietGatedVoiceActivityDetector(delegate)

        gate.isSpeech(loud)
        assertTrue(gate.isSpeech(quiet))
        delegate.speaking = false
        assertFalse(gate.isSpeech(quiet))
        assertFalse(gate.isSpeech(quiet))

        // 区切りの無音を VAD が判定し終えるまでの 2 つを渡し、そのあとは飛ばす
        assertEquals(3, delegate.frames)
    }

    @Test
    fun `飛ばしたあとは、VAD の状態を捨ててから渡す`() {
        val delegate = CountingDetector()
        val gate = QuietGatedVoiceActivityDetector(delegate)

        gate.isSpeech(loud)
        gate.isSpeech(loud)
        assertEquals(0, delegate.resets)
        gate.isSpeech(quiet)
        gate.isSpeech(loud)

        assertEquals(1, delegate.resets)
    }

    @Test
    fun `大きさの分布と飛ばした数を数え、取り出すと 0 に戻る`() {
        val levels = AudioLevelCounter()
        val gate = QuietGatedVoiceActivityDetector(CountingDetector(), levels = levels)

        gate.isSpeech(quiet)
        gate.isSpeech(quiet)
        gate.isSpeech(loud)
        val summary = levels.drain()

        assertEquals(AudioLevelSummary(frames = 3, skippedFrames = 2, histogram = listOf(-65 to 2, -35 to 1)), summary)
        assertNull(levels.drain())
    }
}
