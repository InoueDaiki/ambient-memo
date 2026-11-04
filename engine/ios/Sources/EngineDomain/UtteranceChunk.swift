import Foundation

/// メモ1件になる発話のまとまり。発話は開始時刻の順に並ぶ。Kotlin と同じく、空のまとまりは作れない(落ちる)。
struct UtteranceChunk: Sendable {
    let utterances: [Utterance]
    let startedAtMillis: Int64
    let endedAtMillis: Int64
    let isBlank: Bool

    init(_ utterances: [Utterance]) {
        precondition(!utterances.isEmpty, "List is empty.")
        self.utterances = utterances
        startedAtMillis = utterances[0].startedAtMillis
        endedAtMillis = utterances.map(\.endedAtMillis).max()!
        isBlank = utterances.allSatisfy(\.text.kotlinIsBlank)
    }
}

private let MILLIS_PER_MINUTE: Int64 = 60_000
private let MINUTES_PER_HOUR: Int64 = 60
private let MINUTES_PER_DAY: Int64 = 24 * MINUTES_PER_HOUR

extension Utterance {
    /**
     * 発話を、検証の文字起こしと同じ `[HH:mm] 自分: 本文` の形にする。
     * 話者が分からない発話は、話者識別の前と同じ `[HH:mm] 本文` の形にする。
     *
     * 時差は Foundation の TimeZone から秒の単位で読む。Kotlin(java.util.TimeZone)とは tz データの版が違うことがある。
     */
    func toTranscriptLine(_ timeZone: TimeZone, _ labels: SpeakerLabels = .NONE) -> String {
        let date = Date(timeIntervalSince1970: Double(floorDiv(startedAtMillis, MILLIS_PER_SECOND)))
        let localMillis = startedAtMillis + Int64(timeZone.secondsFromGMT(for: date)) * MILLIS_PER_SECOND
        let minutes = floorMod(floorDiv(localMillis, MILLIS_PER_MINUTE), MINUTES_PER_DAY)
        let label = labels.of(self).map { "\($0): " } ?? ""
        return "[\(pad2(minutes / MINUTES_PER_HOUR)):\(pad2(minutes % MINUTES_PER_HOUR))] \(label)\(text.kotlinTrim())"
    }
}

private func pad2(_ value: Int64) -> String {
    value < 10 ? "0\(value)" : "\(value)"
}

extension UtteranceChunk {
    func toTranscript(_ timeZone: TimeZone, _ labels: SpeakerLabels = .NONE) -> String {
        utterances.filter { !$0.text.kotlinIsBlank }.map { $0.toTranscriptLine(timeZone, labels) }.joined(separator: "\n")
    }
}

/**
 * [sorted] を、無音の長さと文字数の上限でまとまりに分ける。[sorted] は開始時刻、id の順に並んでいること。
 *
 * 上限より長い発話が1つだけあっても、その発話だけのまとまりにする。切ると文が壊れる。
 * 文字数は Kotlin と同じく UTF-16 の単位で数える。
 */
func splitIntoChunks(
    _ sorted: [Utterance],
    _ config: OrganizeConfig,
    _ timeZone: TimeZone,
    _ labels: SpeakerLabels = .NONE
) -> [UtteranceChunk] {
    var chunks: [UtteranceChunk] = []
    var current: [Utterance] = []
    var currentChars = 0
    var currentEndedAt = Int64.min
    for utterance in sorted {
        let chars = utterance.toTranscriptLine(timeZone, labels).kotlinLength + 1
        // Kotlin の Long の引き算はあふれても回るので、同じく回して比べる
        let isSilent = utterance.startedAtMillis &- currentEndedAt >= config.minSilenceGapMillis
        if !current.isEmpty, isSilent || currentChars + chars > config.maxChunkChars {
            chunks.append(UtteranceChunk(current))
            current = []
            currentChars = 0
            currentEndedAt = Int64.min
        }
        current.append(utterance)
        currentChars += chars
        currentEndedAt = max(currentEndedAt, utterance.endedAtMillis)
    }
    if !current.isEmpty { chunks.append(UtteranceChunk(current)) }
    return chunks
}

extension Array where Element == UtteranceChunk {
    /// 会話が続いているかもしれない最後のまとまりを除く。途中で切ると、続きが別のメモに分かれる。
    func withoutOngoing(_ nowMillis: Int64, _ config: OrganizeConfig) -> [UtteranceChunk] {
        let isLastOngoing = last.map { nowMillis - $0.endedAtMillis < config.minSilenceGapMillis } ?? false
        return isLastOngoing ? Array(dropLast()) : self
    }
}
