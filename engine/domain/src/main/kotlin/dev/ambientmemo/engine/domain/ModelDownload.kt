package dev.ambientmemo.engine.domain

enum class ModelDownloadPhase { DOWNLOADING, VERIFYING, EXTRACTING }

enum class ModelDownloadFailure {
    /** 通信が切れたか、取得先が応じなかった。途中までのファイルは残し、続きから取得できる。 */
    NETWORK,

    /** 配布物が期待と違った。途中までのファイルは消したので、最初から取得する。 */
    CORRUPTED,

    /** 端末の空き容量が足りない。途中までのファイルは残す。 */
    INSUFFICIENT_STORAGE,

    /** それ以外の失敗。 */
    UNKNOWN,
}

/** [completedBytes] / [totalBytes] は [phase] の進み具合。段階ごとに対象のバイト数が違う。 */
data class ModelDownloadProgress(
    val phase: ModelDownloadPhase,
    val completedBytes: Long,
    val totalBytes: Long,
)

/** これから取得する量。[totalBytes] は配布物の大きさの合計で、[downloadedBytes] はそのうち端末にある分。 */
data class ModelDownloadPlan(val totalBytes: Long, val downloadedBytes: Long) {
    /** 取得するものがない。 */
    val isComplete: Boolean
        get() = totalBytes == 0L
}

/** [shortageBytes] は空き容量の不足分。容量不足を取得の前に見つけたときだけ 0 より大きい。 */
class ModelDownloadException(
    val failure: ModelDownloadFailure,
    val shortageBytes: Long = 0,
    cause: Throwable? = null,
) : Exception(failure.name, cause)

/** 取得の状態。止まっているときに取得するものが残っているかは [ModelDownloadPlan] で見る。 */
sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState

    data class Running(val progress: ModelDownloadProgress) : ModelDownloadState

    data class Failed(val failure: ModelDownloadFailure, val shortageBytes: Long = 0) : ModelDownloadState
}
