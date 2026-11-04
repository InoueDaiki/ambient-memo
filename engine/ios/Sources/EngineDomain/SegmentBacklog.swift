import Foundation

/**
 * 文字起こし待ちの発話の置き場所。音声の長さの合計に上限を持つ。
 *
 * 音声の読み取りを止めないように [offer] は待たない。あふれたら古い発話から解放して、
 * 欠落を溜める。DB への書き込みは文字起こし側が [drainGaps] で引き取って行う。
 *
 * Kotlin の `Channel(CONFLATED)` の起こし方は、ロックと 1 つの継続で書いた。待つのは文字起こし側の 1 つのタスクだけ。
 */
final class SegmentBacklog: @unchecked Sendable {
    private let maxSamples: Int64

    // ここから下は lock で守る
    private let lock = NSLock()
    private var segments: [SpeechSegment] = []
    private var gaps: [CaptureGap] = []
    private var bufferedSamples: Int64 = 0
    private var isClosed = false
    private var isWoken = false
    private var waiter: CheckedContinuation<Void, any Error>?

    init(_ maxSamples: Int64) {
        self.maxSamples = maxSamples
    }

    func offer(_ segment: SpeechSegment) {
        lock.withLock {
            segments.append(segment)
            bufferedSamples += Int64(segment.sampleCount)
            while bufferedSamples > maxSamples {
                dropOldest(.PROCESSING_BACKLOG)
            }
        }
        wakeUp()
    }

    func addGap(_ gap: CaptureGap) {
        lock.withLock { gaps.append(gap) }
        wakeUp()
    }

    /// 溜まった発話か欠落があれば `true`、閉じて空になったら `false` を返す。待っている間に取り消されると投げる。
    func awaitWork() async throws -> Bool {
        while true {
            let state: Bool? = lock.withLock {
                if !segments.isEmpty || !gaps.isEmpty { return true }
                if isClosed { return false }
                return nil
            }
            if let state { return state }
            try await receiveWakeUp()
        }
    }

    func poll() -> SpeechSegment? {
        lock.withLock {
            if segments.isEmpty { return nil }
            let segment = segments.removeFirst()
            bufferedSamples -= Int64(segment.sampleCount)
            return segment
        }
    }

    func drainGaps() -> [CaptureGap] {
        lock.withLock {
            let drained = gaps
            gaps.removeAll()
            return drained
        }
    }

    /// これ以上追加しない。溜まった分は処理を続ける。
    func close() {
        lock.withLock { isClosed = true }
        wakeUp()
    }

    /// 溜まった発話をすべて解放して、欠落にする。
    func abandonAll(_ reason: CaptureGapReason) {
        lock.withLock {
            while !segments.isEmpty {
                dropOldest(reason)
            }
        }
    }

    /// lock の内側で呼ぶ。
    private func dropOldest(_ reason: CaptureGapReason) {
        let dropped = segments.removeFirst()
        bufferedSamples -= Int64(dropped.sampleCount)
        // 区間は終了時刻が開始時刻より前にならないので、CaptureGap の確かめは通る
        if let gap = try? CaptureGap(dropped.startedAtMillis, dropped.endedAtMillis, reason) { gaps.append(gap) }
        dropped.close()
    }

    /// Kotlin の `trySend(Unit)`。待っていなければ、次に待つときにすぐ戻るように印を残す。
    private func wakeUp() {
        let continuation: CheckedContinuation<Void, any Error>? = lock.withLock {
            if let waiter {
                self.waiter = nil
                return waiter
            }
            isWoken = true
            return nil
        }
        continuation?.resume()
    }

    /// Kotlin の `receive()`。取り消されると CancellationError を投げる。
    private func receiveWakeUp() async throws {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
                let result: Result<Void, any Error>? = lock.withLock {
                    if isWoken {
                        isWoken = false
                        return .success(())
                    }
                    if Task.isCancelled { return .failure(CancellationError()) }
                    waiter = continuation
                    return nil
                }
                if let result { continuation.resume(with: result) }
            }
        } onCancel: {
            let continuation: CheckedContinuation<Void, any Error>? = lock.withLock {
                let waiting = waiter
                waiter = nil
                return waiting
            }
            continuation?.resume(throwing: CancellationError())
        }
    }
}
