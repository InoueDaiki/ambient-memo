package dev.ambientmemo.engine.domain

/** 発話の区切りとバッファの上限。既定値は仮で、実機と公開データで見直す。 */
data class CaptureConfig(
    /** 発話区間の上限。超えると分割する。メモリの上限と文字起こしの負荷を決める。 */
    val maxUtteranceMillis: Long = DEFAULT_MAX_UTTERANCE_MILLIS,
    /** この長さの無音が続いたら発話の終わりとする。息継ぎでは区切らない。 */
    val minSilenceMillis: Long = DEFAULT_MIN_SILENCE_MILLIS,
    /** 発話の検知より前に残す音声の長さ。語頭の欠けを防ぐ。 */
    val preRollMillis: Long = DEFAULT_PRE_ROLL_MILLIS,
    /** これより短い発話は雑音として捨てる。欠落としては記録しない。 */
    val minSpeechMillis: Long = DEFAULT_MIN_SPEECH_MILLIS,
    /** 文字起こし待ちの音声の合計の上限。超えると古い発話から捨てる。 */
    val maxBacklogMillis: Long = DEFAULT_MAX_BACKLOG_MILLIS,
    /** フレームの時刻がこれ以上ずれたら、音声が途切れたものとして扱う。 */
    val discontinuityToleranceMillis: Long = DEFAULT_DISCONTINUITY_TOLERANCE_MILLIS,
) {
    init {
        require(minSilenceMillis > 0) { "minSilenceMillis は正の値です" }
        require(preRollMillis >= 0) { "preRollMillis は 0 以上です" }
        require(minSpeechMillis >= 0) { "minSpeechMillis は 0 以上です" }
        require(discontinuityToleranceMillis >= 0) { "discontinuityToleranceMillis は 0 以上です" }
        require(maxUtteranceMillis > maxOf(minSilenceMillis, preRollMillis, minSpeechMillis)) {
            "maxUtteranceMillis は無音、語頭、最短の発話の長さより長くします"
        }
        require(maxBacklogMillis >= maxUtteranceMillis) { "maxBacklogMillis は発話の上限以上にします" }
    }

    private companion object {
        const val DEFAULT_MAX_UTTERANCE_MILLIS = 30_000L
        const val DEFAULT_MIN_SILENCE_MILLIS = 700L
        const val DEFAULT_PRE_ROLL_MILLIS = 300L
        const val DEFAULT_MIN_SPEECH_MILLIS = 200L
        const val DEFAULT_MAX_BACKLOG_MILLIS = 120_000L
        const val DEFAULT_DISCONTINUITY_TOLERANCE_MILLIS = 200L
    }
}

internal const val MILLIS_PER_SECOND = 1_000L

internal fun millisToSamples(millis: Long, sampleRateHz: Int): Int =
    Math.toIntExact(millis * sampleRateHz / MILLIS_PER_SECOND)

internal fun samplesToMillis(samples: Int, sampleRateHz: Int): Long = samples * MILLIS_PER_SECOND / sampleRateHz
