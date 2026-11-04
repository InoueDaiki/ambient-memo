import EngineDomain
import EngineDomainTesting
@testable import EngineStore
import Foundation
import Testing

/// 契約テストを流す実装。Fake と本物(SQLite とファイル)の両方に同じテストを流す(CLAUDE.md)。
enum Implementation: String, CaseIterable, CustomTestStringConvertible, Sendable {
    case fake
    case real

    var testDescription: String { self == .fake ? "Fake" : "本物" }
}

/**
 * テストごとの一時ディレクトリ。開いた DB は、持ち主のテストのインスタンスが消えるときに閉じて消す。
 * swift-testing はテストの呼び出しごとにスイートのインスタンスを作るので、スイートの属性として持つ。
 */
final class StoreFixture {
    let directory: URL
    private var databases: [EngineDatabase] = []

    init() {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("EngineStoreTests-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    deinit {
        databases.forEach { $0.close() }
        try? FileManager.default.removeItem(at: directory)
    }

    var databaseURL: URL { directory.appendingPathComponent("test.db") }

    /// 本番と同じく、版を見て作成かマイグレーションを行って開く。同じファイルを何度開いてもよい。
    func open() throws -> EngineDatabase {
        let database = try EngineDatabase(url: databaseURL)
        databases.append(database)
        return database
    }

    func utterances(_ implementation: Implementation) throws -> any UtteranceRepository {
        switch implementation {
        case .fake: InMemoryUtteranceRepository()
        case .real: SqliteUtteranceRepository(database: try open())
        }
    }

    func captureGaps(_ implementation: Implementation) throws -> any CaptureGapRepository {
        switch implementation {
        case .fake: InMemoryCaptureGapRepository()
        case .real: SqliteCaptureGapRepository(database: try open())
        }
    }

    func speakers(_ implementation: Implementation) throws -> any SpeakerRepository {
        switch implementation {
        case .fake: InMemorySpeakerRepository()
        case .real: SqliteSpeakerRepository(database: try open())
        }
    }

    func decisionLog(_ implementation: Implementation) throws -> any SpeakerDecisionLog {
        switch implementation {
        case .fake: InMemorySpeakerDecisionLog()
        case .real: SqliteSpeakerRepository(database: try open())
        }
    }

    /// 発話とメモを同じ保存先で作る。メモの契約は、発話を [UtteranceRepository] から入れる。
    func memoStores(_ implementation: Implementation) throws -> (utterances: any UtteranceRepository, memos: any MemoRepository) {
        switch implementation {
        case .fake:
            let utterances = InMemoryUtteranceRepository()
            return (utterances, InMemoryMemoRepository(utterances: utterances))
        case .real:
            let store = EngineStore(database: try open())
            return (store.utterances, store.memos)
        }
    }

    func organizeAttempts(_ implementation: Implementation) -> any OrganizeAttempts {
        switch implementation {
        case .fake: InMemoryOrganizeAttempts()
        case .real: FileOrganizeAttempts(url: directory.appendingPathComponent("attempts.json"))
        }
    }
}
