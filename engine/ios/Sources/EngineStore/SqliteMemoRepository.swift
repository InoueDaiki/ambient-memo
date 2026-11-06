import EngineDomain
import Foundation

/// Android の `SqliteMemoRepository` と同じ。
public final class SqliteMemoRepository: MemoRepository {
    private let database: EngineDatabase

    public init(database: EngineDatabase) {
        self.database = database
    }

    public func listUnorganizedUtterances() async throws -> [Utterance] {
        try await database.perform { connection in
            try connection.query(
                """
                SELECT \(UTTERANCE_COLUMNS)
                FROM utterance
                WHERE organized_at_ms IS NULL
                ORDER BY started_at_ms, id
                """,
                [],
                toUtterance
            )
        }
    }

    public func recordMemo(_ memo: NewMemo) async throws -> Memo? {
        try await database.transaction { connection -> Memo? in
            let allUnorganized = try memo.utteranceIds.allSatisfy { id in
                try connection.queryOne(
                    "SELECT count(*) FROM utterance WHERE id = ? AND organized_at_ms IS NULL",
                    [.integer(id)]
                ) { $0.int64(0) } == 1
            }
            // まだ何も書いていないので、ここで終えれば何も残らない(Kotlin の rollback(null) にあたる)
            if !allUnorganized { return nil }
            try connection.execute(
                """
                INSERT INTO memo (started_at_ms, ended_at_ms, title, summary, model_version, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                [
                    .integer(memo.startedAtMillis),
                    .integer(memo.endedAtMillis),
                    memo.title.sqlite,
                    memo.summary.sqlite,
                    .text(memo.modelVersion),
                    .integer(memo.createdAtMillis),
                ]
            )
            let memoId = connection.lastInsertedId
            for utteranceId in memo.utteranceIds {
                try connection.execute(
                    "INSERT INTO memo_utterance (memo_id, utterance_id) VALUES (?, ?)",
                    [.integer(memoId), .integer(utteranceId)]
                )
                try Self.markOrganized(connection, utteranceId, memo.createdAtMillis)
            }
            return Memo(
                id: memoId,
                startedAtMillis: memo.startedAtMillis,
                endedAtMillis: memo.endedAtMillis,
                title: memo.title,
                summary: memo.summary,
                modelVersion: memo.modelVersion,
                createdAtMillis: memo.createdAtMillis
            )
        }
    }

    public func markOrganized(_ utteranceIds: [Int64], organizedAtMillis: Int64) async throws {
        try await database.transaction { connection in
            for id in utteranceIds { try Self.markOrganized(connection, id, organizedAtMillis) }
        }
    }

    public func listMemos() async throws -> [Memo] {
        try await database.perform { connection in
            try connection.query("SELECT \(MEMO_COLUMNS) FROM memo ORDER BY started_at_ms, id", [], toMemo)
        }
    }

    public func listRecentMemos(limit: Int) async throws -> [Memo] {
        try await database.perform { connection in
            try connection.query(
                "SELECT \(MEMO_COLUMNS) FROM memo ORDER BY started_at_ms DESC, id DESC LIMIT ?",
                [.integer(Int64(limit))],
                toMemo
            )
        }
    }

    public func findMemo(memoId: Int64) async throws -> Memo? {
        try await database.perform { connection in
            try connection.queryOne("SELECT \(MEMO_COLUMNS) FROM memo WHERE id = ?", [.integer(memoId)], toMemo)
        }
    }

    public func listMemoUtterances(memoId: Int64) async throws -> [Utterance] {
        try await database.perform { connection in
            try connection.query(
                """
                SELECT \(UTTERANCE_COLUMNS)
                FROM utterance
                JOIN memo_utterance ON memo_utterance.utterance_id = utterance.id
                WHERE memo_utterance.memo_id = ?
                ORDER BY utterance.started_at_ms, utterance.id
                """,
                [.integer(memoId)],
                toUtterance
            )
        }
    }

    public func listUtteranceIds(memoId: Int64) async throws -> [Int64] {
        try await database.perform { connection in
            try connection.query(
                """
                SELECT memo_utterance.utterance_id
                FROM memo_utterance
                JOIN utterance ON utterance.id = memo_utterance.utterance_id
                WHERE memo_utterance.memo_id = ?
                ORDER BY utterance.started_at_ms, utterance.id
                """,
                [.integer(memoId)]
            ) { $0.int64(0) }
        }
    }

    /// [term] は英字だけを小文字にしてから SQL に渡す。SQLite の lower も英字だけを小文字にする。
    public func searchMemos(term: String, fromMillis: Int64, toMillis: Int64, limit: Int) async throws -> [Memo] {
        let key = foldAscii(term)
        return try await database.perform { connection in
            try connection.query(
                """
                SELECT \(MEMO_COLUMNS_OF_MEMO) FROM memo
                WHERE memo.started_at_ms < ?1 AND memo.ended_at_ms >= ?2
                  AND (?3 = '' OR instr(lower(coalesce(memo.title, '')), ?3) > 0
                    OR instr(lower(coalesce(memo.summary, '')), ?3) > 0
                    OR EXISTS (SELECT 1 FROM memo_utterance JOIN utterance ON utterance.id = memo_utterance.utterance_id
                               WHERE memo_utterance.memo_id = memo.id AND instr(lower(utterance.body), ?3) > 0))
                ORDER BY memo.started_at_ms DESC, memo.id DESC LIMIT ?4
                """,
                [.integer(toMillis), .integer(fromMillis), .text(key), .integer(Int64(limit))],
                toMemo
            )
        }
    }

    /// 処理済みの発話はそのままにする。
    private static func markOrganized(_ connection: SQLiteConnection, _ utteranceId: Int64, _ organizedAtMillis: Int64) throws {
        try connection.execute(
            "UPDATE utterance SET organized_at_ms = ? WHERE id = ? AND organized_at_ms IS NULL",
            [.integer(organizedAtMillis), .integer(utteranceId)]
        )
    }
}

private let MEMO_COLUMNS = "id, started_at_ms, ended_at_ms, title, summary, model_version, created_at_ms"

/// 発話と結ぶ問い合わせで、列の名前が重ならないように表の名前を付けた [MEMO_COLUMNS]。
private let MEMO_COLUMNS_OF_MEMO = MEMO_COLUMNS.split(separator: ", ").map { "memo.\($0)" }.joined(separator: ", ")

@Sendable
private func toMemo(_ row: SQLiteRow) throws -> Memo {
    Memo(
        id: row.int64(0),
        startedAtMillis: row.int64(1),
        endedAtMillis: row.int64(2),
        title: row.optionalString(3),
        summary: row.optionalString(4),
        modelVersion: row.string(5),
        createdAtMillis: row.int64(6)
    )
}
