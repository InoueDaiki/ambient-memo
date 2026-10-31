package dev.ambientmemo.engine.domain

/** ASR のトークン。[offsetMillis] は発話区間の先頭からの、トークンの開始時刻。 */
data class TranscriptToken(
    val text: String,
    val offsetMillis: Long,
)

/**
 * トークンの時刻の付いた文字起こし。話者の交代で本文を分けるときに使う。
 *
 * [tokens] は時刻の順で、つなげると本文になる。実装が時刻を返せないときは空にする。
 * そのときは本文を文字数で比例配分する(`SpeakerTurns.kt` の `timedTokens`)。
 */
data class TimedTranscription(
    val transcription: Transcription,
    val tokens: List<TranscriptToken> = emptyList(),
) {
    val text: String
        get() = transcription.text
}
