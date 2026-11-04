import EngineDomain
import Foundation
import UIKit

private let SESSION_FILE = "session"
private let MAX_RECORDING_MILLIS: Int64 = 7 * 24 * 60 * 60 * 1_000
private let MAX_FILE_BYTES: Int64 = 8 * 1_024 * 1_024
private let UNAVAILABLE = -1

/// [isFull] は大きさの上限に達して、新しい出来事を残していない。
public struct DiagnosticsStatus: Sendable, Hashable {
    public let isRecording: Bool
    public let startedAtMillis: Int64?
    public let endsAtMillis: Int64?
    public let sizeBytes: Int64
    public let isFull: Bool
}

/**
 * 動作の記録をファイルに残す。形式は Android の `DiagnosticsJournal` と同じ(1 行の JSON、`session` のファイル、プロセスごとのファイル)。
 *
 * iOS は整理も同じプロセスで動かすが、書くファイルは Android と同じく整理(`organize.jsonl`)と分ける。
 * 書けなくても聞き取りと整理は止めない。
 */
public final class DiagnosticsJournal: DiagnosticsLog, @unchecked Sendable {
    private let directory: URL
    private let clock: any Clock
    private let lock = NSLock()

    public init(directory: URL, clock: any Clock = SystemClock()) {
        self.directory = directory
        self.clock = clock
    }

    private struct Session {
        let startedAtMillis: Int64
        let endsAtMillis: Int64
    }

    /// [process] の名前のファイルに書く記録の受け口。
    public func log(_ process: DiagnosticProcess) -> any DiagnosticsLog {
        ClosureDiagnosticsLog { [self] event in record(event, process) }
    }

    public func status() -> DiagnosticsStatus {
        lock.withLock {
            let session = session()
            let sizes = eventFiles().map { max(fileSize($0.path), 0) }
            return DiagnosticsStatus(
                isRecording: isRunning(session),
                startedAtMillis: session?.startedAtMillis,
                endsAtMillis: session?.endsAtMillis,
                sizeBytes: sizes.reduce(0, +),
                isFull: sizes.contains { $0 >= MAX_FILE_BYTES }
            )
        }
    }

    /// 前の記録を消して始める。
    public func start() throws {
        try lock.withLock {
            try? FileManager.default.removeItem(at: directory)
            let now = clock.nowMillis()
            try write(now, now + MAX_RECORDING_MILLIS)
        }
    }

    public func stop() throws {
        try lock.withLock {
            if let session = session(), isRunning(session) { try write(session.startedAtMillis, clock.nowMillis()) }
        }
    }

    public func clear() {
        lock.withLock { _ = try? FileManager.default.removeItem(at: directory) }
    }

    public func record(_ event: any DiagnosticEvent) {
        record(event, .MAIN)
    }

    private func record(_ event: any DiagnosticEvent, _ process: DiagnosticProcess) {
        lock.withLock {
            let file = directory.appendingPathComponent("\(process.name.lowercased()).jsonl")
            guard isRunning(session()), fileSize(file.path) < MAX_FILE_BYTES else { return }
            let line = encodeDiagnostic(clock.nowMillis(), process, event) + "\n"
            // 空き容量がないときなどは、その分の記録を諦める
            if !FileManager.default.fileExists(atPath: file.path) {
                FileManager.default.createFile(atPath: file.path, contents: nil)
            }
            guard let handle = try? FileHandle(forWritingTo: file) else { return }
            defer { try? handle.close() }
            _ = try? handle.seekToEnd()
            try? handle.write(contentsOf: Data(line.utf8))
        }
    }

    /// [outputDirectory] の前の書き出しを消して、1 つのファイルにまとめる。記録がなければ nil を返す。
    public func export(to outputDirectory: URL, header: String, footer: String) throws -> URL? {
        try lock.withLock {
            guard let session = session() else { return nil }
            try? FileManager.default.removeItem(at: outputDirectory)
            try FileManager.default.createDirectory(at: outputDirectory, withIntermediateDirectories: true)
            let file = outputDirectory.appendingPathComponent("ambient-memo-diagnostics-\(session.startedAtMillis).jsonl")
            var text = header + "\n"
            for events in eventFiles() {
                let content = (try? String(contentsOf: events, encoding: .utf8)) ?? ""
                for line in content.split(separator: "\n", omittingEmptySubsequences: true) { text += line + "\n" }
            }
            text += footer + "\n"
            try Data(text.utf8).write(to: file)
            return file
        }
    }

    private func isRunning(_ session: Session?) -> Bool {
        guard let session else { return false }
        return clock.nowMillis() < session.endsAtMillis
    }

    private func eventFiles() -> [URL] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        return names.filter { $0.hasSuffix(".jsonl") }.sorted().map { directory.appendingPathComponent($0) }
    }

    private func write(_ startedAtMillis: Int64, _ endsAtMillis: Int64) throws {
        try StorageDirectories.prepare(directory)
        try "\(startedAtMillis) \(endsAtMillis)".write(
            to: directory.appendingPathComponent(SESSION_FILE),
            atomically: true,
            encoding: .utf8
        )
    }

    private func session() -> Session? {
        guard let text = try? String(contentsOf: directory.appendingPathComponent(SESSION_FILE), encoding: .utf8) else {
            return nil
        }
        let values = text.split(separator: " ").compactMap { Int64($0) }
        guard values.count == 2 else { return nil }
        return Session(startedAtMillis: values[0], endsAtMillis: values[1])
    }
}

/**
 * 端末とこのプロセスの今の状態を読む。Android の `deviceSample` と同じ項目にする。
 *
 * iOS で得られない値は -1 にする: 充電量(μAh)、温度。発熱の段階は `ProcessInfo.thermalState` の 0〜3。
 * 画面は「アプリが前面にあるか」、Doze は常に false、省電力は低電力モード。
 */
@MainActor
func deviceSample() -> DeviceSample {
    let device = UIDevice.current
    device.isBatteryMonitoringEnabled = true
    let level = device.batteryLevel
    let state = device.batteryState
    let process = ProcessInfo.processInfo
    let memory = processMemory()
    return DeviceSample(
        batteryPercent: level < 0 ? UNAVAILABLE : Int((level * 100).rounded()),
        chargeMicroAmpereHours: Int64(UNAVAILABLE),
        temperatureDeciCelsius: UNAVAILABLE,
        isCharging: state == .charging || state == .full,
        thermalStatus: process.thermalState.rawValue,
        isScreenOn: UIApplication.shared.applicationState == .active,
        isDeviceIdle: false,
        isPowerSave: process.isLowPowerModeEnabled,
        elapsedMillis: Int64(clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW) / 1_000_000),
        uptimeMillis: Int64(clock_gettime_nsec_np(CLOCK_UPTIME_RAW) / 1_000_000),
        processCpuMillis: processCpuMillis(),
        rssKilobytes: memory.resident,
        peakRssKilobytes: memory.peak
    )
}

private func processCpuMillis() -> Int64 {
    var usage = rusage()
    guard getrusage(RUSAGE_SELF, &usage) == 0 else { return Int64(UNAVAILABLE) }
    let user = Int64(usage.ru_utime.tv_sec) * 1_000 + Int64(usage.ru_utime.tv_usec) / 1_000
    let system = Int64(usage.ru_stime.tv_sec) * 1_000 + Int64(usage.ru_stime.tv_usec) / 1_000
    return user + system
}

/// 物理メモリの使用量とピーク(kB)。
private func processMemory() -> (resident: Int64, peak: Int64) {
    var info = task_vm_info_data_t()
    var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)
    let result = withUnsafeMutablePointer(to: &info) {
        $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
            task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
        }
    }
    guard result == KERN_SUCCESS else { return (Int64(UNAVAILABLE), Int64(UNAVAILABLE)) }
    return (Int64(info.phys_footprint / 1_024), Int64(info.ledger_phys_footprint_peak / 1_024))
}

/// 書き出しの 1 行目。読むときに要る版と端末の種類だけを持つ。項目は Android と同じで、`sdk` は iOS の版。
@MainActor
func diagnosticsHeader() -> String {
    let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
    var system = utsname()
    uname(&system)
    let model = withUnsafeBytes(of: &system.machine) { String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self) }
    return encodeFields([
        ("event", "header"),
        ("format", 1),
        ("engineApi", currentEngineStatus().apiVersion),
        ("appVersion", version),
        ("model", model),
        ("sdk", UIDevice.current.systemVersion),
        ("utcOffsetMinutes", TimeZone.current.secondsFromGMT() / 60),
        ("asr", ModelCatalog.ACTIVE.asr.modelPackage.id),
        ("speaker", ModelCatalog.ACTIVE_SPEAKER.version),
        ("llm", ModelCatalog.ACTIVE_LLM.version),
    ])
}

/// 書き出しの最後の行。話者の判定の集計だけで、話者の名前や特徴量は含まない。Android と同じ形。
func encodeSpeakerSummary(_ diagnostics: SpeakerDiagnostics) -> String {
    let histogram = diagnostics.histogram.map { "\($0.floor):\($0.selfRuns)/\($0.otherRuns)/\($0.unknownRuns)" }
        .joined(separator: " ")
    return encodeFields([
        ("event", "speaker_summary"),
        ("days", diagnostics.days),
        ("selfRuns", diagnostics.selfRuns),
        ("otherRuns", diagnostics.otherRuns),
        ("unknownRuns", diagnostics.unknownRuns),
        ("selfMs", diagnostics.selfMillis),
        ("otherMs", diagnostics.otherMillis),
        ("unknownMs", diagnostics.unknownMillis),
        ("provisionalSpeakers", diagnostics.provisionalSpeakers),
        ("adaptedVoice", diagnostics.hasAdaptedVoice),
        ("selfThreshold", diagnostics.config.selfThreshold),
        // 帯の下端:自分/他人/不明
        ("histogram", histogram),
    ])
}
