package dev.ambientmemo.engine.android

/** Debug は開発用の Fake で動かす。 */
internal fun speechComponents(): SpeechComponents? = SpeechComponents(
    voiceActivityDetector = DevelopmentVoiceActivityDetector(),
    transcriber = DevelopmentTranscriber(),
    isDevelopment = true,
)
