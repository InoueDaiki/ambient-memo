import EngineDomain
import EngineDomainTesting
import Foundation

/// kotlinx の CompletableDeferred<Unit> にあたる。一度だけ知らせ、待っている側を起こす。
final class Signal: @unchecked Sendable {
    private let lock = NSLock()
    private var isSignaled = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func signal() {
        let resumed: [CheckedContinuation<Void, Never>] = lock.withLock {
            if isSignaled { return [] }
            isSignaled = true
            defer { waiters.removeAll() }
            return waiters
        }
        resumed.forEach { $0.resume() }
    }

    func wait() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let ready: Bool = lock.withLock {
                if isSignaled { return true }
                waiters.append(continuation)
                return false
            }
            if ready { continuation.resume() }
        }
    }
}

/// kotlinx の awaitCancellation にあたる。取り消されるまで待ち、CancellationError を投げる。
func awaitCancellation() async throws -> Never {
    while true {
        try await Task.sleep(for: .seconds(3_600))
    }
}

/// 500ms の発話と 200ms の無音を [count] 回くり返す。
func bursts(_ count: Int) -> (audio: [Int16], speech: [ClosedRange<Int64>]) {
    var audio: [Int16] = []
    var speech: [ClosedRange<Int64>] = []
    for index in 0..<count {
        audio += SyntheticAudio.tone(500) + SyntheticAudio.silence(200)
        let start = Int64(index) * 700
        speech.append(start...(start + 499))
    }
    return (audio, speech)
}

/// 中の音声を流し終えたら [exhausted] で知らせ、[keepOpen] なら取り消されるまで終わらない音声源。
final class SignalingAudioSource: AudioSource {
    let sampleRateHz = TEST_SAMPLE_RATE_HZ
    let exhausted = Signal()
    private let inner: FakeAudioSource
    private let keepOpen: Bool

    init(_ samples: [Int16], keepOpen: Bool) {
        inner = FakeAudioSource(samples)
        self.keepOpen = keepOpen
    }

    func frames() -> AsyncThrowingStream<AudioFrame, any Error> {
        let frames = inner.frames()
        let iterator = IteratorBox(frames.makeAsyncIterator())
        return AsyncThrowingStream(unfolding: { [self] in
            if let frame = try await iterator.next() { return frame }
            exhausted.signal()
            if keepOpen { try await awaitCancellation() }
            return nil
        })
    }

    private final class IteratorBox: @unchecked Sendable {
        var iterator: AsyncThrowingStream<AudioFrame, any Error>.AsyncIterator

        init(_ iterator: AsyncThrowingStream<AudioFrame, any Error>.AsyncIterator) {
            self.iterator = iterator
        }

        func next() async throws -> AudioFrame? {
            try await iterator.next()
        }
    }
}

let pipelineConfig = try! CaptureConfig(
    maxUtteranceMillis: 1_000,
    minSilenceMillis: 100,
    preRollMillis: 0,
    minSpeechMillis: 0,
    maxBacklogMillis: 2_000
)
