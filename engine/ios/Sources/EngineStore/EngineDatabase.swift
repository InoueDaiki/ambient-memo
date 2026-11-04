import Foundation
import SQLite3

/**
 * エンジンの DB。1 つの接続を持ち、読み書きをすべて 1 つの直列のキューで順に行う。
 *
 * どのタスクから同時に呼んでもよい。SQLite の処理は Swift の並行処理のスレッドを止めないように、
 * 専用のキューで行い、終わるまで呼び出し側のタスクを待たせる(Kotlin の `withContext(Dispatchers.IO)` にあたる)。
 * 開くときに版を見て、作成かマイグレーションを行う。
 *
 * 外部キーの制約は Android と同じく有効にしない(SQLDelight の Android と JDBC のドライバーの既定)。
 * 話者を消す処理は、発話の話者を先に付け替えるか外してから消す。
 *
 * DB に書くのはエンジンだけ(CLAUDE.md)。画面からはエンジンの境界を通して使う。
 */
public final class EngineDatabase: @unchecked Sendable {
    // connection と isClosed は queue の上でだけ触る。そのため @unchecked Sendable にしている
    private let queue: DispatchQueue
    private let connection: SQLiteConnection
    private var isClosed = false

    /// DB のファイル。テストで同じファイルを開き直すときに使う。
    public let url: URL

    /**
     * [url] の DB を開く。なければ作る。版が古ければここで移す。
     *
     * iOS では、DB と、SQLite が後から作る -wal と -shm のファイルの保護を
     * completeUntilFirstUserAuthentication にする。画面のロック中にも聞き取りを書くので。
     * バックアップから外すのは [EngineStoreFiles.prepareDatabaseFile] で行う。先にそれを呼ぶ。
     */
    public init(url: URL) throws {
        var flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_NOMUTEX
        #if os(iOS)
        flags |= SQLITE_OPEN_FILEPROTECTION_COMPLETEUNTILFIRSTUSERAUTHENTICATION
        #endif
        let connection = try SQLiteConnection(path: url.path, flags: flags)
        do {
            // ほかのプロセス(将来の拡張など)が書いている間は、すぐに失敗せずに待つ
            try connection.executeScript("PRAGMA busy_timeout = 5000")
            // Android 9 以降の既定(互換の WAL)に寄せる。読み書きが重なっても待たない
            try connection.executeScript("PRAGMA journal_mode = WAL")
            try connection.executeScript("PRAGMA synchronous = NORMAL")
            try EngineSchema.migrate(connection)
        } catch {
            connection.close()
            throw error
        }
        self.connection = connection
        self.url = url
        self.queue = DispatchQueue(label: "dev.ambientmemo.engine.store")
    }

    // 閉じ忘れても、最後の参照が消えたときに SQLiteConnection の deinit で閉じる

    /// 閉じる。閉じた後の読み書きは [EngineStoreError.closed] を投げる。何度呼んでもよい。
    public func close() {
        queue.sync {
            isClosed = true
            connection.close()
        }
    }

    /// 今の DB の版。
    public func schemaVersion() async throws -> Int {
        Int(try await run { try EngineSchema.userVersion($0) })
    }

    /// 1 回の読み書き。複数の文を書くときは [transaction] を使う。
    @discardableResult
    func perform<Result: Sendable>(_ body: @escaping @Sendable (SQLiteConnection) throws -> Result) async throws -> Result {
        try await run(body)
    }

    /// [body] を 1 つのトランザクションで行う。投げたら何も書かない。
    @discardableResult
    func transaction<Result: Sendable>(
        _ body: @escaping @Sendable (SQLiteConnection) throws -> Result
    ) async throws -> Result {
        try await run { connection in try connection.transaction { try body(connection) } }
    }

    private func run<Result: Sendable>(_ body: @escaping @Sendable (SQLiteConnection) throws -> Result) async throws -> Result {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                continuation.resume(with: Swift.Result {
                    if self.isClosed { throw EngineStoreError.closed }
                    return try body(self.connection)
                })
            }
        }
    }
}
