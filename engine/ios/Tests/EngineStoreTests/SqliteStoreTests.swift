import EngineDomain
@testable import EngineStore
import Foundation
import SQLite3
import Testing

/// 本物の SQLite にだけある性質。Android の SqliteRepositoryTest と SchemaMigrationTest にあたる。
@Suite final class SqliteStoreTests {
    private let fixture = StoreFixture()

    private func utterance(_ startedAtMillis: Int64, _ endedAtMillis: Int64, _ text: String) throws -> NewUtterance {
        try NewUtterance(startedAtMillis: startedAtMillis, endedAtMillis: endedAtMillis, text: text, confidence: nil, createdAtMillis: endedAtMillis)
    }

    @Test func まとめて保存の途中で失敗すると_1_行も残さない() async throws {
        let database = try fixture.open()
        try await database.perform { connection in
            try connection.executeScript(
                """
                CREATE TRIGGER reject BEFORE INSERT ON utterance WHEN NEW.body = '失敗'
                BEGIN SELECT RAISE(ABORT, 'reject'); END
                """
            )
        }
        let repository = SqliteUtteranceRepository(database: database)

        await #expect(throws: SQLiteError.self) {
            try await repository.insertAll([try self.utterance(0, 100, "前"), try self.utterance(100, 200, "失敗")])
        }

        #expect(try await repository.listOverlapping(fromMillis: 0, toMillis: 1_000).isEmpty)
    }

    @Test func 閉じて開き直しても残っている() async throws {
        let gap = try CaptureGap(100, 200, .PROCESSING_BACKLOG)
        let first = EngineStore(database: try fixture.open())
        _ = try await first.utterances.insert(try utterance(0, 100, "残す"))
        try await first.captureGaps.insert(gap)
        first.database.close()

        let second = EngineStore(database: try fixture.open())

        #expect(try await second.utterances.listOverlapping(fromMillis: 0, toMillis: 100).map(\.text) == ["残す"])
        #expect(try await second.captureGaps.listOverlapping(fromMillis: 100, toMillis: 200) == [gap])
    }

    @Test func 閉じた後の読み書きは失敗する() async throws {
        let database = try fixture.open()
        database.close()

        await #expect(throws: EngineStoreError.closed) {
            try await SqliteUtteranceRepository(database: database).listRecent(limit: 1)
        }
    }

    @Test func 自分は_DB_でも1人だけ() async throws {
        let database = try fixture.open()
        let insert = "INSERT INTO speaker (display_name, name_source, is_self, created_at_ms) VALUES ('自分', 'self', 1, 0)"
        try await database.perform { try $0.execute(insert) }

        await #expect(throws: SQLiteError.self) { try await database.perform { try $0.execute(insert) } }
    }

    @Test func 時刻が逆転した行は_DB_でも拒む() async throws {
        let database = try fixture.open()

        await #expect(throws: SQLiteError.self) {
            try await database.perform { connection in
                try connection.execute(
                    "INSERT INTO utterance (started_at_ms, ended_at_ms, body, created_at_ms) VALUES (10, 9, '本文', 10)"
                )
            }
        }
    }

    @Test func 未知の理由は黙って読み替えない() async throws {
        let database = try fixture.open()
        try await database.perform { try $0.execute("INSERT INTO capture_gap (started_at_ms, ended_at_ms, reason) VALUES (0, 100, 'unknown')") }

        await #expect(throws: IllegalStateException.self) {
            try await SqliteCaptureGapRepository(database: database).listOverlapping(fromMillis: 0, toMillis: 100)
        }
    }

    @Test func 特徴量はリトルエンディアンの_32_ビット浮動小数で残す() async throws {
        let database = try fixture.open()
        _ = try await SqliteSpeakerRepository(database: database)
            .saveSelfEmbedding(SpeakerEmbedding([1, -0.5], "model-1"), sampleCount: 1, nowMillis: 0)

        let bytes = try await database.perform { connection in
            try connection.queryOne("SELECT vector FROM speaker_embedding") { $0.data(0) }
        }

        #expect(bytes.map(Array.init) == [0x00, 0x00, 0x80, 0x3F, 0x00, 0x00, 0x00, 0xBF])
    }

    @Test func 並行に書いても行を失わず_別の_id_が付く() async throws {
        let store = EngineStore(database: try fixture.open())
        let count = 200

        let ids = try await withThrowingTaskGroup(of: Int64.self) { group in
            for index in 0..<count {
                group.addTask {
                    let start = Int64(index)
                    let saved = try await store.utterances.insert(
                        try NewUtterance(startedAtMillis: start, endedAtMillis: start + 1, text: "\(index)", confidence: nil, createdAtMillis: start + 1)
                    )
                    try await store.captureGaps.insert(try CaptureGap(start, start + 1, .PROCESSING_BACKLOG))
                    _ = try await store.speakers.createProvisionalSpeakerWithoutVoice(nowMillis: start)
                    return saved.id
                }
            }
            return try await group.reduce(into: Set<Int64>()) { $0.insert($1) }
        }

        #expect(ids.count == count)
        #expect(try await store.utterances.listOverlapping(fromMillis: 0, toMillis: Int64(count + 1)).count == count)
        #expect(try await store.captureGaps.listOverlapping(fromMillis: 0, toMillis: Int64(count + 1)).count == count)
        // 仮の話者の番号は、同時に作っても重ならない
        let names = try await store.speakers.listSpeakers().map(\.displayName)
        #expect(Set(names).count == count)
    }
}

/// スキーマの版とマイグレーション。Android の SchemaMigrationTest にあたる。
@Suite final class SchemaTests {
    private let fixture = StoreFixture()

    /// このファイルから見た engine のディレクトリ。
    private static let engineDirectory = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent() // EngineStoreTests
        .deletingLastPathComponent() // Tests
        .deletingLastPathComponent() // ios
        .deletingLastPathComponent() // engine

    private static let sqldelightDirectory = engineDirectory.appendingPathComponent("adapters/src/main/sqldelight")

    @Test func 新しい_DB_は今の版で作る() async throws {
        #expect(try await fixture.open().schemaVersion() == Int(EngineSchema.version))
    }

    @Test func 版の番号は_Android_の最新の版と同じ() throws {
        // SQLDelight の版は、マイグレーションの数 + 1
        let migrations = try FileManager.default
            .contentsOfDirectory(atPath: Self.sqldelightDirectory.appendingPathComponent("migrations").path)
            .filter { $0.hasSuffix(".sqm") }

        #expect(Int(EngineSchema.version) == migrations.count + 1)
    }

    @Test func 表と列と制約と索引が_Android_の最新のスキーマと同じ() async throws {
        let android = Self.sqldelightDirectory.appendingPathComponent("databases/\(EngineSchema.version).db")
        try #require(FileManager.default.fileExists(atPath: android.path), "Android のスキーマがありません: \(android.path)")
        let ours = try fixture.open()

        let expected = try Self.schema(of: android)
        let actual = try await ours.perform { try Self.schema($0) }

        #expect(actual == expected)
    }

    @Test func 開き直しても作り直さない() async throws {
        let first = EngineStore(database: try fixture.open())
        _ = try await first.utterances.insert(
            try NewUtterance(startedAtMillis: 0, endedAtMillis: 1, text: "残す", confidence: nil, createdAtMillis: 1)
        )
        first.database.close()

        let second = EngineStore(database: try fixture.open())

        #expect(try await second.utterances.listRecent(limit: 10).map(\.text) == ["残す"])
        #expect(try await second.database.schemaVersion() == Int(EngineSchema.version))
    }

    @Test func 新しいアプリで作った版の_DB_は開かない() async throws {
        let database = try fixture.open()
        try await database.perform { try $0.executeScript("PRAGMA user_version = \(EngineSchema.version + 1)") }
        database.close()

        #expect(throws: EngineStoreError.unsupportedSchemaVersion(EngineSchema.version + 1)) { try self.fixture.open() }
    }

    @Test func iOS_の最初の版より古い_DB_は開かない() async throws {
        let database = try fixture.open()
        try await database.perform { try $0.executeScript("PRAGMA user_version = \(EngineSchema.firstVersion - 1)") }
        database.close()

        #expect(throws: EngineStoreError.unsupportedSchemaVersion(EngineSchema.firstVersion - 1)) { try self.fixture.open() }
    }

    @Test func 版_6_から移すと_ToDo_を消し_メモと根拠を残す() async throws {
        try Self.prepare(fixture.databaseURL, version: 6)

        let store = EngineStore(database: try fixture.open())

        #expect(try await store.database.schemaVersion() == 7)
        // 版 7 の DB に Android と同じ版 7 の行を入れたものと、表の中身が同じになる
        let expectedURL = fixture.directory.appendingPathComponent("expected.db")
        try Self.prepare(expectedURL, version: 7)
        let expected = try Self.contents(of: expectedURL)
        #expect(try await store.database.perform { try Self.contents($0) } == expected)
        // 作り直した memo の続きの id を使い、作り直した索引で読める
        let memo = try #require(try await store.memos.findMemo(memoId: 1))
        #expect(memo.title == "題名")
        #expect(try await store.memos.listMemoUtterances(memoId: 1).map(\.id) == [1])
        #expect(try await store.memos.searchMemos(term: "要約", fromMillis: Int64.min, toMillis: Int64.max, limit: 10) == [memo])
        let utterance = try #require(try await store.memos.listUnorganizedUtterances().first)
        let next = try #require(try await store.memos.recordMemo(
            try NewMemo(
                startedAtMillis: utterance.startedAtMillis,
                endedAtMillis: utterance.endedAtMillis,
                title: nil,
                summary: nil,
                modelVersion: "fake-1",
                createdAtMillis: 10_000,
                utteranceIds: [utterance.id]
            )
        ))
        #expect(next.id == 2)
        let indexes = try await store.database.perform { connection in
            try connection.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'memo'") { $0.string(0) }
        }
        #expect(indexes == ["memo_started_at"])
    }

    @Test func 移した版_6_の_DB_の表と列と制約と索引は_Android_の最新のスキーマと同じ() async throws {
        try Self.prepare(fixture.databaseURL, version: 6)
        let android = Self.sqldelightDirectory.appendingPathComponent("databases/\(EngineSchema.version).db")

        let actual = try await fixture.open().perform { try Self.schema($0) }

        // 名前を変えた表は、SQLite が CREATE 文の表の名前を引用符で囲む。それだけが違う
        let normalized = actual.map { $0.replacingOccurrences(of: #"CREATE TABLE "memo""#, with: "CREATE TABLE memo") }
        #expect(normalized == (try Self.schema(of: android)))
    }

    @Test func すべての版からのマイグレーションがある() {
        // 版を上げたら、iOS の最初の版から今の版までのどこからでも移せる。各版からの中身のテストは版を上げるときに足す
        for version in EngineSchema.firstVersion..<EngineSchema.version {
            #expect(EngineSchema.migrations[version] != nil, "版 \(version) からのマイグレーションがありません")
        }
    }

    private static let seedsDirectory = engineDirectory.appendingPathComponent("adapters/src/test/resources/migration-seeds")

    /// Android のその版のスキーマ(`databases/<版>.db`)を写し、Android の移行のテストと同じ行(`migration-seeds/<版>.sql`)を入れる。
    /// Android のスキーマのファイルは版を持たないので、ここで付ける。
    private static func prepare(_ url: URL, version: Int32) throws {
        try FileManager.default.copyItem(at: sqldelightDirectory.appendingPathComponent("databases/\(version).db"), to: url)
        let seed = try String(contentsOf: seedsDirectory.appendingPathComponent("\(version).sql"), encoding: .utf8)
        let connection = try SQLiteConnection(path: url.path, flags: SQLITE_OPEN_READWRITE)
        defer { connection.close() }
        try connection.executeScript(seed)
        try connection.executeScript("PRAGMA user_version = \(version)")
    }

    /// 表ごとの行を、列の値を SQLite の quote で書いた文字列にして rowid の順に並べる。表は名前の順。
    private static func contents(_ connection: SQLiteConnection) throws -> [String: [String]] {
        let tables = try connection.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
        ) { $0.string(0) }
        var contents: [String: [String]] = [:]
        for table in tables {
            let columns = try connection.query("SELECT name FROM pragma_table_info(?) ORDER BY cid", [.text(table)]) { $0.string(0) }
            let row = columns.map { "quote(\($0))" }.joined(separator: " || ',' || ")
            contents[table] = try connection.query("SELECT \(row) FROM \(table) ORDER BY rowid") { $0.string(0) }
        }
        return contents
    }

    private static func contents(of url: URL) throws -> [String: [String]] {
        let connection = try SQLiteConnection(path: url.path, flags: SQLITE_OPEN_READONLY)
        defer { connection.close() }
        return try contents(connection)
    }

    /// sqlite_master の、SQLite が自動で作るものを除いた定義。比べやすいように名前の順に並べる。
    private static func schema(_ connection: SQLiteConnection) throws -> [String] {
        try connection.query(
            """
            SELECT type, name, tbl_name, sql FROM sqlite_master
            WHERE name NOT LIKE 'sqlite_%'
            ORDER BY type, name
            """
        ) { row in "\(row.string(0)) \(row.string(1)) \(row.string(2)): \(row.optionalString(3) ?? "")" }
    }

    /// Android のスキーマのファイルは変えないように、読み取りだけで開く。
    private static func schema(of url: URL) throws -> [String] {
        let connection = try SQLiteConnection(path: url.path, flags: SQLITE_OPEN_READONLY)
        defer { connection.close() }
        return try schema(connection)
    }
}

/// DB のファイルの用意。iOS の保護は macOS では確かめられないので、作成とバックアップの印だけを見る。
@Suite final class EngineStoreFilesTests {
    private let fixture = StoreFixture()

    @Test func ディレクトリとファイルを作り_バックアップから外す() throws {
        let url = fixture.directory.appendingPathComponent("EngineStore/ambient-memo.db")

        try EngineStoreFiles.prepareDatabaseFile(at: url)
        try EngineStoreFiles.prepareDatabaseFile(at: url)

        #expect(FileManager.default.fileExists(atPath: url.path))
        #expect(try url.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true)
        let directory = url.deletingLastPathComponent()
        #expect(try directory.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true)
    }

    @Test func 用意した空のファイルを_DB_として開ける() async throws {
        let url = fixture.directory.appendingPathComponent("EngineStore/ambient-memo.db")
        try EngineStoreFiles.prepareDatabaseFile(at: url)

        let database = try EngineDatabase(url: url)
        defer { database.close() }

        #expect(try await database.schemaVersion() == Int(EngineSchema.version))
    }
}

/// FileOrganizeAttempts にだけある性質。
@Suite final class FileOrganizeAttemptsTests {
    private let fixture = StoreFixture()

    @Test func 別のインスタンスからも回数を読める() throws {
        let url = fixture.directory.appendingPathComponent("attempts.json")
        try FileOrganizeAttempts(url: url).set(firstUtteranceId: 7, count: 2)

        #expect(try FileOrganizeAttempts(url: url).count(firstUtteranceId: 7) == 2)
    }

    @Test func 発話の_id_と回数のほかは書かない() throws {
        let url = fixture.directory.appendingPathComponent("attempts.json")
        try FileOrganizeAttempts(url: url).set(firstUtteranceId: 7, count: 2)

        #expect(try String(contentsOf: url, encoding: .utf8) == #"{"7":2}"#)
    }
}
