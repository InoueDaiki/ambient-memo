import BackgroundTasks
import EngineDomain
import Foundation
import UIKit

/// 今の実行の段階。
public enum OrganizePhase: Sendable { case idle, preparing, organizing, stopping }

/// 手動の実行の終わり方。画面に出す。Android の `OrganizeEnding` と同じ。
public enum OrganizeEnding: Sendable { case completed, stopped, tooHot, modelMissing, failed, timeLimit }

/// [ending] は手動の実行の最後の結果。定期実行の結果は画面に出さない。
public struct OrganizeStatus: Sendable, Equatable {
    public var phase: OrganizePhase = .idle
    public var completedChunks = 0
    public var totalChunks = 0
    public var isManual = false
    public var ending: OrganizeEnding?

    public var isRunning: Bool { phase != .idle }
}

/// 1回の整理の結果。
enum OrganizeRunResult: Sendable { case completed, stopped, modelMissing, failed }

enum OrganizeStart: Sendable { case started, joined, busy }

/**
 * 整理を同時に1つだけ動かす。Android の `OrganizeRunner` と同じ決まりで、定期実行の途中で手動の依頼が来たら手動に切り替える。
 *
 * iOS には別のプロセスがないので、同じプロセスの優先度の低いタスクで動かす。LLM のクラッシュは聞き取りも巻き込むので、
 * 定期実行は聞き取りの止まっている間に限る(BGProcessingTask は端末を使っていない間に OS が動かす)。
 */
final class OrganizeRunner: @unchecked Sendable {
    private let lock = NSLock()
    private var task: Task<Void, Never>?
    private var trigger = OrganizeTrigger.SCHEDULED
    private var stopRequested = false
    private var stopReason: OrganizeStopReason?
    private var abandoned = false
    private var statusValue = OrganizeStatus()

    private let session: @Sendable (_ shouldContinue: @escaping @Sendable () -> Bool, _ onProgress: @escaping @Sendable (OrganizeProgress) -> Void) async -> OrganizeRunResult
    private let device: @Sendable () -> (isCharging: Bool, isTooHot: Bool)
    private let onStatus: @Sendable (OrganizeStatus) -> Void
    private let onFinished: @Sendable (OrganizeRunResult) -> Void

    init(
        session: @escaping @Sendable (@escaping @Sendable () -> Bool, @escaping @Sendable (OrganizeProgress) -> Void) async -> OrganizeRunResult,
        device: @escaping @Sendable () -> (isCharging: Bool, isTooHot: Bool),
        onStatus: @escaping @Sendable (OrganizeStatus) -> Void,
        onFinished: @escaping @Sendable (OrganizeRunResult) -> Void
    ) {
        self.session = session
        self.device = device
        self.onStatus = onStatus
        self.onFinished = onFinished
    }

    var status: OrganizeStatus { lock.withLock { statusValue } }

    func start(_ requested: OrganizeTrigger) -> OrganizeStart {
        let (result, next): (OrganizeStart, OrganizeStatus?) = lock.withLock {
            if statusValue.isRunning {
                if requested == .SCHEDULED { return (.busy, nil) }
                trigger = .MANUAL
                statusValue.isManual = true
                return (.joined, statusValue)
            }
            trigger = requested
            stopRequested = false
            stopReason = nil
            abandoned = false
            statusValue = OrganizeStatus(phase: .preparing, isManual: requested == .MANUAL)
            task = Task.detached(priority: .background) { [self] in
                let result = await session({ [self] in shouldContinue() }, { [self] in progress($0) })
                finish(result)
            }
            return (.started, statusValue)
        }
        if let next { onStatus(next) }
        return result
    }

    /// 利用者の依頼。まとまりの区切りで止める。
    func requestStop() {
        let next: OrganizeStatus? = lock.withLock {
            guard statusValue.isRunning else { return nil }
            stopRequested = true
            statusValue.phase = .stopping
            return statusValue
        }
        if let next { onStatus(next) }
    }

    /// OS に止められるので、区切りを待たずに捨てる。処理中のまとまりは次回やり直す。
    func abandon(_ ending: OrganizeEnding) {
        let next: OrganizeStatus? = lock.withLock {
            guard statusValue.isRunning else { return nil }
            abandoned = true
            task?.cancel()
            statusValue = OrganizeStatus(isManual: statusValue.isManual, ending: statusValue.isManual ? ending : nil)
            return statusValue
        }
        if let next { onStatus(next) }
    }

    private func shouldContinue() -> Bool {
        let state = device()
        return lock.withLock {
            stopReason = trigger.stopReason(
                OrganizeConditions(stopRequested: stopRequested, isCharging: state.isCharging, isTooHot: state.isTooHot)
            )
            return stopReason == nil
        }
    }

    private func progress(_ progress: OrganizeProgress) {
        let next: OrganizeStatus? = lock.withLock {
            guard !abandoned else { return nil }
            if statusValue.phase != .stopping { statusValue.phase = .organizing }
            statusValue.completedChunks = progress.completedChunks
            statusValue.totalChunks = progress.totalChunks
            return statusValue
        }
        if let next { onStatus(next) }
    }

    private func finish(_ result: OrganizeRunResult) {
        let next: OrganizeStatus? = lock.withLock {
            if abandoned { return nil }
            let isManual = trigger == .MANUAL
            statusValue = OrganizeStatus(isManual: isManual, ending: isManual ? endingOf(result) : nil)
            return statusValue
        }
        guard let next else { return }
        onStatus(next)
        onFinished(result)
    }

    private func endingOf(_ result: OrganizeRunResult) -> OrganizeEnding {
        switch result {
        case .completed: .completed
        case .modelMissing: .modelMissing
        case .failed: .failed
        case .stopped: stopReason == .TOO_HOT ? .tooHot : .stopped
        }
    }
}

/// 端末の充電と発熱。OrganizeJob の区切りで読むので、UIKit の値は通知で写しておく。
final class DeviceConditions: @unchecked Sendable {
    private let charging = Guarded(false)
    private let observers = Guarded<[NSObjectProtocol]>([])

    /// どのスレッドで作ってもよい。値はメインのスレッドで読む。
    init() {
        Task { @MainActor [weak self] in
            UIDevice.current.isBatteryMonitoringEnabled = true
            self?.update()
            let observer = NotificationCenter.default.addObserver(
                forName: UIDevice.batteryStateDidChangeNotification, object: nil, queue: .main
            ) { [weak self] _ in
                MainActor.assumeIsolated { self?.update() }
            }
            self?.observers.withLock { $0.append(observer) }
        }
    }

    @MainActor
    private func update() {
        let state = UIDevice.current.batteryState
        charging.withLock { $0 = state == .charging || state == .full }
    }

    var isCharging: Bool { charging.current }

    /// Android の THERMAL_STATUS_SEVERE 以上に当たる段階。
    var isTooHot: Bool {
        let state = ProcessInfo.processInfo.thermalState
        return state == .serious || state == .critical
    }
}

/**
 * 充電中の整理を BGProcessingTask に任せる。OS が実行の時刻を決めるので、Android の 6 時間ごとより間隔がばらつく。
 *
 * 登録はアプリの起動が終わる前に要るので、[register] は AppDelegate から呼ぶ。
 */
public enum OrganizeSchedule {
    /// 前の実行からこれだけ空ける。Android の定期実行の間隔と同じ。
    private static let INTERVAL_SECONDS: TimeInterval = 6 * 60 * 60

    /// app.config.ts の BGTaskSchedulerPermittedIdentifiers と揃える。
    static var identifier: String { "\(Bundle.main.bundleIdentifier ?? "dev.ambientmemo.app").organize" }

    /// 起動の途中で 1 回だけ呼ぶ。
    public static func register(_ handler: @escaping @Sendable (BGProcessingTask) -> Void) {
        _ = BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            guard let task = task as? BGProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            handler(task)
        }
    }

    /// 次の実行を頼む。同じ名前の依頼は置き換わる。
    static func ensureScheduled() {
        let request = BGProcessingTaskRequest(identifier: identifier)
        request.requiresExternalPower = true
        request.requiresNetworkConnectivity = false
        request.earliestBeginDate = Date(timeIntervalSinceNow: INTERVAL_SECONDS)
        // シミュレーターなどで受け付けられなくても、手動の整理はできる
        try? BGTaskScheduler.shared.submit(request)
    }
}
