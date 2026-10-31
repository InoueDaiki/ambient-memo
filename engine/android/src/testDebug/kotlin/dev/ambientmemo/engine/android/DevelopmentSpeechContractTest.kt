@file:Suppress("FunctionNaming") // テスト名は日本語の文にする

package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.AudioFrame
import dev.ambientmemo.engine.domain.Transcriber
import dev.ambientmemo.engine.domain.VoiceActivityDetector
import dev.ambientmemo.engine.domain.testing.TEST_SAMPLE_RATE_HZ
import dev.ambientmemo.engine.domain.testing.TranscriberContract
import dev.ambientmemo.engine.domain.testing.VoiceActivityDetectorContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DevelopmentVoiceActivityDetectorContractTest : VoiceActivityDetectorContract() {
    override fun detector(speechMillis: LongRange): VoiceActivityDetector =
        DevelopmentVoiceActivityDetector(speechMillis, periodMillis = 10_000, TEST_SAMPLE_RATE_HZ)
}

class DevelopmentTranscriberContractTest : TranscriberContract() {
    override fun transcriber(): Transcriber = DevelopmentTranscriber()
}

class DevelopmentVoiceActivityDetectorTest {
    @Test
    fun `発話の判定を周期的にくり返す`() {
        val detector = DevelopmentVoiceActivityDetector(100L..<200L, periodMillis = 300, TEST_SAMPLE_RATE_HZ)
        val frame = AudioFrame(0, ShortArray(TEST_SAMPLE_RATE_HZ / 10))

        val results = List(6) { detector.isSpeech(frame) }

        assertEquals(listOf(false, true, false, false, true, false), results)
    }
}
