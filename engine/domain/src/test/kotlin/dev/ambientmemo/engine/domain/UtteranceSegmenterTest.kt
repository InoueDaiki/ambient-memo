package dev.ambientmemo.engine.domain

import dev.ambientmemo.engine.domain.testing.SyntheticAudio
import dev.ambientmemo.engine.domain.testing.TEST_SAMPLE_RATE_HZ
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 配列の参照を共有する言語の性質に依るテストだけを残す。切り出しの規則は engine/spec/utterance-segmenter.json にある。 */
class UtteranceSegmenterTest {
    private val config = CaptureConfig(
        maxUtteranceMillis = 1_000,
        minSilenceMillis = 100,
        preRollMillis = 50,
        minSpeechMillis = 30,
    )
    private val segmenter = UtteranceSegmenter(config, TEST_SAMPLE_RATE_HZ)

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
