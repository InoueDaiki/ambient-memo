import EngineDomain
import Foundation

/**
 * 1 つの DB の上のリポジトリをまとめたもの。エンジンはこれを 1 つだけ作り、各ポートに渡す。
 *
 * ```swift
 * let directory = applicationSupport.appendingPathComponent("EngineStore", isDirectory: true)
 * let databaseURL = directory.appendingPathComponent("ambient-memo.db")
 * try EngineStoreFiles.prepareDatabaseFile(at: databaseURL)
 * let store = try EngineStore(databaseURL: databaseURL)
 * let attempts = FileOrganizeAttempts(url: directory.appendingPathComponent("organize-attempts.json"))
 * ```
 *
 * 発話の話者の付け替え(話者の統合とリセット)は [speakers] が同じ DB の発話の表を直接書く。
 */
public struct EngineStore: Sendable {
    public let database: EngineDatabase
    public let utterances: SqliteUtteranceRepository
    public let captureGaps: SqliteCaptureGapRepository
    /// [SpeakerRepository] と [SpeakerDecisionLog] の両方。
    public let speakers: SqliteSpeakerRepository
    public let memos: SqliteMemoRepository

    public init(database: EngineDatabase) {
        self.database = database
        utterances = SqliteUtteranceRepository(database: database)
        captureGaps = SqliteCaptureGapRepository(database: database)
        speakers = SqliteSpeakerRepository(database: database)
        memos = SqliteMemoRepository(database: database)
    }

    /// [databaseURL] の DB を開く(なければ作り、古ければ移す)。
    public init(databaseURL: URL) throws {
        self.init(database: try EngineDatabase(url: databaseURL))
    }

    public var speakerDecisionLog: SqliteSpeakerRepository { speakers }
}
