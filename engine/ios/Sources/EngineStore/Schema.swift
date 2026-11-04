import Foundation

/**
 * DB のスキーマの版と、作成とマイグレーション。
 *
 * 表、列、制約、索引は Android(`engine/adapters/src/main/sqldelight` の `.sq`)と同じにする。
 * 端末をまたいでデータを移すことはないが、同じ考え方で保つため。`EngineStoreTests` が Android の最新の
 * スキーマ(`databases/<版>.db`)と比べる。
 *
 * 版は Android と同じく `PRAGMA user_version` に持ち、番号も Android の最新の版に揃える。
 * iOS の最初の版は Android の版 6 から始めたので、iOS には版 1 から 5 の DB はなく、そのマイグレーションもない。
 * 以後スキーマを変えるときは、Android に `.sqm` を足すのと同じ変更を [migrations] に足し、[version] を上げる。
 * DB のスキーマを変えたら、全版からのマイグレーションをテストする(CLAUDE.md)。
 */
enum EngineSchema {
    /// 今のスキーマの版。Android の SQLDelight の版(マイグレーションの数 + 1)と同じ番号にする。
    static let version: Int32 = 6

    /// iOS で最初に作った版。これより古い版の DB は iOS にはない。
    static let firstVersion: Int32 = 6

    /**
     * 版 n から n + 1 へのマイグレーション。キーは移す前の版。
     * 例: 版 6 から 7 へ列を足すなら `6: "ALTER TABLE todo ADD COLUMN ...;"` を足し、[version] を 7 にして、
     * [create] にも同じ列を足す。新しく作る DB は [create] で、既存の DB はここで同じ形になるようにする。
     */
    static let migrations: [Int32: String] = [:]

    /// 新しい DB に今の版のスキーマを作る。文は Android の `.sq` の CREATE と同じ。
    static let create = """
        CREATE TABLE speaker (
          id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
          display_name TEXT NOT NULL,
          name_source TEXT NOT NULL,
          is_self INTEGER NOT NULL,
          created_at_ms INTEGER NOT NULL,
          CHECK (is_self IN (0, 1))
        );

        CREATE UNIQUE INDEX speaker_self ON speaker (is_self) WHERE is_self = 1;

        CREATE TABLE speaker_embedding (
          speaker_id INTEGER NOT NULL REFERENCES speaker (id),
          model_version TEXT NOT NULL,
          vector BLOB NOT NULL,
          sample_count INTEGER NOT NULL,
          updated_at_ms INTEGER NOT NULL,
          voiced_ms INTEGER NOT NULL DEFAULT 0,
          PRIMARY KEY (speaker_id, model_version),
          CHECK (sample_count > 0)
        );

        CREATE INDEX speaker_embedding_recent ON speaker_embedding (model_version, updated_at_ms);

        CREATE TABLE self_voice_adaptation (
          model_version TEXT NOT NULL PRIMARY KEY,
          vector BLOB NOT NULL,
          voiced_ms INTEGER NOT NULL,
          updated_at_ms INTEGER NOT NULL,
          CHECK (voiced_ms > 0)
        );

        CREATE TABLE speaker_decision_stat (
          day_ms INTEGER NOT NULL,
          decision TEXT NOT NULL,
          bucket INTEGER NOT NULL,
          runs INTEGER NOT NULL,
          voiced_ms INTEGER NOT NULL,
          PRIMARY KEY (day_ms, decision, bucket)
        );

        CREATE TABLE utterance (
          id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
          started_at_ms INTEGER NOT NULL,
          ended_at_ms INTEGER NOT NULL,
          body TEXT NOT NULL,
          confidence REAL,
          created_at_ms INTEGER NOT NULL,
          organized_at_ms INTEGER,
          speaker_id INTEGER REFERENCES speaker (id),
          speaker_confidence REAL,
          CHECK (ended_at_ms >= started_at_ms),
          CHECK (confidence IS NULL OR confidence BETWEEN 0.0 AND 1.0)
        );

        CREATE INDEX utterance_started_at ON utterance (started_at_ms);

        CREATE INDEX utterance_unorganized ON utterance (started_at_ms, id) WHERE organized_at_ms IS NULL;

        CREATE INDEX utterance_speaker ON utterance (speaker_id);

        CREATE TABLE capture_gap (
          id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
          started_at_ms INTEGER NOT NULL,
          ended_at_ms INTEGER NOT NULL,
          reason TEXT NOT NULL,
          CHECK (ended_at_ms >= started_at_ms)
        );

        CREATE INDEX capture_gap_started_at ON capture_gap (started_at_ms);

        CREATE TABLE memo (
          id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
          started_at_ms INTEGER NOT NULL,
          ended_at_ms INTEGER NOT NULL,
          title TEXT,
          summary TEXT,
          todo_extraction TEXT NOT NULL,
          model_version TEXT NOT NULL,
          created_at_ms INTEGER NOT NULL,
          CHECK (ended_at_ms >= started_at_ms)
        );

        CREATE INDEX memo_started_at ON memo (started_at_ms);

        CREATE TABLE memo_utterance (
          memo_id INTEGER NOT NULL REFERENCES memo (id),
          utterance_id INTEGER NOT NULL REFERENCES utterance (id),
          PRIMARY KEY (memo_id, utterance_id)
        );

        CREATE UNIQUE INDEX memo_utterance_utterance ON memo_utterance (utterance_id);

        CREATE TABLE todo (
          id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
          memo_id INTEGER NOT NULL REFERENCES memo (id),
          task TEXT NOT NULL,
          kind TEXT NOT NULL,
          due_date TEXT,
          status TEXT NOT NULL,
          created_at_ms INTEGER NOT NULL,
          completed_at_ms INTEGER,
          completed_from_status TEXT,
          title TEXT NOT NULL DEFAULT ''
        );

        CREATE INDEX todo_memo ON todo (memo_id);
        """

    /**
     * 版を見て、作るか移す。1 つのトランザクションで行い、途中で失敗したら版も含めて元に戻す。
     *
     * 版 0 は新しい DB。iOS の最初の版より古い版と、今より新しい版は開かない。
     * 新しい版は、新しいアプリで作った DB を古いアプリで開いたときに起きる。読み替えると壊すので失敗にする。
     */
    static func migrate(_ connection: SQLiteConnection) throws {
        try connection.transaction {
            let current = try userVersion(connection)
            if current == version { return }
            if current == 0 {
                try connection.executeScript(create)
            } else {
                guard current >= firstVersion, current < version else {
                    throw EngineStoreError.unsupportedSchemaVersion(current)
                }
                for from in current..<version {
                    guard let script = migrations[from] else { throw EngineStoreError.unsupportedSchemaVersion(current) }
                    try connection.executeScript(script)
                }
            }
            try connection.executeScript("PRAGMA user_version = \(version)")
        }
    }

    static func userVersion(_ connection: SQLiteConnection) throws -> Int32 {
        Int32(try connection.queryOne("PRAGMA user_version") { $0.int64(0) } ?? 0)
    }
}

public enum EngineStoreError: Error, Equatable, CustomStringConvertible {
    /// このアプリでは開けない版の DB。
    case unsupportedSchemaVersion(Int32)

    /// DB はもう閉じている。
    case closed

    public var description: String {
        switch self {
        case let .unsupportedSchemaVersion(version):
            return "開けない版の DB です: \(version)"
        case .closed:
            return "DB は閉じています"
        }
    }
}
