import Foundation

/// 文字起こしの結果を保存するかを決める。保存しない発話は欠落にしない。
public protocol TranscriptionFilter: Sendable {
    func shouldSave(_ text: String, durationMillis: Int64) -> Bool
}

/// Kotlin の `fun interface` の SAM 変換にあたる。`TranscriptionFilter { _, _ -> true }` は `ClosureTranscriptionFilter { _, _ in true }`。
public struct ClosureTranscriptionFilter: TranscriptionFilter {
    private let body: @Sendable (String, Int64) -> Bool

    public init(_ body: @escaping @Sendable (String, Int64) -> Bool) {
        self.body = body
    }

    public func shouldSave(_ text: String, durationMillis: Int64) -> Bool {
        body(text, durationMillis)
    }
}

/**
 * 雑音だけの区間から出た短い文字を除く。
 *
 * 仮の規則で、値は DEMAND の環境音 5 分 × 12 種類と、Mac の読み上げの短い語で決めた。実際の会話では測っていない。
 * - 1 文字は除く。雑音でよく出る。「え」のような本物の相づちも落ちる
 * - 2 文字は、区間が長いときだけ除く。雑音の区間は長いのに文字が少なく、「そう」のような本物の短い発話は区間が短い
 *
 * 文字は Kotlin と同じく UTF-16 の 1 単位ずつ `isLetterOrDigit` で数える。サロゲートの対の文字は数えない。
 */
public struct NoiseTextFilter: TranscriptionFilter {
    private let longDurationMillis: Int64

    public init(longDurationMillis: Int64 = DEFAULT_LONG_DURATION_MILLIS) {
        self.longDurationMillis = longDurationMillis
    }

    public func shouldSave(_ text: String, durationMillis: Int64) -> Bool {
        let length = text.kotlinCountLetterOrDigit()
        switch length {
        case ...1: return false
        case 2: return durationMillis < longDurationMillis
        default: return true
        }
    }

    @usableFromInline static let DEFAULT_LONG_DURATION_MILLIS: Int64 = 2_000
}
