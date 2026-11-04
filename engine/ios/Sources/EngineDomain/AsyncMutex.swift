import Foundation

/**
 * kotlinx.coroutines の `Mutex` にあたる、待つ間にスレッドを止めないロック。Kotlin のファイルには対応しない。
 *
 * 待っているタスクは来た順にロックを得る。待っている間に取り消されると CancellationError を投げる。
 * Kotlin と同じく再入できない。同じタスクで入れ子にすると止まる。
 * Swift 標準の `Synchronization.Mutex` は待つ間にスレッドを止めるので、async の処理をまたいで持てない。
 */
public final class AsyncMutex: @unchecked Sendable {
    private struct Waiter {
        let id: UInt64
        let continuation: CheckedContinuation<Void, any Error>
    }

    // ここから下は lock で守る
    private let lock = NSLock()
    private var isLocked = false
    private var waiters: [Waiter] = []
    private var nextId: UInt64 = 0

    public init() {}

    public func withLock<Result>(_ body: () async throws -> Result) async throws -> Result {
        try await acquire()
        defer { release() }
        return try await body()
    }

    private func acquire() async throws {
        let id: UInt64 = lock.withLock {
            nextId += 1
            return nextId
        }
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
                let result: Result<Void, any Error>? = lock.withLock {
                    if Task.isCancelled { return .failure(CancellationError()) }
                    if !isLocked {
                        isLocked = true
                        return .success(())
                    }
                    waiters.append(Waiter(id: id, continuation: continuation))
                    return nil
                }
                if let result { continuation.resume(with: result) }
            }
        } onCancel: {
            let continuation: CheckedContinuation<Void, any Error>? = lock.withLock {
                guard let index = waiters.firstIndex(where: { $0.id == id }) else { return nil }
                return waiters.remove(at: index).continuation
            }
            continuation?.resume(throwing: CancellationError())
        }
    }

    private func release() {
        let next: CheckedContinuation<Void, any Error>? = lock.withLock {
            if waiters.isEmpty {
                isLocked = false
                return nil
            }
            // ロックは持ったまま次へ渡す
            return waiters.removeFirst().continuation
        }
        next?.resume()
    }
}
