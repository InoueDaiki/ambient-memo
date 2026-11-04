import Foundation

// 区間の内側を窓で判定し、話者の続く範囲ごとに本文を分ける。docs/検証-話者区間.md の推薦と同じ手順にする。
// この手順の値はすべて SpeakerConfig にある。時刻は明記がなければ区間の先頭からのミリ秒。

/// 発話区間の話者の範囲と、その本文。時刻は発話と同じ UNIX エポックからのミリ秒。[speaker] が nil なら不明。
public struct SpeakerTurn: Sendable {
    public var startedAtMillis: Int64
    public var endedAtMillis: Int64
    public var text: String
    public var speaker: SpeakerAssignment?
    /// 手で直したときに判定へ反映する声。保存しない。
    public var voice: TurnVoice?

    public init(_ startedAtMillis: Int64, _ endedAtMillis: Int64, _ text: String, speaker: SpeakerAssignment?, voice: TurnVoice? = nil) {
        self.startedAtMillis = startedAtMillis
        self.endedAtMillis = endedAtMillis
        self.text = text
        self.speaker = speaker
        self.voice = voice
    }
}

/// 行の声の特徴量。[RecentVoices] がメモリに持つだけで、DB には書かない。
public struct TurnVoice: Sendable {
    public let vector: [Float]
    public let voicedMillis: Int64

    public init(_ vector: [Float], _ voicedMillis: Int64) {
        self.vector = vector
        self.voicedMillis = voicedMillis
    }
}

struct SpeakerWindow: Sendable, Hashable {
    let startMillis: Int64
    let endMillis: Int64

    init(_ startMillis: Int64, _ endMillis: Int64) {
        self.startMillis = startMillis
        self.endMillis = endMillis
    }
}

/// 窓の特徴量と、自分とのコサイン類似度。
struct ScoredWindow: Sendable {
    let window: SpeakerWindow
    let vector: [Float]
    let score: Float

    init(_ window: SpeakerWindow, _ vector: [Float], _ score: Float) {
        self.window = window
        self.vector = vector
        self.score = score
    }
}

enum Voice: String, Sendable, Hashable {
    case SELF, OTHER, UNKNOWN
}

/// ヒステリシスで同じ判定が続いたまとまり。[score] は自分との類似度の平均。
struct VoiceRun: Sendable {
    let startMillis: Int64
    let endMillis: Int64
    let voice: Voice
    let vector: [Float]?
    let score: Float

    init(_ startMillis: Int64, _ endMillis: Int64, _ voice: Voice, _ vector: [Float]?, _ score: Float) {
        self.startMillis = startMillis
        self.endMillis = endMillis
        self.voice = voice
        self.vector = vector
        self.score = score
    }

    var millis: Int64 { endMillis - startMillis }
}

/// 本文の割り当てが済んだまとまり。
struct VoicePart: Sendable {
    let run: VoiceRun
    let startMillis: Int64
    let endMillis: Int64
    let text: String

    init(_ run: VoiceRun, _ startMillis: Int64, _ endMillis: Int64, _ text: String) {
        self.run = run
        self.startMillis = startMillis
        self.endMillis = endMillis
        self.text = text
    }
}

/**
 * 判定する窓を返す。最後の窓は区間の終わりに揃える。
 *
 * 区間の後ろの区切りの無音は判定に使わない。検証と同じく、発話の部分だけを見る。
 * 無音を除くと特徴量を作れない長さになるときは、以前と同じく区間全体を使う。
 */
func speakerWindows(_ durationMillis: Int64, _ config: SpeakerConfig) -> [SpeakerWindow] {
    let voicedMillis = durationMillis - config.trailingSilenceMillis
    let endMillis = voicedMillis >= config.minWindowMillis ? voicedMillis : durationMillis
    if endMillis <= config.windowMillis { return [SpeakerWindow(0, endMillis)] }

    let windows = stride(from: Int64(0), through: endMillis - config.windowMillis, by: Int(config.hopMillis))
        .map { SpeakerWindow($0, $0 + config.windowMillis) }
    let isAligned = windows.last!.endMillis == endMillis
    return isAligned ? windows : windows + [SpeakerWindow(endMillis - config.windowMillis, endMillis)]
}

/**
 * ヒステリシスで窓を自分かそれ以外に分け、続いた窓を1つのまとまりにする。境界は窓の中央の中点。
 * まとまりの長さで信頼できるかを決めるので、最後のまとまりは窓の終わりまでにして、区切りの無音を含めない。
 *
 * Kotlin と同じく、[windows] が空なら [NoSuchElementException] を投げる。
 */
func voiceRuns(_ windows: [ScoredWindow], _ config: SpeakerConfig) throws -> [VoiceRun] {
    var isSelf = false
    let states = windows.map { window in
        isSelf = window.score >= (isSelf ? config.selfExitThreshold : config.selfThreshold)
        return isSelf
    }
    let centers = windows.map { ($0.window.startMillis + $0.window.endMillis) / 2 }
    guard let last = windows.last else { throw NoSuchElementException("List is empty.") }
    var edges: [Int64] = [0]
    for index in centers.indices.dropLast() { edges.append((centers[index] + centers[index + 1]) / 2) }
    edges.append(last.window.endMillis)

    var runs: [VoiceRun] = []
    var from = 0
    for index in windows.indices where index == windows.count - 1 || states[index + 1] != states[index] {
        let members = Array(windows[from...index])
        runs.append(try voiceRun(edges[from], edges[index + 1], states[index], members, config))
        from = index + 1
    }
    return runs
}

private func voiceRun(
    _ startMillis: Int64,
    _ endMillis: Int64,
    _ isSelf: Bool,
    _ windows: [ScoredWindow],
    _ config: SpeakerConfig
) throws -> VoiceRun {
    // Kotlin の Iterable<Float>.average() は Double で前から足して数で割る
    var sum = 0.0
    for window in windows { sum += Double(window.score) }
    let score = Float(sum / Double(windows.count))
    let voice: Voice
    if score >= config.unknownFloor, score < config.selfThreshold {
        voice = .UNKNOWN
    } else if isSelf {
        voice = .SELF
    } else {
        voice = .OTHER
    }
    return VoiceRun(startMillis, endMillis, voice, try meanOf(windows.map(\.vector)), score)
}

/**
 * 知っている他人の声と比べて、自分の判定を確かめる。[rivalScore] は最も近い他人との類似度。
 *
 * しきい値を緩めると、別の人の声も帯の近くに入る。一方で、自分の声が端末の条件で下がった発話は、
 * 他人の重心にも近くならない。そこで、確信の持てない範囲だけ、自分と他人のどちらに近いかで決める。
 */
func settledVoice(_ run: VoiceRun, _ rivalScore: Float?, _ config: SpeakerConfig) -> Voice {
    guard let rivalScore, run.score < config.strongSelfThreshold else { return run.voice }
    let lead = run.score - rivalScore
    if run.voice == .UNKNOWN, lead >= config.rivalMargin { return .SELF }
    if run.voice == .UNKNOWN, lead <= -config.rivalMargin { return .OTHER }
    if run.voice == .SELF, lead <= -config.selfOverrideMargin { return .OTHER }
    return run.voice
}

/**
 * 分割に使うトークンを返す。時刻のないときと、つなげても本文にならないときは、本文を区間に均等に並べる。
 * これは文字数の比例配分で、時刻で分けるより粗い。検証では測っていない。
 */
func timedTokens(_ transcription: TimedTranscription, _ durationMillis: Int64) -> [TranscriptToken] {
    let tokens = transcription.tokens
    let isUsable = !tokens.isEmpty &&
        tokens.map(\.text).joined().kotlinWithoutWhitespace() == transcription.text.kotlinWithoutWhitespace()
    if isUsable { return tokens }

    let characters = characters(transcription.text)
    return characters.enumerated().map { index, text in
        TranscriptToken(text, Int64(index) * durationMillis / Int64(characters.count))
    }
}

/// サロゲートペアを割らずに 1 文字ずつ分ける。Kotlin のコードポイントと同じく、Unicode のスカラーで分ける。
private func characters(_ text: String) -> [String] {
    text.unicodeScalars.map { String($0) }
}

/**
 * トークンを開始時刻でまとまりへ分ける。多数決にしない。
 *
 * 句読点のような文字だけのトークンは、前のトークンと同じまとまりに入れて、行の先頭に残さない。
 * 文字のないまとまりは行にせず、時間は前の行に含める。無音や雑音で話者が増えないように、
 * まとまりの話者を決める前に落とす。行の時刻は隙間なく続き、区間の先頭から終わりまでを覆う。
 */
func assignText(_ runs: [VoiceRun], _ tokens: [TranscriptToken], _ durationMillis: Int64) -> [VoicePart] {
    var texts = Array(repeating: "", count: runs.count)
    var current = 0
    for (index, token) in tokens.enumerated() {
        if index == 0 || token.text.kotlinAnyLetterOrDigit() {
            let found = runs.firstIndex { token.offsetMillis < $0.endMillis }
            current = found ?? (runs.count - 1)
        }
        texts[current] += token.text
    }

    let kept = runs.indices.filter { !texts[$0].kotlinIsBlank }
    return kept.enumerated().map { position, index in
        let startMillis = position == 0 ? 0 : runs[index].startMillis
        let endMillis = position + 1 < kept.count ? runs[kept[position + 1]].startMillis : durationMillis
        return VoicePart(runs[index], startMillis, endMillis, texts[index].kotlinTrim())
    }
}

/// 隣り合う同じ話者の行を 1 行にする。必要な分だけ行を分け、1 行 1 話者を保つ。
func mergeAdjacent(_ turns: [SpeakerTurn]) throws -> [SpeakerTurn] {
    var merged: [SpeakerTurn] = []
    for turn in turns {
        if let last = merged.last, last.speaker?.speakerId == turn.speaker?.speakerId {
            merged[merged.count - 1] = try mergedWith(last, turn)
        } else {
            merged.append(turn)
        }
    }
    return merged
}

private func mergedWith(_ turn: SpeakerTurn, _ next: SpeakerTurn) throws -> SpeakerTurn {
    let confidences = [turn.speaker?.confidence, next.speaker?.confidence].compactMap { $0 }
    let confidence = confidences.dropFirst().reduce(confidences.first) { result, value in result.map { kotlinMin($0, value) } }
    var merged = turn
    merged.endedAtMillis = next.endedAtMillis
    merged.text = turn.text + next.text
    merged.speaker = try turn.speaker.map { try SpeakerAssignment($0.speakerId, confidence: confidence) }
    merged.voice = [turn.voice, next.voice].compactMap { $0 }.kotlinMaxBy(\.voicedMillis)
    return merged
}
