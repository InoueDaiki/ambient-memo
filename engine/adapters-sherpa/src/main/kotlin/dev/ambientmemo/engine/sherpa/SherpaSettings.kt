package dev.ambientmemo.engine.sherpa

import dev.ambientmemo.engine.domain.CaptureConfig

/**
 * sherpa-onnx に渡す値。docs/検証-ASR.md の仮決定で、Mac でしか測っていない。実機で見直す。
 */
object SherpaSettings {
    const val SAMPLE_RATE_HZ = 16_000
    const val VAD_THRESHOLD = 0.5f

    /** 発話の判定が出るまでに要る長さ。これより短い音は発話にしない。 */
    const val VAD_MIN_SPEECH_SECONDS = 0.25f

    /**
     * 発話を区切る無音の長さ。検証の 0.5〜0.8 秒のうち、文字起こしの CER が最もよかった値。
     *
     * 区切りは domain の `minSilenceMillis` ではなく、ここで決める。短くして domain に任せると、
     * 発話の途中の小さな音が最短の発話の長さに届かず、無音扱いになって語が落ちた。
     */
    const val VAD_MIN_SILENCE_SECONDS = 0.8f
    const val VAD_MAX_SPEECH_SECONDS = 20f
    const val VAD_WINDOW_SAMPLES = 512
    const val VAD_THREADS = 1

    /** Pixel では測っていない。Mac では 1 スレッドでも実時間比 0.012 だった。 */
    const val ASR_THREADS = 2
    const val ASR_FEATURE_DIM = 80
    const val ASR_DECODING_METHOD = "greedy_search"
    const val ASR_LANGUAGE = "ja"

    /** 検証は Mac の 1 スレッドで実時間比 0.005。Pixel では測っていない。 */
    const val SPEAKER_THREADS = 1

    /** 区間の前後に足す無音の長さ。 */
    const val PADDING_MILLIS = 300L

    /** 発話の区間の前に残す実音声の長さ。 */
    private const val CONTEXT_MILLIS = 300L

    /**
     * VAD が発話と判定するのは、音が始まってから最短の発話の長さ(0.25 秒)と 2 窓(64 ミリ秒)の後になる。
     * 判定より前の音声も実音声の前置きに含める必要があるので、その遅れを足す。切りのよい値に丸めた。
     */
    private const val DETECTION_LAG_MILLIS = 300L

    private const val MAX_UTTERANCE_MILLIS = 20_000L

    /** 続けて読み上げても登録の発話が揃うように、[MAX_UTTERANCE_MILLIS] より短く分ける。 */
    private const val MAX_ENROLLMENT_UTTERANCE_MILLIS = 6_000L

    /** VAD が無音を判定した後は、すぐ区切る。フレームは 0.1 秒。 */
    private const val MIN_SILENCE_MILLIS = 100L

    /**
     * sherpa-onnx の VAD で区切るときの [CaptureConfig]。
     *
     * VAD は区切りの無音の間も発話と判定し続けるので、発話の区間の後ろには実音声が [VAD_MIN_SILENCE_SECONDS] ぶん
     * 入る。検証の「後ろの 0.3 秒」もこの無音の後に足していたが、十分な長さがあるので足さない。
     * その分、保存する発話の終了時刻は実際の発話の終わりより遅くなる。
     * 短い音は VAD が落とすので、最短の長さは見ない。
     */
    fun captureConfig(): CaptureConfig = CaptureConfig(
        maxUtteranceMillis = MAX_UTTERANCE_MILLIS,
        minSilenceMillis = MIN_SILENCE_MILLIS,
        preRollMillis = CONTEXT_MILLIS + DETECTION_LAG_MILLIS,
        minSpeechMillis = 0,
    )

    /** 自分の声の登録の区切り。短く分ける以外は [captureConfig] と同じにして、照合する区間と同じ形の音声を使う。 */
    fun enrollmentCaptureConfig(): CaptureConfig =
        captureConfig().copy(maxUtteranceMillis = MAX_ENROLLMENT_UTTERANCE_MILLIS)
}
