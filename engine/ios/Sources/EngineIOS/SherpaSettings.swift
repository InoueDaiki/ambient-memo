import EngineDomain
import Foundation

/**
 * sherpa-onnx に渡す値。Android の `SherpaSettings`(engine/adapters-sherpa)と同じ値にする。
 * docs/検証-ASR.md の仮決定で、Mac でしか測っていない。実機で見直す。
 */
public enum SherpaSettings {
    public static let SAMPLE_RATE_HZ = 16_000
    public static let VAD_THRESHOLD: Float = 0.5

    /// 発話の判定が出るまでに要る長さ。これより短い音は発話にしない。
    public static let VAD_MIN_SPEECH_SECONDS: Float = 0.25

    /// 発話を区切る無音の長さ。区切りは domain の `minSilenceMillis` ではなく、ここで決める(Android と同じ理由)。
    public static let VAD_MIN_SILENCE_SECONDS: Float = 0.8
    public static let VAD_MAX_SPEECH_SECONDS: Float = 20
    public static let VAD_WINDOW_SAMPLES: Int32 = 512
    public static let VAD_THREADS: Int32 = 1

    /// VAD がネイティブ側に溜める音声の上限。Android の JNI の既定値と同じ。区間は毎回捨てるので溜まらない。
    public static let VAD_BUFFER_SECONDS: Float = 60

    public static let ASR_THREADS: Int32 = 2
    public static let ASR_FEATURE_DIM: Int32 = 80
    public static let ASR_DECODING_METHOD = "greedy_search"
    public static let ASR_LANGUAGE = "ja"

    /// Kotlin の OfflineRecognizerConfig の既定値。C API は 0 で初期化されるので明示する。
    static let ASR_MAX_ACTIVE_PATHS: Int32 = 4
    static let ASR_HOTWORDS_SCORE: Float = 1.5

    public static let SPEAKER_THREADS: Int32 = 1

    /// これより短い音声からは話者の特徴量を作らない。検証と同じ値。
    public static let MIN_SPEAKER_MILLIS: Int64 = 400

    /// 区間の前後に足す無音の長さ。
    public static let PADDING_MILLIS: Int64 = 300

    /// 発話の区間の前に残す実音声の長さ。
    private static let CONTEXT_MILLIS: Int64 = 300

    /// VAD の判定の遅れ。判定より前の音声も前置きに含める。
    private static let DETECTION_LAG_MILLIS: Int64 = 300

    private static let MAX_UTTERANCE_MILLIS: Int64 = 20_000

    /// 続けて読み上げても登録の発話が揃うように、[MAX_UTTERANCE_MILLIS] より短く分ける。
    private static let MAX_ENROLLMENT_UTTERANCE_MILLIS: Int64 = 6_000

    /// VAD が無音を判定した後は、すぐ区切る。フレームは 0.1 秒。
    private static let MIN_SILENCE_MILLIS: Int64 = 100

    /// sherpa-onnx の VAD で区切るときの [CaptureConfig]。値の理由は Android の `SherpaSettings.captureConfig` を参照。
    public static func captureConfig() -> CaptureConfig {
        // 値は固定で、条件を満たすので失敗しない
        // swiftlint:disable:next force_try
        try! CaptureConfig(
            maxUtteranceMillis: MAX_UTTERANCE_MILLIS,
            minSilenceMillis: MIN_SILENCE_MILLIS,
            preRollMillis: CONTEXT_MILLIS + DETECTION_LAG_MILLIS,
            minSpeechMillis: 0
        )
    }

    /// 自分の声の登録の区切り。短く分ける以外は [captureConfig] と同じ。
    public static func enrollmentCaptureConfig() -> CaptureConfig {
        let base = captureConfig()
        // swiftlint:disable:next force_try
        return try! CaptureConfig(
            maxUtteranceMillis: MAX_ENROLLMENT_UTTERANCE_MILLIS,
            minSilenceMillis: base.minSilenceMillis,
            preRollMillis: base.preRollMillis,
            minSpeechMillis: base.minSpeechMillis,
            maxBacklogMillis: base.maxBacklogMillis,
            discontinuityToleranceMillis: base.discontinuityToleranceMillis
        )
    }
}

/// LLM の実行基盤の設定。Android の `LlmSettings` と同じ値にする。変えると検証の結果が当てはまらなくなる。
public enum LlmSettings {
    /// 入力と出力の合計の上限。
    public static let MAX_NUM_TOKENS = 4096
    public static let CPU_THREADS = 4
    public static let TOP_K = 1
    public static let TOP_P: Float = 1.0
    public static let TEMPERATURE: Float = 0.0
    public static let SEED = 1
}
