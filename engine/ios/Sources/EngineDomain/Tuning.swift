import Foundation

/// 利用者が画面で変えられる値。Kotlin の `TuningKey`。範囲、刻み、既定値はここだけで決め、画面にもここから渡す。
///
/// 既定値は [SpeakerConfig]、[OrganizeConfig]、[QUIET_GATE_DBFS]、アダプターの VAD の既定値と同じにする。
/// 揃っていることはテストで確かめる。[wireName] は保存の形と JS の境界で使う名前で、変えない。
public enum TuningKey: String, CaseIterable, Sendable {
    case SELF_THRESHOLD
    case SELF_EXIT_THRESHOLD
    case UNKNOWN_FLOOR
    case PROVISIONAL_THRESHOLD
    case MAX_PROVISIONAL_SPEAKERS
    case QUIET_GATE
    case VAD_THRESHOLD
    case VAD_MIN_SILENCE
    case MIN_TOPIC_LINES
    case CHUNK_GAP_MINUTES

    private var spec: (wireName: String, min: Double, max: Double, step: Double, defaultValue: Double) {
        switch self {
        case .SELF_THRESHOLD: return ("selfThreshold", 0.3, 0.8, 0.01, 0.5)
        case .SELF_EXIT_THRESHOLD: return ("selfExitThreshold", 0.2, 0.8, 0.01, 0.45)
        case .UNKNOWN_FLOOR: return ("unknownFloor", 0.1, 0.8, 0.01, 0.4)
        case .PROVISIONAL_THRESHOLD: return ("provisionalThreshold", 0.3, 0.8, 0.01, 0.45)
        case .MAX_PROVISIONAL_SPEAKERS: return ("maxProvisionalSpeakers", 1, 10, 1, 3)
        case .QUIET_GATE: return ("quietGateDbfs", -70, -30, 1, QUIET_GATE_DBFS)
        case .VAD_THRESHOLD: return ("vadThreshold", 0.2, 0.9, 0.05, 0.5)
        case .VAD_MIN_SILENCE: return ("vadMinSilenceMillis", 300, 2_000, 100, 800)
        case .MIN_TOPIC_LINES: return ("minTopicLines", 1, 10, 1, 4)
        case .CHUNK_GAP_MINUTES: return ("chunkGapMinutes", 1, 30, 1, 5)
        }
    }

    public var wireName: String { spec.wireName }
    public var min: Double { spec.min }
    public var max: Double { spec.max }
    public var step: Double { spec.step }
    public var defaultValue: Double { spec.defaultValue }

    /// 刻みが 1 以上の値は整数で表す。
    public var isInteger: Bool { step >= 1 }

    public static func fromWireName(_ name: String) -> TuningKey? {
        allCases.first { $0.wireName == name }
    }
}

/// 調整の値を受け付けない理由。Kotlin の `TuningProblem`。
public enum TuningProblem: String, Sendable {
    case UNKNOWN_KEY
    case OUT_OF_RANGE
    case SELF_EXIT_ABOVE_SELF
    case UNKNOWN_FLOOR_ABOVE_SELF_EXIT
}

public struct InvalidTuningException: Error, Equatable {
    public let problem: TuningProblem
    public let key: String?
}

/// 調整の値の組。Kotlin の `Tuning`。既定値と違う値だけを持つ。
public struct Tuning: Sendable, Equatable {
    private let overrides: [TuningKey: Double]

    private init(_ overrides: [TuningKey: Double]) {
        self.overrides = overrides
    }

    public static let DEFAULT = Tuning([:])

    public subscript(key: TuningKey) -> Double {
        overrides[key] ?? key.defaultValue
    }

    /// 既定値と違う値。名前の順。
    public var changed: [(key: TuningKey, value: Double)] {
        TuningKey.allCases.compactMap { key in overrides[key].map { (key, $0) } }
    }

    /// 話者の判定の値。[SpeakerConfig.default] のほかの値はそのまま使う。
    public func speakerConfig() -> SpeakerConfig {
        let base = SpeakerConfig.default
        let selfThreshold = Float(self[.SELF_THRESHOLD])
        let provisional = Float(self[.PROVISIONAL_THRESHOLD])
        // 範囲と値どうしの関係は作るときに確かめたので、ここでは失敗しない
        return try! SpeakerConfig(
            selfThreshold: selfThreshold,
            selfExitThreshold: Float(self[.SELF_EXIT_THRESHOLD]),
            unknownFloor: Float(self[.UNKNOWN_FLOOR]),
            provisionalThreshold: provisional,
            maxProvisionalSpeakers: Int(self[.MAX_PROVISIONAL_SPEAKERS]),
            consolidationThreshold: provisional,
            // 自分に入る値より低いと、強い自分の判定が自分の判定より緩くなる
            strongSelfThreshold: Swift.max(base.strongSelfThreshold, selfThreshold),
            trailingSilenceMillis: Int64(self[.VAD_MIN_SILENCE])
        )
    }

    /// 整理ジョブの値。[OrganizeConfig.default] のほかの値はそのまま使う。
    public func organizeConfig() -> OrganizeConfig {
        try! OrganizeConfig(
            minSilenceGapMillis: Int64(self[.CHUNK_GAP_MINUTES]) * Tuning.MILLIS_PER_MINUTE,
            minTopicLines: Int(self[.MIN_TOPIC_LINES])
        )
    }

    public var quietGateDbfs: Double { self[.QUIET_GATE] }
    public var vadThreshold: Float { Float(self[.VAD_THRESHOLD]) }
    public var vadMinSilenceSeconds: Float { Float(self[.VAD_MIN_SILENCE] / Double(MILLIS_PER_SECOND)) }

    /// 保存の形。1 行に `名前=値` を 1 つ。既定値の項目は書かない。
    public func encode() -> String {
        var text = Tuning.VERSION_LINE + "\n"
        for (key, value) in changed {
            text += key.wireName + "=" + Tuning.format(key, value) + "\n"
        }
        return text
    }

    private static let VERSION_LINE = "version=1"
    private static let MILLIS_PER_MINUTE: Int64 = 60_000
    private static let DECIMAL_SCALE = 1_000_000.0
    private static let TOLERANCE = 1e-9

    /// 名前と値から作る。書かない項目は既定値。受け付けなければ [InvalidTuningException]。
    public static func of(_ values: [String: Double]) throws -> Tuning {
        var keyed: [TuningKey: Double] = [:]
        // 例外の種類が Kotlin と揃うように、名前の順に確かめる
        for (name, value) in values.sorted(by: { $0.key < $1.key }) {
            guard let key = TuningKey.fromWireName(name) else {
                throw InvalidTuningException(problem: .UNKNOWN_KEY, key: name)
            }
            keyed[key] = try snap(key, value)
        }
        return try checked(keyed)
    }

    /// 保存の形から読む。例外を投げない。読めない行、知らない名前、範囲の外の値は飛ばす。
    /// 残った値どうしの関係が合わなければ、すべて既定値にする。片方だけ使うと、利用者が選んでいない組になるため。
    public static func decode(_ text: String) -> Tuning {
        var keyed: [TuningKey: Double] = [:]
        for line in text.split(omittingEmptySubsequences: false, whereSeparator: \.isNewline) {
            guard let separator = line.firstIndex(of: "=") else { continue }
            let name = line[..<separator].trimmingCharacters(in: .whitespaces)
            let rawValue = line[line.index(after: separator)...].trimmingCharacters(in: .whitespaces)
            guard let key = TuningKey.fromWireName(name), let value = Double(rawValue) else { continue }
            if let snapped = try? snap(key, value) { keyed[key] = snapped }
        }
        return (try? checked(keyed)) ?? DEFAULT
    }

    private static func snap(_ key: TuningKey, _ value: Double) throws -> Double {
        guard value.isFinite, value >= key.min - TOLERANCE, value <= key.max + TOLERANCE else {
            throw InvalidTuningException(problem: .OUT_OF_RANGE, key: key.wireName)
        }
        let steps = roundHalfUp((value - key.min) / key.step)
        // 刻みを足した誤差を、小数 6 桁で丸めて消す。Kotlin でも同じ値になる
        let snapped = roundHalfUp((key.min + steps * key.step) * DECIMAL_SCALE) / DECIMAL_SCALE
        return Swift.min(Swift.max(snapped, key.min), key.max)
    }

    /// Kotlin の `roundToLong` と同じ丸め。
    private static func roundHalfUp(_ value: Double) -> Double {
        (value + 0.5).rounded(.down)
    }

    private static func checked(_ values: [TuningKey: Double]) throws -> Tuning {
        func value(_ key: TuningKey) -> Double { values[key] ?? key.defaultValue }
        if value(.SELF_EXIT_THRESHOLD) > value(.SELF_THRESHOLD) + TOLERANCE {
            throw InvalidTuningException(problem: .SELF_EXIT_ABOVE_SELF, key: TuningKey.SELF_EXIT_THRESHOLD.wireName)
        }
        if value(.UNKNOWN_FLOOR) > value(.SELF_EXIT_THRESHOLD) + TOLERANCE {
            throw InvalidTuningException(problem: .UNKNOWN_FLOOR_ABOVE_SELF_EXIT, key: TuningKey.UNKNOWN_FLOOR.wireName)
        }
        return Tuning(values.filter { abs($0.value - $0.key.defaultValue) > TOLERANCE })
    }

    private static func format(_ key: TuningKey, _ value: Double) -> String {
        key.isInteger ? String(Int64(roundHalfUp(value))) : String(value)
    }
}
