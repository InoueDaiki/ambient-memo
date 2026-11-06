import Foundation

/**
 * 実機の確認のために残す出来事。利用者が書き出して端末の外へ持ち出す値なので、数値と列挙だけを持つ。
 *
 * 文字列の項目を足さない。本文や例外の文言が入る余地をなくすため。
 *
 * Kotlin の sealed interface を protocol で表した。Swift の protocol は閉じられないので、
 * このファイルにない型を [encodeDiagnostic] に渡すと落ちる。
 */
public protocol DiagnosticEvent: Sendable {}

public enum SegmentOutcome: String, Sendable, Hashable, CaseIterable {
    case SAVED

    /// 文字起こしの結果が空だった。
    case BLANK

    /// 雑音の文字として捨てた。
    case FILTERED
    case FAILED

    public var name: String { rawValue }
}

/// 発話区間を 1 つ処理した。VAD の発火 1 回にあたる。
public struct SegmentProcessed: DiagnosticEvent, Hashable {
    public let segmentMillis: Int64
    /// 区間の終わりから処理を始めるまでの待ち。
    public let waitMillis: Int64
    public let transcribeMillis: Int64
    /// 話者の判定と保存。話者のロックの待ちを含む。
    public let saveMillis: Int64
    public let textLength: Int
    public let turns: Int
    public let outcome: SegmentOutcome

    public init(
        _ segmentMillis: Int64,
        _ waitMillis: Int64,
        _ transcribeMillis: Int64,
        _ saveMillis: Int64,
        _ textLength: Int,
        _ turns: Int,
        _ outcome: SegmentOutcome
    ) {
        self.segmentMillis = segmentMillis
        self.waitMillis = waitMillis
        self.transcribeMillis = transcribeMillis
        self.saveMillis = saveMillis
        self.textLength = textLength
        self.turns = turns
        self.outcome = outcome
    }
}

public struct CaptureGapRecorded: DiagnosticEvent, Hashable {
    public let reason: CaptureGapReason
    public let gapMillis: Int64

    public init(_ reason: CaptureGapReason, _ gapMillis: Int64) {
        self.reason = reason
        self.gapMillis = gapMillis
    }
}

public struct CaptureChanged: DiagnosticEvent, Hashable {
    public let isListening: Bool
    public let isInterrupted: Bool

    public init(isListening: Bool, isInterrupted: Bool) {
        self.isListening = isListening
        self.isInterrupted = isInterrupted
    }
}

public enum LlmBackend: String, Sendable, Hashable, CaseIterable {
    case GPU, CPU

    public var name: String { rawValue }
}

public struct LlmBackendChosen: DiagnosticEvent, Hashable {
    public let backend: LlmBackend

    public init(_ backend: LlmBackend) {
        self.backend = backend
    }
}

public struct LlmLoaded: DiagnosticEvent, Hashable {
    public let loadMillis: Int64
    public let isLoaded: Bool

    public init(loadMillis: Int64, isLoaded: Bool) {
        self.loadMillis = loadMillis
        self.isLoaded = isLoaded
    }
}

/// [maxOutputTokens] で依頼の種類を見分ける。
public struct LlmGenerated: DiagnosticEvent, Hashable {
    public let inputChars: Int
    public let maxOutputTokens: Int
    public let outputChars: Int
    public let generateMillis: Int64
    public let isFailed: Bool

    public init(inputChars: Int, maxOutputTokens: Int, outputChars: Int, generateMillis: Int64, isFailed: Bool) {
        self.inputChars = inputChars
        self.maxOutputTokens = maxOutputTokens
        self.outputChars = outputChars
        self.generateMillis = generateMillis
        self.isFailed = isFailed
    }
}

public struct OrganizeFinished: DiagnosticEvent, Hashable {
    public let outcome: OrganizeOutcome
    public let memoCount: Int
    public let unreadableCount: Int
    public let totalMillis: Int64

    public init(outcome: OrganizeOutcome, memoCount: Int, unreadableCount: Int, totalMillis: Int64) {
        self.outcome = outcome
        self.memoCount = memoCount
        self.unreadableCount = unreadableCount
        self.totalMillis = totalMillis
    }
}

/// 質問に 1 回答えた。[searchRounds] は検索の回数、[sourceCount] は答えの根拠にしたメモの数。
public struct AskFinished: DiagnosticEvent, Hashable {
    public let outcome: AnswerOutcome
    public let searchRounds: Int
    public let sourceCount: Int
    public let totalMillis: Int64

    public init(outcome: AnswerOutcome, searchRounds: Int, sourceCount: Int, totalMillis: Int64) {
        self.outcome = outcome
        self.searchRounds = searchRounds
        self.sourceCount = sourceCount
        self.totalMillis = totalMillis
    }
}

/// 端末とプロセスの状態。得られない値は -1。
public struct DeviceSample: DiagnosticEvent, Hashable {
    public let batteryPercent: Int
    public let chargeMicroAmpereHours: Int64
    public let temperatureDeciCelsius: Int
    public let isCharging: Bool
    public let thermalStatus: Int
    public let isScreenOn: Bool
    public let isDeviceIdle: Bool
    public let isPowerSave: Bool
    /// 起動からの時間。眠っている間も進む。
    public let elapsedMillis: Int64
    /// 起動からの時間。眠っている間は止まるので、[elapsedMillis] との差で眠った時間が分かる。
    public let uptimeMillis: Int64
    public let processCpuMillis: Int64
    public let rssKilobytes: Int64
    public let peakRssKilobytes: Int64

    public init(
        batteryPercent: Int,
        chargeMicroAmpereHours: Int64,
        temperatureDeciCelsius: Int,
        isCharging: Bool,
        thermalStatus: Int,
        isScreenOn: Bool,
        isDeviceIdle: Bool,
        isPowerSave: Bool,
        elapsedMillis: Int64,
        uptimeMillis: Int64,
        processCpuMillis: Int64,
        rssKilobytes: Int64,
        peakRssKilobytes: Int64
    ) {
        self.batteryPercent = batteryPercent
        self.chargeMicroAmpereHours = chargeMicroAmpereHours
        self.temperatureDeciCelsius = temperatureDeciCelsius
        self.isCharging = isCharging
        self.thermalStatus = thermalStatus
        self.isScreenOn = isScreenOn
        self.isDeviceIdle = isDeviceIdle
        self.isPowerSave = isPowerSave
        self.elapsedMillis = elapsedMillis
        self.uptimeMillis = uptimeMillis
        self.processCpuMillis = processCpuMillis
        self.rssKilobytes = rssKilobytes
        self.peakRssKilobytes = peakRssKilobytes
    }
}

/// 記録の受け口。実装は記録が止まっていれば何もしない。失敗しても聞き取りや整理を止めない。
public protocol DiagnosticsLog: Sendable {
    func record(_ event: any DiagnosticEvent)
}

/// Kotlin の `fun interface` の SAM 変換にあたる。
public struct ClosureDiagnosticsLog: DiagnosticsLog {
    private let body: @Sendable (any DiagnosticEvent) -> Void

    public init(_ body: @escaping @Sendable (any DiagnosticEvent) -> Void) {
        self.body = body
    }

    public func record(_ event: any DiagnosticEvent) {
        body(event)
    }
}

/// Kotlin の `DiagnosticsLog.NONE`。Swift の protocol は値を持てないので、別の名前空間に置く。
public enum DiagnosticsLogs {
    public static let NONE: any DiagnosticsLog = ClosureDiagnosticsLog { _ in }
}

/// 欠落を保存するときに記録する。
public final class RecordingCaptureGaps: CaptureGapRepository {
    private let delegate: any CaptureGapRepository
    private let diagnostics: any DiagnosticsLog

    public init(_ delegate: any CaptureGapRepository, _ diagnostics: any DiagnosticsLog) {
        self.delegate = delegate
        self.diagnostics = diagnostics
    }

    public func insert(_ gap: CaptureGap) async throws {
        try await delegate.insert(gap)
        diagnostics.record(CaptureGapRecorded(gap.reason, gap.endedAtMillis - gap.startedAtMillis))
    }

    public func listOverlapping(fromMillis: Int64, toMillis: Int64) async throws -> [CaptureGap] {
        try await delegate.listOverlapping(fromMillis: fromMillis, toMillis: toMillis)
    }
}

/// 生成の時間を記録する。入出力は長さだけを見る。長さは Kotlin と同じく UTF-16 の単位で数える。
public final class TimedLlm: Llm {
    private let delegate: any Llm
    private let diagnostics: any DiagnosticsLog

    public init(_ delegate: any Llm, _ diagnostics: any DiagnosticsLog) {
        self.delegate = delegate
        self.diagnostics = diagnostics
    }

    public var modelVersion: String { delegate.modelVersion }

    public func generate(_ request: LlmRequest) async throws -> String {
        let started = MonotonicMark()
        var outputChars: Int?
        defer {
            diagnostics.record(
                LlmGenerated(
                    inputChars: request.system.kotlinLength + request.user.kotlinLength,
                    maxOutputTokens: request.maxOutputTokens,
                    outputChars: outputChars ?? 0,
                    generateMillis: started.elapsedMillis(),
                    isFailed: outputChars == nil
                )
            )
        }
        let output = try await delegate.generate(request)
        outputChars = output.kotlinLength
        return output
    }
}

/**
 * Kotlin の `TimeSource.Monotonic.markNow()`。経過時間をミリ秒の切り捨てで返す。
 *
 * JVM の System.nanoTime と同じく、端末が眠っている間は進まない SuspendingClock を使う。
 */
struct MonotonicMark: Sendable {
    private let start = SuspendingClock.now

    func elapsedMillis() -> Int64 {
        let duration = start.duration(to: SuspendingClock.now)
        let (seconds, attoseconds) = duration.components
        return seconds * 1_000 + attoseconds / 1_000_000_000_000_000
    }
}

/// 1 行の JSON にする。項目の名前と値はこのファイルの型からしか来ないので、エスケープは要らない。
public func encodeDiagnostic(_ atMillis: Int64, _ process: DiagnosticProcess, _ event: any DiagnosticEvent) -> String {
    let fields: [(String, Any)] = [("at", atMillis), ("process", process.name), ("event", diagnosticName(event))] +
        diagnosticFields(event)
    return encodeFields(fields)
}

public enum DiagnosticProcess: String, Sendable, Hashable, CaseIterable {
    case MAIN, ORGANIZE

    public var name: String { rawValue }
}

/**
 * 値は数値、真偽、列挙、定数の文字列だけを渡す。
 *
 * 数値は整数だけを Kotlin と同じ形で書く。Float と Double は Swift の書き方になり、Kotlin の `1.0E10` のような形と違う。
 */
public func encodeFields(_ fields: [(String, Any)]) -> String {
    "{" + fields.map { name, value in
        let encoded: String
        switch value {
        case let value as Bool: encoded = value ? "true" : "false"
        case let value as any BinaryInteger: encoded = "\(value)"
        case let value as Double: encoded = "\(value)"
        case let value as Float: encoded = "\(value)"
        default: encoded = "\"\(value)\""
        }
        return "\"\(name)\":\(encoded)"
    }.joined(separator: ",") + "}"
}

private func diagnosticName(_ event: any DiagnosticEvent) -> String {
    switch event {
    case is SegmentProcessed: return "segment"
    case is CaptureGapRecorded: return "gap"
    case is CaptureChanged: return "capture"
    case is LlmBackendChosen: return "llm_backend"
    case is LlmLoaded: return "llm_load"
    case is LlmGenerated: return "llm_generate"
    case is OrganizeFinished: return "organize"
    case is AskFinished: return "ask"
    case is DeviceSample: return "device"
    case is AudioLevelSummary: return "audio_level"
    default: preconditionFailure("未知の出来事です: \(type(of: event))")
    }
}

private func diagnosticFields(_ event: any DiagnosticEvent) -> [(String, Any)] {
    switch event {
    case let event as SegmentProcessed:
        return [
            ("segmentMs", event.segmentMillis),
            ("waitMs", event.waitMillis),
            ("transcribeMs", event.transcribeMillis),
            ("saveMs", event.saveMillis),
            ("textLength", event.textLength),
            ("turns", event.turns),
            ("outcome", event.outcome.name),
        ]
    case let event as CaptureGapRecorded:
        return [("reason", event.reason.name), ("gapMs", event.gapMillis)]
    case let event as CaptureChanged:
        return [("listening", event.isListening), ("interrupted", event.isInterrupted)]
    case let event as LlmBackendChosen:
        return [("backend", event.backend.name)]
    case let event as LlmLoaded:
        return [("loadMs", event.loadMillis), ("loaded", event.isLoaded)]
    case let event as LlmGenerated:
        return [
            ("inputChars", event.inputChars),
            ("maxOutputTokens", event.maxOutputTokens),
            ("outputChars", event.outputChars),
            ("generateMs", event.generateMillis),
            ("failed", event.isFailed),
        ]
    case let event as OrganizeFinished:
        return [
            ("outcome", event.outcome.name),
            ("memos", event.memoCount),
            ("unreadable", event.unreadableCount),
            ("totalMs", event.totalMillis),
        ]
    case let event as AskFinished:
        return [
            ("outcome", event.outcome.name),
            ("searchRounds", event.searchRounds),
            ("sources", event.sourceCount),
            ("totalMs", event.totalMillis),
        ]
    case let event as DeviceSample:
        return [
            ("batteryPercent", event.batteryPercent),
            ("chargeMicroAh", event.chargeMicroAmpereHours),
            ("temperatureDeciC", event.temperatureDeciCelsius),
            ("charging", event.isCharging),
            ("thermalStatus", event.thermalStatus),
            ("screenOn", event.isScreenOn),
            ("deviceIdle", event.isDeviceIdle),
            ("powerSave", event.isPowerSave),
            ("elapsedMs", event.elapsedMillis),
            ("uptimeMs", event.uptimeMillis),
            ("processCpuMs", event.processCpuMillis),
            ("rssKb", event.rssKilobytes),
            ("peakRssKb", event.peakRssKilobytes),
        ]
    case let event as AudioLevelSummary:
        return [
            ("frames", event.frames),
            ("skippedFrames", event.skippedFrames),
            // 帯の下端の dBFS:フレームの数
            ("histogram", event.histogram.map { "\($0.band):\($0.count)" }.joined(separator: " ")),
        ]
    default:
        preconditionFailure("未知の出来事です: \(type(of: event))")
    }
}
