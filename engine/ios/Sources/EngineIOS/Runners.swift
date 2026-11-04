import EngineDomain
import Foundation

/// 値をロックで守る。domain の同じ役割の型は外から使えないので、EngineIOS にも置く。
final class Guarded<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Value

    init(_ value: Value) {
        self.value = value
    }

    func withLock<Result>(_ body: (inout Value) throws -> Result) rethrows -> Result {
        lock.lock()
        defer { lock.unlock() }
        return try body(&value)
    }

    var current: Value { withLock { $0 } }
}

/// 聞き取りの状態。Android の `CaptureState` と同じ。
public enum CaptureState: Sendable, Equatable {
    /// 止まっている。[interrupted] は、操作ではなく中断や読み取りの失敗で止まったときに true。
    case stopped(interrupted: Bool)
    case starting
    case listening
    case stopping
}

/**
 * 聞き取りを1本だけ動かし、状態を公開する。Android の `CaptureRunner` と同じ。
 *
 * 最初のフレームが届いて初めて listening にする。マイクを開けなかったときに、聞き取り中と見せないため。
 */
final class CaptureRunner: Sendable {
    private struct State {
        var capture: CaptureState = .stopped(interrupted: false)
        var task: Task<Void, Never>?
        var generation = 0
    }

    private let state = Guarded(State())
    private let onChange: @Sendable (CaptureState) -> Void
    private let onFailure: @Sendable (any Error) -> Void
    private let capture: @Sendable (any AudioSource) async throws -> Void
    private let audioSource: @Sendable () -> any AudioSource

    init(
        onChange: @escaping @Sendable (CaptureState) -> Void,
        onFailure: @escaping @Sendable (any Error) -> Void,
        capture: @escaping @Sendable (any AudioSource) async throws -> Void,
        audioSource: @escaping @Sendable () -> any AudioSource
    ) {
        self.onChange = onChange
        self.onFailure = onFailure
        self.capture = capture
        self.audioSource = audioSource
    }

    var current: CaptureState { state.withLock { $0.capture } }

    /// 動いていなければ始める。止まっていなければ false を返す。
    func start() -> Bool {
        let started: Bool = state.withLock { state in
            guard case .stopped = state.capture, state.task == nil else { return false }
            state.generation += 1
            let generation = state.generation
            state.capture = .starting
            state.task = Task.detached(priority: .userInitiated) { [self] in await run(generation) }
            return true
        }
        if started { onChange(.starting) }
        return started
    }

    /// 止まるまで待つ。未処理の音声を捨てて、欠落を記録し終えてから戻る。
    func stop() async {
        let running: Task<Void, Never>? = state.withLock { state in
            guard let task = state.task else { return nil }
            state.capture = .stopping
            return task
        }
        guard let running else { return }
        onChange(.stopping)
        running.cancel()
        await running.value
    }

    /// 聞き取りが始まるか止まるまで待つ。
    func settled(timeoutMillis: Int64) async -> CaptureState {
        let deadline = SystemClock().nowMillis() + timeoutMillis
        while SystemClock().nowMillis() < deadline {
            let value = current
            if value == .listening { return value }
            if case .stopped = value { return value }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        return current
    }

    private func run(_ generation: Int) async {
        // マイクの音声に終わりはないので、頼まれずに戻ったときも中断として扱う
        var interrupted = true
        do {
            try await capture(ObservedAudioSource(audioSource()) { [self] in markListening(generation) })
            if Task.isCancelled { interrupted = false }
        } catch {
            if Task.isCancelled {
                interrupted = false
            } else {
                onFailure(error)
            }
        }
        let final = CaptureState.stopped(interrupted: interrupted)
        state.withLock { state in
            state.capture = final
            state.task = nil
        }
        onChange(final)
    }

    private func markListening(_ generation: Int) {
        let changed: Bool = state.withLock { state in
            guard state.generation == generation, state.capture == .starting else { return false }
            state.capture = .listening
            return true
        }
        if changed { onChange(.listening) }
    }
}

/// 最初のフレームで知らせる。
private final class ObservedAudioSource: AudioSource {
    private let delegate: any AudioSource
    private let onFrame: @Sendable () -> Void

    init(_ delegate: any AudioSource, onFrame: @escaping @Sendable () -> Void) {
        self.delegate = delegate
        self.onFrame = onFrame
    }

    var sampleRateHz: Int { delegate.sampleRateHz }

    func frames() -> AsyncThrowingStream<AudioFrame, any Error> {
        let box = IteratorBox(delegate.frames().makeAsyncIterator())
        let onFrame = self.onFrame
        // 受け取る側が次を求めたときだけ読む。間にバッファを挟まない
        return AsyncThrowingStream(unfolding: {
            let frame = try await box.next()
            if frame != nil { onFrame() }
            return frame
        })
    }
}

/// 反復子は 1 つのタスクから順に使う。
private final class IteratorBox: @unchecked Sendable {
    private var iterator: AsyncThrowingStream<AudioFrame, any Error>.AsyncIterator

    init(_ iterator: AsyncThrowingStream<AudioFrame, any Error>.AsyncIterator) {
        self.iterator = iterator
    }

    func next() async throws -> AudioFrame? {
        try await iterator.next()
    }
}

/// 自分の声を登録できなかった理由。Android の `EnrollmentFailure` と同じ。
public enum EnrollmentFailure: String, Error, Sendable {
    case MICROPHONE_PERMISSION
    case MICROPHONE_UNAVAILABLE
    case SPEAKER_MODEL_UNAVAILABLE
    case LISTENING
    case NOT_ENOUGH_SPEECH
    case CANCELLED
}

/// 自分の声の登録を1本だけ動かし、止められるようにする。
final class EnrollmentRunner: Sendable {
    private struct State {
        var isRunning = false
        var task: Task<EnrollmentFailure?, Never>?
    }

    private let state = Guarded(State())
    private let precondition: @Sendable () async -> EnrollmentFailure?
    private let enroll: @Sendable () async throws -> EnrollmentOutcome

    init(
        precondition: @escaping @Sendable () async -> EnrollmentFailure?,
        enroll: @escaping @Sendable () async throws -> EnrollmentOutcome
    ) {
        self.precondition = precondition
        self.enroll = enroll
    }

    var isRunning: Bool { state.withLock { $0.isRunning } }

    func run() async throws {
        let task: Task<EnrollmentFailure?, Never>? = state.withLock { state in
            if state.isRunning { return nil }
            state.isRunning = true
            let task = Task.detached(priority: .userInitiated) { [precondition, enroll] () -> EnrollmentFailure? in
                if let failure = await precondition() { return failure }
                do {
                    let outcome = try await enroll()
                    return outcome == .ENROLLED ? nil : .NOT_ENOUGH_SPEECH
                } catch is AudioCaptureError {
                    return .MICROPHONE_UNAVAILABLE
                } catch {
                    // 取り消しと、ほかの失敗はどちらも何も記録していない
                    return .CANCELLED
                }
            }
            state.task = task
            return task
        }
        guard let task else { throw EnrollmentFailure.LISTENING }
        let failure = await withTaskCancellationHandler { await task.value } onCancel: { task.cancel() }
        state.withLock { state in
            state.isRunning = false
            state.task = nil
        }
        if let failure { throw failure }
    }

    /// 動いていなければ何もしない。
    func cancel() {
        state.withLock { $0.task?.cancel() }
    }
}
