import Foundation

/// 話者の判定の結果。集計だけに使う。
public enum SpeakerDecision: String, Sendable, Hashable, CaseIterable {
    case SELF, OTHER, UNKNOWN

    public var name: String { rawValue }
}

/// 判定 1 回の値。[selfScore] は自分との類似度の平均。
public struct SpeakerDecisionSample: Sendable {
    public let decision: SpeakerDecision
    public let selfScore: Float
    public let voicedMillis: Int64

    public init(_ decision: SpeakerDecision, _ selfScore: Float, _ voicedMillis: Int64) {
        self.decision = decision
        self.selfScore = selfScore
        self.voicedMillis = voicedMillis
    }
}

/// 判定と類似度の帯ごとの回数。
public struct SpeakerDecisionBucket: Sendable, Hashable {
    public let decision: SpeakerDecision
    public let bucket: Int
    public let runs: Int64
    public let voicedMillis: Int64

    public init(_ decision: SpeakerDecision, _ bucket: Int, _ runs: Int64, _ voicedMillis: Int64) {
        self.decision = decision
        self.bucket = bucket
        self.runs = runs
        self.voicedMillis = voicedMillis
    }
}

/// 類似度を帯の番号にする。0.05 刻み。負の類似度は 0 の帯に入れる。
public func scoreBucket(_ score: Float) -> Int {
    // Kotlin と同じく Float のまま掛けて切り捨てる。NaN は 0 の帯になる
    let scaled = (coerceIn(score, 0, 1) * Float(SCORE_BUCKETS)).rounded(.down)
    return min(kotlinToInt(scaled), SCORE_BUCKETS - 1)
}

/// 帯の下端の類似度。
public func bucketFloor(_ bucket: Int) -> Float {
    Float(bucket) / Float(SCORE_BUCKETS)
}

public let SCORE_BUCKETS = 20

/// 端末の中で話者の判定を確かめる値。音声も特徴量も含まない。
public struct SpeakerDiagnostics: Sendable, Hashable {
    public let days: Int
    public let selfRuns: Int64
    public let otherRuns: Int64
    public let unknownRuns: Int64
    public let selfMillis: Int64
    public let otherMillis: Int64
    public let unknownMillis: Int64
    /// 類似度の帯ごとの判定の数。帯の順に並ぶ。
    public let histogram: [SpeakerDiagnosticsBand]
    public let provisionalSpeakers: Int
    public let hasAdaptedVoice: Bool
    public let config: SpeakerConfig

    public init(
        days: Int,
        selfRuns: Int64,
        otherRuns: Int64,
        unknownRuns: Int64,
        selfMillis: Int64,
        otherMillis: Int64,
        unknownMillis: Int64,
        histogram: [SpeakerDiagnosticsBand],
        provisionalSpeakers: Int,
        hasAdaptedVoice: Bool,
        config: SpeakerConfig
    ) {
        self.days = days
        self.selfRuns = selfRuns
        self.otherRuns = otherRuns
        self.unknownRuns = unknownRuns
        self.selfMillis = selfMillis
        self.otherMillis = otherMillis
        self.unknownMillis = unknownMillis
        self.histogram = histogram
        self.provisionalSpeakers = provisionalSpeakers
        self.hasAdaptedVoice = hasAdaptedVoice
        self.config = config
    }
}

public struct SpeakerDiagnosticsBand: Sendable, Hashable {
    public let floor: Float
    public let selfRuns: Int64
    public let otherRuns: Int64
    public let unknownRuns: Int64

    public init(_ floor: Float, _ selfRuns: Int64, _ otherRuns: Int64, _ unknownRuns: Int64) {
        self.floor = floor
        self.selfRuns = selfRuns
        self.otherRuns = otherRuns
        self.unknownRuns = unknownRuns
    }
}

/// 集計を読みやすい形にする。
public final class SpeakerDiagnosticsReader: Sendable {
    private let log: any SpeakerDecisionLog
    private let speakers: any SpeakerRepository
    private let modelVersion: String
    private let clock: any Clock
    private let config: SpeakerConfig

    public init(
        log: any SpeakerDecisionLog,
        speakers: any SpeakerRepository,
        modelVersion: String,
        clock: any Clock,
        config: SpeakerConfig = .default
    ) {
        self.log = log
        self.speakers = speakers
        self.modelVersion = modelVersion
        self.clock = clock
        self.config = config
    }

    public func read(days: Int = SpeakerDiagnosticsReader.DEFAULT_DAYS) async throws -> SpeakerDiagnostics {
        let buckets = try await log.summarize(fromMillis: clock.nowMillis() - Int64(days) * Self.MILLIS_PER_DAY)
        let by = { (decision: SpeakerDecision) in buckets.filter { $0.decision == decision } }
        let embeddings = try await speakers.listEmbeddings(modelVersion: modelVersion, limit: config.maxComparedSpeakers)
        // Kotlin の toSortedMap と同じく、帯の番号の順に並べる
        let histogram = buckets.kotlinGroupBy(\.bucket)
            .sorted { $0.key < $1.key }
            .map { band($0.key, $0.values) }
        return SpeakerDiagnostics(
            days: days,
            selfRuns: by(.SELF).reduce(0) { $0 + $1.runs },
            otherRuns: by(.OTHER).reduce(0) { $0 + $1.runs },
            unknownRuns: by(.UNKNOWN).reduce(0) { $0 + $1.runs },
            selfMillis: by(.SELF).reduce(0) { $0 + $1.voicedMillis },
            otherMillis: by(.OTHER).reduce(0) { $0 + $1.voicedMillis },
            unknownMillis: by(.UNKNOWN).reduce(0) { $0 + $1.voicedMillis },
            histogram: histogram,
            provisionalSpeakers: embeddings.count { $0.nameSource == .PROVISIONAL },
            hasAdaptedVoice: try await speakers.findSelfAdaptation(modelVersion: modelVersion) != nil,
            config: config
        )
    }

    private func band(_ bucket: Int, _ rows: [SpeakerDecisionBucket]) -> SpeakerDiagnosticsBand {
        let runs = { (decision: SpeakerDecision) in rows.filter { $0.decision == decision }.reduce(0) { $0 + $1.runs } }
        return SpeakerDiagnosticsBand(bucketFloor(bucket), runs(.SELF), runs(.OTHER), runs(.UNKNOWN))
    }

    public static let DEFAULT_DAYS = 7
    public static let MILLIS_PER_DAY: Int64 = 86_400_000
}
