import Foundation

/**
 * 発話の区切りとバッファの上限。既定値は仮で、実機と公開データで見直す。
 *
 * Swift では初期化が値を確かめて [IllegalArgumentException] を投げる。既定値は [default] にある。
 */
public struct CaptureConfig: Sendable, Hashable {
    /// 発話区間の上限。超えると分割する。メモリの上限と文字起こしの負荷を決める。
    public let maxUtteranceMillis: Int64
    /// この長さの無音が続いたら発話の終わりとする。息継ぎでは区切らない。
    public let minSilenceMillis: Int64
    /// 発話の検知より前に残す音声の長さ。語頭の欠けを防ぐ。
    public let preRollMillis: Int64
    /// これより短い発話は雑音として捨てる。欠落としては記録しない。
    public let minSpeechMillis: Int64
    /// 文字起こし待ちの音声の合計の上限。超えると古い発話から捨てる。
    public let maxBacklogMillis: Int64
    /// フレームの時刻がこれ以上ずれたら、音声が途切れたものとして扱う。
    public let discontinuityToleranceMillis: Int64

    public init(
        maxUtteranceMillis: Int64 = DEFAULT_MAX_UTTERANCE_MILLIS,
        minSilenceMillis: Int64 = DEFAULT_MIN_SILENCE_MILLIS,
        preRollMillis: Int64 = DEFAULT_PRE_ROLL_MILLIS,
        minSpeechMillis: Int64 = DEFAULT_MIN_SPEECH_MILLIS,
        maxBacklogMillis: Int64 = DEFAULT_MAX_BACKLOG_MILLIS,
        discontinuityToleranceMillis: Int64 = DEFAULT_DISCONTINUITY_TOLERANCE_MILLIS
    ) throws {
        try require(minSilenceMillis > 0, "minSilenceMillis は正の値です")
        try require(preRollMillis >= 0, "preRollMillis は 0 以上です")
        try require(minSpeechMillis >= 0, "minSpeechMillis は 0 以上です")
        try require(discontinuityToleranceMillis >= 0, "discontinuityToleranceMillis は 0 以上です")
        try require(
            maxUtteranceMillis > max(minSilenceMillis, preRollMillis, minSpeechMillis),
            "maxUtteranceMillis は無音、語頭、最短の発話の長さより長くします"
        )
        try require(maxBacklogMillis >= maxUtteranceMillis, "maxBacklogMillis は発話の上限以上にします")
        self.maxUtteranceMillis = maxUtteranceMillis
        self.minSilenceMillis = minSilenceMillis
        self.preRollMillis = preRollMillis
        self.minSpeechMillis = minSpeechMillis
        self.maxBacklogMillis = maxBacklogMillis
        self.discontinuityToleranceMillis = discontinuityToleranceMillis
    }

    /// Kotlin の `CaptureConfig()`。
    // swiftlint:disable:next force_try
    public static let `default` = try! CaptureConfig()

    @usableFromInline static let DEFAULT_MAX_UTTERANCE_MILLIS: Int64 = 30_000
    @usableFromInline static let DEFAULT_MIN_SILENCE_MILLIS: Int64 = 700
    @usableFromInline static let DEFAULT_PRE_ROLL_MILLIS: Int64 = 300
    @usableFromInline static let DEFAULT_MIN_SPEECH_MILLIS: Int64 = 200
    @usableFromInline static let DEFAULT_MAX_BACKLOG_MILLIS: Int64 = 120_000
    @usableFromInline static let DEFAULT_DISCONTINUITY_TOLERANCE_MILLIS: Int64 = 200
}

let MILLIS_PER_SECOND: Int64 = 1_000

/// Kotlin の Int に収まらなければ [ArithmeticException] を投げる。
func millisToSamples(_ millis: Int64, _ sampleRateHz: Int) throws -> Int {
    let (product, overflow) = millis.multipliedReportingOverflow(by: Int64(sampleRateHz))
    // Kotlin の Long の掛け算はあふれても投げずに回るが、その後の toIntExact でほぼ必ず投げる
    if overflow { throw ArithmeticException("long overflow") }
    return try toIntExact(product / MILLIS_PER_SECOND)
}

func samplesToMillis(_ samples: Int, _ sampleRateHz: Int) -> Int64 {
    Int64(samples) * MILLIS_PER_SECOND / Int64(sampleRateHz)
}
