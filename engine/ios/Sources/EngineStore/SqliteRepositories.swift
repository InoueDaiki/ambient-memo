import EngineDomain
import Foundation

/// Android の `SqliteUtteranceRepository` と同じ。
public final class SqliteUtteranceRepository: UtteranceRepository {
    private let database: EngineDatabase

    public init(database: EngineDatabase) {
        self.database = database
    }

    public func insert(_ utterance: NewUtterance) async throws -> Utterance {
        // Kotlin と同じく、まとめて保存の 1 件として行う
        guard let saved = try await insertAll([utterance]).first else {
            throw IllegalStateException("保存した発話がありません")
        }
        return saved
    }

    public func insertAll(_ utterances: [NewUtterance]) async throws -> [Utterance] {
        try await database.transaction { connection in
            try utterances.map { utterance in
                try connection.execute(
                    """
                    INSERT INTO utterance (started_at_ms, ended_at_ms, body, confidence, created_at_ms, speaker_id, speaker_confidence)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    [
                        .integer(utterance.startedAtMillis),
                        .integer(utterance.endedAtMillis),
                        .text(utterance.text),
                        utterance.confidence.sqlite,
                        .integer(utterance.createdAtMillis),
                        utterance.speakerId.sqlite,
                        utterance.speakerConfidence.sqlite,
                    ]
                )
                return Utterance(
                    id: connection.lastInsertedId,
                    startedAtMillis: utterance.startedAtMillis,
                    endedAtMillis: utterance.endedAtMillis,
                    text: utterance.text,
                    confidence: utterance.confidence,
                    createdAtMillis: utterance.createdAtMillis,
                    speakerId: utterance.speakerId,
                    speakerConfidence: utterance.speakerConfidence
                )
            }
        }
    }

    public func listOverlapping(fromMillis: Int64, toMillis: Int64) async throws -> [Utterance] {
        try await database.perform { connection in
            try connection.query(
                """
                SELECT \(UTTERANCE_COLUMNS)
                FROM utterance
                WHERE started_at_ms < ? AND ended_at_ms > ?
                ORDER BY started_at_ms, id
                """,
                [.integer(toMillis), .integer(fromMillis)],
                toUtterance
            )
        }
    }

    public func listRecent(limit: Int) async throws -> [Utterance] {
        try await database.perform { connection in
            try connection.query(
                """
                SELECT \(UTTERANCE_COLUMNS)
                FROM utterance
                ORDER BY started_at_ms DESC, id DESC
                LIMIT ?
                """,
                [.integer(Int64(limit))],
                toUtterance
            )
        }
    }

    public func find(id: Int64) async throws -> Utterance? {
        try await database.perform { connection in
            try connection.queryOne("SELECT \(UTTERANCE_COLUMNS) FROM utterance WHERE id = ?", [.integer(id)], toUtterance)
        }
    }

    public func setSpeaker(id: Int64, speakerId: Int64?) async throws -> Bool {
        try await database.transaction { connection in
            // 手で直した話者。類似度はもとの判定の値なので残さない
            try connection.execute(
                "UPDATE utterance SET speaker_id = ?, speaker_confidence = NULL WHERE id = ?",
                [speakerId.sqlite, .integer(id)]
            ) > 0
        }
    }

    public func countBySpeaker() async throws -> [Int64: Int] {
        let rows = try await database.perform { connection in
            try connection.query(
                "SELECT speaker_id, count(*) FROM utterance WHERE speaker_id IS NOT NULL GROUP BY speaker_id"
            ) { row in (row.int64(0), Int(row.int64(1))) }
        }
        return Dictionary(rows, uniquingKeysWith: { _, last in last })
    }
}

/// 発話を読む列。[toUtterance] はこの順に読む。読み出す場所はすべてここを通す。
let UTTERANCE_COLUMNS = """
    utterance.id, utterance.started_at_ms, utterance.ended_at_ms, utterance.body, utterance.confidence, \
    utterance.created_at_ms, utterance.speaker_id, utterance.speaker_confidence
    """

@Sendable
func toUtterance(_ row: SQLiteRow) -> Utterance {
    Utterance(
        id: row.int64(0),
        startedAtMillis: row.int64(1),
        endedAtMillis: row.int64(2),
        text: row.string(3),
        confidence: row.optionalDouble(4),
        createdAtMillis: row.int64(5),
        speakerId: row.optionalInt64(6),
        speakerConfidence: row.optionalDouble(7)
    )
}

/// Android の `SqliteCaptureGapRepository` と同じ。
public final class SqliteCaptureGapRepository: CaptureGapRepository {
    private let database: EngineDatabase

    public init(database: EngineDatabase) {
        self.database = database
    }

    public func insert(_ gap: CaptureGap) async throws {
        try await database.perform { connection in
            try connection.execute(
                "INSERT INTO capture_gap (started_at_ms, ended_at_ms, reason) VALUES (?, ?, ?)",
                [.integer(gap.startedAtMillis), .integer(gap.endedAtMillis), .text(gap.reason.column)]
            )
            return ()
        }
    }

    public func listOverlapping(fromMillis: Int64, toMillis: Int64) async throws -> [CaptureGap] {
        try await database.perform { connection in
            try connection.query(
                """
                SELECT started_at_ms, ended_at_ms, reason
                FROM capture_gap
                WHERE started_at_ms < ? AND ended_at_ms > ?
                ORDER BY started_at_ms, id
                """,
                [.integer(toMillis), .integer(fromMillis)]
            ) { row in
                try CaptureGap(row.int64(0), row.int64(1), try CaptureGapReason(column: row.string(2)))
            }
        }
    }
}

// enum の名前を変えても保存済みの値が読めるように、列の値は対応表で固定する。Android と同じ値
extension CaptureGapReason {
    var column: String {
        switch self {
        case .PROCESSING_BACKLOG: "processing_backlog"
        case .PROCESSING_FAILED: "processing_failed"
        case .SOURCE_DISCONTINUITY: "source_discontinuity"
        case .PIPELINE_STOPPED: "pipeline_stopped"
        }
    }

    init(column: String) throws {
        guard let reason = Self.allCases.first(where: { $0.column == column }) else {
            throw IllegalStateException("未知の欠落の理由です: \(column)")
        }
        self = reason
    }
}
