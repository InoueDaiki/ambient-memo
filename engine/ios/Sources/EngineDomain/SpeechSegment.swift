import Foundation

/**
 * 発話区間の音声を持つ、メモリ上のバッファ。音声を保持するのはここだけにする。
 *
 * [close] すると中身を 0 で塗りつぶす。参照が残っていても音声は読めなくなる。
 *
 * Swift にしたことで変わった点:
 * - Swift の配列は書き換えるとコピーされるので、配列で持つと塗りつぶしがコピーに届かない。
 *   そこで音声は自分で確保したメモリに持ち、[withSamples] で貸す。貸している間は [close] を待たせる。
 * - [samples] は Kotlin と同じ名前で残したが、配列のコピーを返す。コピーは塗りつぶされないので、受け取った側で残さない。
 * - 解放済みのときは、Kotlin の `check` と同じく [IllegalStateException] を投げる。
 * - 渡した配列から音声をコピーするので、呼び出し側の配列は呼び出し側で捨てる。
 */
public final class SpeechSegment: @unchecked Sendable {
    public let startedAtMillis: Int64
    public let endedAtMillis: Int64
    public let sampleRateHz: Int
    public let sampleCount: Int

    // buffer と released は lock で守る
    private let lock = NSLock()
    private let buffer: UnsafeMutableBufferPointer<Int16>
    private var released = false

    public init(startedAtMillis: Int64, endedAtMillis: Int64, sampleRateHz: Int, samples: [Int16]) {
        self.startedAtMillis = startedAtMillis
        self.endedAtMillis = endedAtMillis
        self.sampleRateHz = sampleRateHz
        sampleCount = samples.count
        buffer = UnsafeMutableBufferPointer<Int16>.allocate(capacity: max(samples.count, 1))
        buffer.initialize(repeating: 0)
        _ = buffer.update(fromContentsOf: samples)
    }

    /// 音声を [samples] からコピーする。途中の配列を作らないので、塗りつぶせないコピーが残らない。
    init(startedAtMillis: Int64, endedAtMillis: Int64, sampleRateHz: Int, copying samples: UnsafeBufferPointer<Int16>) {
        self.startedAtMillis = startedAtMillis
        self.endedAtMillis = endedAtMillis
        self.sampleRateHz = sampleRateHz
        sampleCount = samples.count
        buffer = UnsafeMutableBufferPointer<Int16>.allocate(capacity: max(samples.count, 1))
        buffer.initialize(repeating: 0)
        _ = buffer.update(fromContentsOf: samples)
    }

    deinit {
        buffer.update(repeating: 0)
        buffer.deallocate()
    }

    public var isReleased: Bool {
        lock.lock()
        defer { lock.unlock() }
        return released
    }

    /// 音声のコピーを返す。コピーは [close] で塗りつぶされないので、使い終わったら捨てる。
    public var samples: [Int16] {
        get throws {
            try withSamples { Array($0) }
        }
    }

    /// 音声を貸す。[body] の外へポインタを持ち出さない。解放済みなら [IllegalStateException] を投げる。
    public func withSamples<Result>(_ body: (UnsafeBufferPointer<Int16>) throws -> Result) throws -> Result {
        lock.lock()
        defer { lock.unlock() }
        try check(!released, "解放済みのバッファです")
        return try body(UnsafeBufferPointer(rebasing: buffer[0..<sampleCount]))
    }

    public func close() {
        lock.lock()
        defer { lock.unlock() }
        if !released {
            released = true
            buffer.update(repeating: 0)
        }
    }
}

extension SpeechSegment {
    /// Kotlin の `use`。[body] が終われば、成功しても失敗しても [close] する。
    public func use<Result>(_ body: (SpeechSegment) async throws -> Result) async rethrows -> Result {
        defer { close() }
        return try await body(self)
    }
}
