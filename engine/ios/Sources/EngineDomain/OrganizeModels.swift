import Foundation

/// 整理ジョブの既定値。すべて仮で、長い会話の評価と実機で見直す。Swift の既定値は [default] にある。
public struct OrganizeConfig: Sendable, Hashable {
    /// 発話の間がこの長さ以上あいたら、別のまとまりにする。最後のまとまりは、この長さだけ静かになるまで処理しない。
    public let minSilenceGapMillis: Int64
    /// 1つのまとまりの文字起こしの上限。超えると無音がなくても分ける。LLM の文脈長に収めるため。
    public let maxChunkChars: Int
    /// LLM の出力が読めなかったときの再試行の回数。
    public let maxRetries: Int
    /// 同じまとまりの処理がこの回数失敗したら、そのまとまりを飛ばして先を試す。
    public let maxChunkAttempts: Int

    public init(
        minSilenceGapMillis: Int64 = DEFAULT_MIN_SILENCE_GAP_MILLIS,
        maxChunkChars: Int = DEFAULT_MAX_CHUNK_CHARS,
        maxRetries: Int = DEFAULT_MAX_RETRIES,
        maxChunkAttempts: Int = DEFAULT_MAX_CHUNK_ATTEMPTS
    ) throws {
        try require(minSilenceGapMillis > 0, "minSilenceGapMillis は正の値です")
        try require(maxChunkChars > 0, "maxChunkChars は正の値です")
        try require(maxRetries >= 0, "maxRetries は 0 以上です")
        try require(maxChunkAttempts > 0, "maxChunkAttempts は正の値です")
        self.minSilenceGapMillis = minSilenceGapMillis
        self.maxChunkChars = maxChunkChars
        self.maxRetries = maxRetries
        self.maxChunkAttempts = maxChunkAttempts
    }

    /// Kotlin の `OrganizeConfig()`。
    public static let `default` = try! OrganizeConfig()

    @usableFromInline static let DEFAULT_MIN_SILENCE_GAP_MILLIS: Int64 = 5 * 60 * 1_000

    // 検証の文脈長 4096 トークンから、指示、日付の表、出力の分を除いた余裕を見た値
    @usableFromInline static let DEFAULT_MAX_CHUNK_CHARS = 1_800
    @usableFromInline static let DEFAULT_MAX_RETRIES = 1
    @usableFromInline static let DEFAULT_MAX_CHUNK_ATTEMPTS = 3
}

public struct LlmRequest: Sendable, Hashable {
    public var system: String
    public var user: String
    public var maxOutputTokens: Int

    public init(_ system: String, _ user: String, _ maxOutputTokens: Int) {
        self.system = system
        self.user = user
        self.maxOutputTokens = maxOutputTokens
    }
}

/// 保存前のメモ。[title] と [summary] は要約に失敗すると nil になる。発話は残るので、後からたどれる。
public struct NewMemo: Sendable, Hashable {
    public let startedAtMillis: Int64
    public let endedAtMillis: Int64
    public let title: String?
    public let summary: String?
    public let modelVersion: String
    public let createdAtMillis: Int64
    public let utteranceIds: [Int64]

    public init(
        startedAtMillis: Int64,
        endedAtMillis: Int64,
        title: String?,
        summary: String?,
        modelVersion: String,
        createdAtMillis: Int64,
        utteranceIds: [Int64]
    ) throws {
        try require(endedAtMillis >= startedAtMillis, "終了時刻が開始時刻より前です")
        try require(!utteranceIds.isEmpty, "根拠の発話がありません")
        try require(Set(utteranceIds).count == utteranceIds.count, "発話が重複しています")
        self.startedAtMillis = startedAtMillis
        self.endedAtMillis = endedAtMillis
        self.title = title
        self.summary = summary
        self.modelVersion = modelVersion
        self.createdAtMillis = createdAtMillis
        self.utteranceIds = utteranceIds
    }
}

public struct Memo: Sendable, Hashable {
    public var id: Int64
    public var startedAtMillis: Int64
    public var endedAtMillis: Int64
    public var title: String?
    public var summary: String?
    public var modelVersion: String
    public var createdAtMillis: Int64

    public init(
        id: Int64,
        startedAtMillis: Int64,
        endedAtMillis: Int64,
        title: String?,
        summary: String?,
        modelVersion: String,
        createdAtMillis: Int64
    ) {
        self.id = id
        self.startedAtMillis = startedAtMillis
        self.endedAtMillis = endedAtMillis
        self.title = title
        self.summary = summary
        self.modelVersion = modelVersion
        self.createdAtMillis = createdAtMillis
    }
}

public enum OrganizeOutcome: String, Sendable, Hashable, CaseIterable {
    /// 今処理できるまとまりをすべて処理した。
    case COMPLETED

    /// 止めるように言われたので、まとまりの区切りで止めた。
    case STOPPED

    /// LLM が動かなかった。そのまとまりは未処理のまま残した。
    case LLM_FAILED

    public var name: String { rawValue }
}

/// 1回の実行の進み具合。
public struct OrganizeProgress: Sendable, Hashable {
    public let completedChunks: Int
    public let totalChunks: Int

    public init(_ completedChunks: Int, _ totalChunks: Int) {
        self.completedChunks = completedChunks
        self.totalChunks = totalChunks
    }
}

/// 今の未処理の量。[hasOngoing] は、話が続いているかもしれないので待っているまとまりがあること。
public struct OrganizeBacklog: Sendable, Hashable {
    public let readyChunks: Int
    public let hasOngoing: Bool

    public init(readyChunks: Int, hasOngoing: Bool) {
        self.readyChunks = readyChunks
        self.hasOngoing = hasOngoing
    }
}

/// [unreadableCount] は、LLM で読めずに要約のないメモとして記録したまとまりの数。[memoCount] に含む。
public struct OrganizeResult: Sendable, Hashable {
    public let outcome: OrganizeOutcome
    public let memoCount: Int
    public let unreadableCount: Int

    public init(_ outcome: OrganizeOutcome, _ memoCount: Int, _ unreadableCount: Int = 0) {
        self.outcome = outcome
        self.memoCount = memoCount
        self.unreadableCount = unreadableCount
    }
}
