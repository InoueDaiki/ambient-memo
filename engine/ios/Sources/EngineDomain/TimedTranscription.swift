import Foundation

/// ASR のトークン。[offsetMillis] は発話区間の先頭からの、トークンの開始時刻。
public struct TranscriptToken: Sendable, Hashable {
    public let text: String
    public let offsetMillis: Int64

    public init(_ text: String, _ offsetMillis: Int64) {
        self.text = text
        self.offsetMillis = offsetMillis
    }
}

/**
 * トークンの時刻の付いた文字起こし。話者の交代で本文を分けるときに使う。
 *
 * [tokens] は時刻の順で、つなげると本文になる。実装が時刻を返せないときは空にする。
 * そのときは本文を文字数で比例配分する(`SpeakerTurns.swift` の `timedTokens`)。
 */
public struct TimedTranscription: Sendable, Hashable {
    public let transcription: Transcription
    public let tokens: [TranscriptToken]

    public init(_ transcription: Transcription, _ tokens: [TranscriptToken] = []) {
        self.transcription = transcription
        self.tokens = tokens
    }

    public var text: String { transcription.text }
}
