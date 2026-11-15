package dev.ambientmemo.engine.sherpa

import dev.ambientmemo.engine.domain.SpeakerConfig
import dev.ambientmemo.engine.domain.Tuning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SherpaSettingsTest {
    @Test
    fun `VAD の既定値は調整の値の既定値と同じ`() {
        assertEquals(SherpaSettings.VAD_THRESHOLD, Tuning.DEFAULT.vadThreshold)
        assertEquals(SherpaSettings.VAD_MIN_SILENCE_SECONDS, Tuning.DEFAULT.vadMinSilenceSeconds)
    }

    @Test
    fun `話者の判定の区切りの無音は VAD の区切りの無音と同じ`() {
        assertEquals(
            (SherpaSettings.VAD_MIN_SILENCE_SECONDS * 1_000).toLong(),
            SpeakerConfig().trailingSilenceMillis,
        )
    }
}
