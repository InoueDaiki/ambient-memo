package dev.ambientmemo.engine.domain

// 時刻はすべて UNIX エポックからのミリ秒。java.time は minSdk 24 の端末で使えないので持ち込まない

/**
 * 16 ビットのモノラル PCM のひとかたまり。
 *
 * [samples] は [AudioSource] の持ち物で、collect の処理から戻ると再利用されることがある。
 * 残したい場合は受け取った側でコピーする。
 */
class AudioFrame(
    val startedAtMillis: Long,
    val samples: ShortArray,
)

data class Transcription(
    val text: String,
    val confidence: Double? = null,
) {
    init {
        requireConfidence(confidence)
    }
}

/** 話者の特徴量。モデルが変わると比べられないので、版を一緒に持つ。 */
class SpeakerEmbedding(
    val vector: FloatArray,
    val modelVersion: String,
)

/** 保存前の発話。 */
data class NewUtterance(
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val text: String,
    val confidence: Double?,
    val createdAtMillis: Long,
    val speakerId: Long? = null,
    val speakerConfidence: Double? = null,
) {
    init {
        require(endedAtMillis >= startedAtMillis) { "終了時刻が開始時刻より前です" }
        requireConfidence(confidence)
        requireConfidence(speakerConfidence)
    }
}

/** [speakerId] が null の発話は、話者を判定していないか、判定できなかった。[confidence] は文字起こしの信頼度。 */
data class Utterance(
    val id: Long,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val text: String,
    val confidence: Double?,
    val createdAtMillis: Long,
    val speakerId: Long? = null,
    val speakerConfidence: Double? = null,
)

/** 記録できなかった時間。 */
data class CaptureGap(
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val reason: CaptureGapReason,
) {
    init {
        require(endedAtMillis >= startedAtMillis) { "終了時刻が開始時刻より前です" }
    }
}

enum class CaptureGapReason {
    /** 文字起こしが追いつかず、古い発話を捨てた。 */
    PROCESSING_BACKLOG,

    /** 文字起こしか保存に失敗した。音声は残さないので、やり直せない。 */
    PROCESSING_FAILED,

    /** 音声のフレームの時刻が飛んだ。 */
    SOURCE_DISCONTINUITY,

    /** 処理を中断したときに、未処理の音声を捨てた。 */
    PIPELINE_STOPPED,
}

internal fun requireConfidence(confidence: Double?) {
    require(confidence == null || confidence in 0.0..1.0) { "信頼度は 0 から 1 の範囲です" }
}
