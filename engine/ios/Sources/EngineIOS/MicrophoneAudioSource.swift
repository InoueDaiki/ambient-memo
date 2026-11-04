import AVFoundation
import EngineDomain
import Foundation

private let SAMPLE_RATE_HZ = 16_000
private let FRAME_MILLIS: Int64 = 100

/// まとめて流す長さ。メモに残るまでがこの分だけ遅れる。Android の READ_MILLIS と同じ。
private let READ_MILLIS: Int64 = 1_000

/// 受け取る側が遅れたときに持つ音声の上限。Android の AudioRecord のバッファ(3 秒)と同じ。
private let BUFFER_MILLIS: Int64 = 3_000

/// OS に頼む読み取りの単位(サンプル数)。目安で、OS が変えることがある。
private let TAP_BUFFER_FRAMES: AVAudioFrameCount = 4_096

/// 音声を読み取れなくなった。[isInterrupted] は電話などの中断や、ほかのアプリにマイクを取られたとき。
public struct AudioCaptureError: Error, CustomStringConvertible {
    public let description: String
    public let isInterrupted: Bool

    public init(_ description: String, isInterrupted: Bool = false) {
        self.description = description
        self.isInterrupted = isInterrupted
    }
}

/// マイクの許可。
public func isMicrophoneGranted() -> Bool {
    if #available(iOS 17.0, *) {
        return AVAudioApplication.shared.recordPermission == .granted
    }
    return AVAudioSession.sharedInstance().recordPermission == .granted
}

/**
 * マイクから 16 kHz、モノラル、16 ビットの PCM を流す。音声はメモリ上のバッファにしか持たず、ディスクに書かない。
 *
 * - AVAudioEngine の入力を 16 kHz のモノラルに変換し、1 秒ずつまとめて、0.1 秒のフレームに分けて流す(Android と同じ)
 * - フレームの時刻はサンプル数から数え、壁時計との差が 3 秒を超えたら音声が失われたとみなして時刻を壁時計に合わせる。
 *   パイプラインはこの飛びを欠落として記録する
 * - 受け取る側が遅れたら古いフレームから捨てる。捨てた分は時刻の飛びとして伝わる
 * - 電話などの中断、ほかのアプリによるマイクの占有、メディアのサービスの再起動では止まる。自動では戻らない
 * - 入力の経路が変わったら(イヤホンの抜き差しなど)、新しい形式で読み直す
 */
public final class MicrophoneAudioSource: AudioSource {
    public let sampleRateHz = SAMPLE_RATE_HZ

    public init() {}

    public func frames() -> AsyncThrowingStream<AudioFrame, any Error> {
        let maxFrames = Int(BUFFER_MILLIS / FRAME_MILLIS)
        return AsyncThrowingStream(bufferingPolicy: .bufferingNewest(maxFrames)) { continuation in
            let capture = MicrophoneCapture(continuation: continuation)
            continuation.onTermination = { _ in capture.stop() }
            capture.start()
        }
    }
}

/// 1 回の聞き取りの間だけ生きる。OS のスレッドから呼ばれるので、状態は lock で守る。
private final class MicrophoneCapture: @unchecked Sendable {
    private let continuation: AsyncThrowingStream<AudioFrame, any Error>.Continuation
    private let lock = NSLock()
    private let engine = AVAudioEngine()
    private let outputFormat = AVAudioFormat(
        commonFormat: .pcmFormatInt16,
        sampleRate: Double(SAMPLE_RATE_HZ),
        channels: 1,
        interleaved: true
    )!
    private var converter: AVAudioConverter?
    private var observers: [NSObjectProtocol] = []
    private var pending: [Int16] = []
    private var position: SamplePosition?
    private var isStopped = false

    init(continuation: AsyncThrowingStream<AudioFrame, any Error>.Continuation) {
        self.continuation = continuation
        pending.reserveCapacity(Int(Int64(SAMPLE_RATE_HZ) * READ_MILLIS / 1_000) * 2)
    }

    func start() {
        do {
            try configureSession()
            observe()
            try startEngine()
        } catch let error as AudioCaptureError {
            fail(error)
        } catch {
            fail(AudioCaptureError("録音を開始できません"))
        }
    }

    /**
     * ほかのアプリの音楽を止めないように、混ぜて鳴らせる設定にする。`measurement` は OS の声の処理と自動の音量調整を避ける
     * (Android の VOICE_RECOGNITION と同じ考え方)。
     */
    private func configureSession() throws {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playAndRecord, mode: .measurement, options: [.mixWithOthers, .defaultToSpeaker])
            try? session.setPreferredSampleRate(Double(SAMPLE_RATE_HZ))
            try session.setActive(true)
        } catch {
            throw AudioCaptureError("音声のセッションを始められません")
        }
        guard session.isInputAvailable else { throw AudioCaptureError("マイクがありません") }
    }

    private func observe() {
        let center = NotificationCenter.default
        observers.append(
            center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: nil) { [weak self] note in
                self?.onInterruption(note)
            }
        )
        observers.append(
            center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: nil) { [weak self] _ in
                self?.fail(AudioCaptureError("音声のサービスが再起動しました", isInterrupted: true))
            }
        )
        observers.append(
            center.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: nil) { [weak self] _ in
                self?.onConfigurationChange()
            }
        )
    }

    /// 中断で止まったら戻さない。利用者に再開を促す(docs/設計メモ.md の「iOS」)。
    private func onInterruption(_ note: Notification) {
        guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              AVAudioSession.InterruptionType(rawValue: raw) == .began
        else { return }
        fail(AudioCaptureError("ほかの音声に中断されました", isInterrupted: true))
    }

    /// 経路が変わると AVAudioEngine は止まるので、新しい入力の形式で読み直す。
    private func onConfigurationChange() {
        lock.lock()
        let stopped = isStopped
        lock.unlock()
        if stopped { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        do {
            try startEngine()
        } catch {
            fail(AudioCaptureError("入力の経路が変わり、読み直せません", isInterrupted: true))
        }
    }

    private func startEngine() throws {
        let input = engine.inputNode
        let inputFormat = input.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0,
              let converter = AVAudioConverter(from: inputFormat, to: outputFormat)
        else { throw AudioCaptureError("マイクの形式を使えません") }
        lock.withLock { self.converter = converter }
        input.installTap(onBus: 0, bufferSize: TAP_BUFFER_FRAMES, format: inputFormat) { [weak self] buffer, _ in
            self?.accept(buffer)
        }
        engine.prepare()
        do {
            try engine.start()
        } catch {
            input.removeTap(onBus: 0)
            throw AudioCaptureError("録音を開始できません")
        }
    }

    private func accept(_ buffer: AVAudioPCMBuffer) {
        lock.lock()
        defer { lock.unlock() }
        guard !isStopped, let converter else { return }
        let ratio = outputFormat.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount((Double(buffer.frameLength) * ratio).rounded(.up)) + 32
        guard let output = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: capacity) else { return }
        var consumed = false
        var conversionError: NSError?
        let status = converter.convert(to: output, error: &conversionError) { _, inputStatus in
            if consumed {
                inputStatus.pointee = .noDataNow
                return nil
            }
            consumed = true
            inputStatus.pointee = .haveData
            return buffer
        }
        defer {
            if let channel = output.int16ChannelData?[0] { channel.update(repeating: 0, count: Int(output.frameCapacity)) }
        }
        guard status != .error, let channel = output.int16ChannelData?[0] else { return }
        pending.append(contentsOf: UnsafeBufferPointer(start: channel, count: Int(output.frameLength)))
        let chunkSamples = Int(Int64(SAMPLE_RATE_HZ) * READ_MILLIS / 1_000)
        if pending.count >= chunkSamples { flush() }
    }

    /// まとめた音声に時刻を付けて、0.1 秒のフレームに分けて流す。lock の中で呼ぶ。
    private func flush() {
        let count = pending.count
        let next = SamplePosition.advance(position, count: count, sampleRateHz: SAMPLE_RATE_HZ, maxLagMillis: BUFFER_MILLIS)
        let frameSamples = Int(Int64(SAMPLE_RATE_HZ) * FRAME_MILLIS / 1_000)
        var offset = 0
        while offset < count {
            let size = min(frameSamples, count - offset)
            let startedAt = next.frameStartMillis + Int64(offset) * 1_000 / Int64(SAMPLE_RATE_HZ)
            continuation.yield(AudioFrame(startedAtMillis: startedAt, samples: Array(pending[offset..<offset + size])))
            offset += size
        }
        position = next
        for index in pending.indices { pending[index] = 0 }
        pending.removeAll(keepingCapacity: true)
    }

    private func fail(_ error: AudioCaptureError) {
        stop()
        continuation.finish(throwing: error)
    }

    func stop() {
        lock.lock()
        if isStopped {
            lock.unlock()
            return
        }
        isStopped = true
        for index in pending.indices { pending[index] = 0 }
        pending.removeAll()
        converter = nil
        let observers = self.observers
        self.observers = []
        lock.unlock()
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}

/**
 * サンプル数から数えた時刻。Android の PcmReaderAudioSource の Position と同じ。
 * 端数のミリ秒を積み上げないように、基準の時刻からのサンプル数で持つ。
 */
struct SamplePosition {
    let baseMillis: Int64
    let samplesRead: Int64
    let frameStartMillis: Int64

    static func advance(_ previous: SamplePosition?, count: Int, sampleRateHz: Int, maxLagMillis: Int64) -> SamplePosition {
        let now = SystemClock().nowMillis()
        let wallStartMillis = now - Int64(count) * 1_000 / Int64(sampleRateHz)
        if let previous {
            let startMillis = previous.baseMillis + previous.samplesRead * 1_000 / Int64(sampleRateHz)
            if wallStartMillis - startMillis <= maxLagMillis {
                return SamplePosition(
                    baseMillis: previous.baseMillis,
                    samplesRead: previous.samplesRead + Int64(count),
                    frameStartMillis: startMillis
                )
            }
        }
        return SamplePosition(baseMillis: wallStartMillis, samplesRead: Int64(count), frameStartMillis: wallStartMillis)
    }
}
