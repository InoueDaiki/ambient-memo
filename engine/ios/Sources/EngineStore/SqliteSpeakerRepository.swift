import EngineDomain
import Foundation

private let SELF_NAME = "自分"
private let PROVISIONAL_PREFIX = "話者"
private let MILLIS_PER_DAY: Int64 = 86_400_000

/// Android の `SqliteSpeakerRepository` と同じ。話者の表を読み書きする窓口をここに集めている。
public final class SqliteSpeakerRepository: SpeakerRepository, SpeakerDecisionLog {
    private let database: EngineDatabase

    public init(database: EngineDatabase) {
        self.database = database
    }

    public func listSpeakers() async throws -> [Speaker] {
        try await database.perform { try Self.listAll($0) }
    }

    public func listEmbeddings(modelVersion: String, limit: Int) async throws -> [StoredSpeakerEmbedding] {
        try await database.perform { connection in
            try connection.query(
                """
                SELECT speaker_embedding.speaker_id, speaker.is_self, speaker.name_source, speaker_embedding.vector,
                  speaker_embedding.sample_count, speaker_embedding.voiced_ms, speaker_embedding.updated_at_ms
                FROM speaker_embedding
                JOIN speaker ON speaker.id = speaker_embedding.speaker_id
                WHERE speaker_embedding.model_version = ?
                ORDER BY speaker.is_self DESC, speaker_embedding.updated_at_ms DESC, speaker_embedding.speaker_id DESC
                LIMIT ?
                """,
                [.text(modelVersion), .integer(Int64(limit))]
            ) { row in
                StoredSpeakerEmbedding(
                    row.int64(0),
                    row.int64(1) == 1,
                    toFloats(row.data(3)),
                    Int(row.int64(4)),
                    row.int64(5),
                    try SpeakerNameSource(column: row.string(2)),
                    row.int64(6)
                )
            }
        }
    }

    public func saveSelfEmbedding(_ embedding: SpeakerEmbedding, sampleCount: Int, nowMillis: Int64) async throws -> Speaker {
        try await database.transaction { connection in
            let selfSpeaker = try connection.queryOne(
                "SELECT \(SPEAKER_COLUMNS) FROM speaker WHERE is_self = 1",
                [],
                toSpeaker
            ) ?? Self.insertSpeaker(connection, SELF_NAME, .SELF, isSelf: true, nowMillis)
            try Self.save(connection, selfSpeaker.id, embedding, sampleCount, nowMillis, voicedMillis: 0)
            try connection.execute("DELETE FROM self_voice_adaptation")
            return selfSpeaker
        }
    }

    public func createProvisionalSpeaker(_ embedding: SpeakerEmbedding, nowMillis: Int64, voicedMillis: Int64) async throws -> Speaker {
        try await database.transaction { connection in
            let speaker = try Self.insertProvisional(connection, nowMillis)
            try Self.save(connection, speaker.id, embedding, 1, nowMillis, voicedMillis: voicedMillis)
            return speaker
        }
    }

    public func createProvisionalSpeakerWithoutVoice(nowMillis: Int64) async throws -> Speaker {
        try await database.transaction { try Self.insertProvisional($0, nowMillis) }
    }

    public func updateEmbedding(
        speakerId: Int64,
        _ embedding: SpeakerEmbedding,
        sampleCount: Int,
        nowMillis: Int64,
        voicedMillis: Int64
    ) async throws {
        try await database.perform { connection in
            try Self.save(connection, speakerId, embedding, sampleCount, nowMillis, voicedMillis: voicedMillis)
        }
    }

    public func mergeSpeakers(_ merges: [SpeakerMerge], nowMillis: Int64, renumberProvisional: Bool) async throws {
        try await database.transaction { connection in
            for merge in merges { try Self.apply(connection, merge, nowMillis) }
            if renumberProvisional { try Self.renumber(connection) }
        }
    }

    public func resetSpeakers() async throws {
        try await database.transaction { connection in
            try connection.execute("UPDATE utterance SET speaker_id = NULL, speaker_confidence = NULL")
            try connection.execute("DELETE FROM self_voice_adaptation")
            try connection.execute("DELETE FROM speaker_embedding")
            try connection.execute("DELETE FROM speaker")
        }
    }

    public func findSelfAdaptation(modelVersion: String) async throws -> SelfAdaptation? {
        try await database.perform { connection in
            try connection.queryOne(
                "SELECT vector, voiced_ms FROM self_voice_adaptation WHERE model_version = ?",
                [.text(modelVersion)]
            ) { row in SelfAdaptation(toFloats(row.data(0)), row.int64(1), modelVersion) }
        }
    }

    public func saveSelfAdaptation(_ adaptation: SelfAdaptation, nowMillis: Int64) async throws {
        try await database.perform { connection in
            try connection.execute(
                """
                INSERT OR REPLACE INTO self_voice_adaptation (model_version, vector, voiced_ms, updated_at_ms)
                VALUES (?, ?, ?, ?)
                """,
                [.text(adaptation.modelVersion), .blob(toBytes(adaptation.vector)), .integer(adaptation.voicedMillis), .integer(nowMillis)]
            )
        }
    }

    public func setManualName(speakerId: Int64, name: String) async throws -> Bool {
        try await database.transaction { connection in
            try connection.execute(
                "UPDATE speaker SET display_name = ?, name_source = ? WHERE id = ?",
                [.text(name), .text(SpeakerNameSource.MANUAL.column), .integer(speakerId)]
            ) > 0
        }
    }

    public func setInferredName(speakerId: Int64, name: String) async throws -> Bool {
        try await database.transaction { connection in
            // 推定で置き換えてよいのは仮の名前と推定の名前だけ。自分と手動の名前は含めない
            try connection.execute(
                """
                UPDATE speaker
                SET display_name = ?, name_source = ?
                WHERE id = ? AND is_self = 0 AND name_source IN (?, ?)
                """,
                [
                    .text(name),
                    .text(SpeakerNameSource.INFERRED.column),
                    .integer(speakerId),
                    .text(SpeakerNameSource.PROVISIONAL.column),
                    .text(SpeakerNameSource.INFERRED.column),
                ]
            ) > 0
        }
    }

    // MARK: - SpeakerDecisionLog

    public func record(_ samples: [SpeakerDecisionSample], nowMillis: Int64) async throws {
        let dayMillis = floorToDay(nowMillis)
        try await database.transaction { connection in
            for sample in samples {
                let key: [SQLiteValue] = [.integer(dayMillis), .text(sample.decision.column), .integer(Int64(scoreBucket(sample.selfScore)))]
                // Android と同じく、行を用意してから足す(Android 7 の SQLite には UPSERT がないため)
                try connection.execute(
                    """
                    INSERT OR IGNORE INTO speaker_decision_stat (day_ms, decision, bucket, runs, voiced_ms)
                    VALUES (?, ?, ?, 0, 0)
                    """,
                    key
                )
                try connection.execute(
                    """
                    UPDATE speaker_decision_stat
                    SET runs = runs + 1, voiced_ms = voiced_ms + ?
                    WHERE day_ms = ? AND decision = ? AND bucket = ?
                    """,
                    [.integer(sample.voicedMillis)] + key
                )
            }
        }
    }

    public func summarize(fromMillis: Int64) async throws -> [SpeakerDecisionBucket] {
        try await database.perform { connection in
            try connection.query(
                """
                SELECT decision, bucket, SUM(runs) AS runs, SUM(voiced_ms) AS voiced_ms
                FROM speaker_decision_stat
                WHERE day_ms >= ?
                GROUP BY decision, bucket
                ORDER BY decision, bucket
                """,
                [.integer(floorToDay(fromMillis))]
            ) { row in
                SpeakerDecisionBucket(
                    try SpeakerDecision(column: row.string(0)),
                    Int(row.int64(1)),
                    row.optionalInt64(2) ?? 0,
                    row.optionalInt64(3) ?? 0
                )
            }
        }
    }

    public func prune(beforeMillis: Int64) async throws {
        try await database.perform { connection in
            try connection.execute("DELETE FROM speaker_decision_stat WHERE day_ms < ?", [.integer(floorToDay(beforeMillis))])
        }
    }

    // MARK: - トランザクションの中で使う部品

    private static func listAll(_ connection: SQLiteConnection) throws -> [Speaker] {
        try connection.query("SELECT \(SPEAKER_COLUMNS) FROM speaker ORDER BY id", [], toSpeaker)
    }

    /// 統合で話者が消えるので、使われていない最小の番号を探す。番号が増え続けない。
    private static func insertProvisional(_ connection: SQLiteConnection, _ nowMillis: Int64) throws -> Speaker {
        let used = Set(try connection.query("SELECT display_name FROM speaker") { $0.string(0) })
        var number = 1
        while used.contains("\(PROVISIONAL_PREFIX)\(number)") { number += 1 }
        return try insertSpeaker(connection, "\(PROVISIONAL_PREFIX)\(number)", .PROVISIONAL, isSelf: false, nowMillis)
    }

    private static func apply(_ connection: SQLiteConnection, _ merge: SpeakerMerge, _ nowMillis: Int64) throws {
        let isOther = try listAll(connection).contains { $0.id == merge.fromId && !$0.isSelf }
        if !isOther { return }
        try connection.execute(
            "UPDATE utterance SET speaker_id = ?, speaker_confidence = NULL WHERE speaker_id = ?",
            [.integer(merge.intoId), .integer(merge.fromId)]
        )
        try connection.execute("DELETE FROM speaker_embedding WHERE speaker_id = ?", [.integer(merge.fromId)])
        try connection.execute("DELETE FROM speaker WHERE id = ? AND is_self = 0", [.integer(merge.fromId)])
        if let merged = merge.merged {
            try save(connection, merge.intoId, merged.embedding, merged.sampleCount, nowMillis, voicedMillis: merged.voicedMillis)
        }
    }

    /// 仮の話者だけを id の順に、ほかの話者の名前と重ならない「話者N」に付け直す。
    private static func renumber(_ connection: SQLiteConnection) throws {
        let speakers = try listAll(connection)
        let provisional = speakers.filter { $0.nameSource == .PROVISIONAL }
        let taken = Set(speakers.filter { $0.nameSource != .PROVISIONAL }.map(\.displayName))
        var number = 0
        for speaker in provisional {
            repeat { number += 1 } while taken.contains("\(PROVISIONAL_PREFIX)\(number)")
            try connection.execute(
                "UPDATE speaker SET display_name = ? WHERE id = ? AND name_source = ?",
                [.text("\(PROVISIONAL_PREFIX)\(number)"), .integer(speaker.id), .text(SpeakerNameSource.PROVISIONAL.column)]
            )
        }
    }

    private static func insertSpeaker(
        _ connection: SQLiteConnection,
        _ name: String,
        _ source: SpeakerNameSource,
        isSelf: Bool,
        _ nowMillis: Int64
    ) throws -> Speaker {
        try connection.execute(
            "INSERT INTO speaker (display_name, name_source, is_self, created_at_ms) VALUES (?, ?, ?, ?)",
            [.text(name), .text(source.column), .integer(isSelf ? 1 : 0), .integer(nowMillis)]
        )
        return Speaker(connection.lastInsertedId, name, source, isSelf, nowMillis)
    }

    private static func save(
        _ connection: SQLiteConnection,
        _ speakerId: Int64,
        _ embedding: SpeakerEmbedding,
        _ sampleCount: Int,
        _ nowMillis: Int64,
        voicedMillis: Int64
    ) throws {
        try connection.execute(
            """
            INSERT OR REPLACE INTO speaker_embedding (speaker_id, model_version, vector, sample_count, updated_at_ms, voiced_ms)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            [
                .integer(speakerId),
                .text(embedding.modelVersion),
                .blob(toBytes(embedding.vector)),
                .integer(Int64(sampleCount)),
                .integer(nowMillis),
                .integer(voicedMillis),
            ]
        )
    }
}

private let SPEAKER_COLUMNS = "id, display_name, name_source, is_self, created_at_ms"

@Sendable
private func toSpeaker(_ row: SQLiteRow) throws -> Speaker {
    Speaker(row.int64(0), row.string(1), try SpeakerNameSource(column: row.string(2)), row.int64(3) == 1, row.int64(4))
}

/// UTC の日の始まり。Java の Math.floorDiv と同じく、負の時刻も前の日に丸める。
private func floorToDay(_ millis: Int64) -> Int64 {
    let quotient = millis / MILLIS_PER_DAY
    let day = (millis % MILLIS_PER_DAY != 0 && millis < 0) ? quotient - 1 : quotient
    return day * MILLIS_PER_DAY
}

/// 特徴量はリトルエンディアンの 32 ビット浮動小数の列で持つ。Android と同じ並び。
func toBytes(_ vector: [Float]) -> Data {
    var data = Data(capacity: vector.count * MemoryLayout<UInt32>.size)
    for value in vector {
        withUnsafeBytes(of: value.bitPattern.littleEndian) { data.append(contentsOf: $0) }
    }
    return data
}

func toFloats(_ data: Data) -> [Float] {
    let size = MemoryLayout<UInt32>.size
    return data.withUnsafeBytes { bytes in
        // Java の asFloatBuffer と同じく、4 バイトに満たない端は捨てる
        (0..<(bytes.count / size)).map { index in
            Float(bitPattern: UInt32(littleEndian: bytes.loadUnaligned(fromByteOffset: index * size, as: UInt32.self)))
        }
    }
}

// enum の名前を変えても保存済みの値が読めるように、列の値は対応表で固定する。Android と同じ値
extension SpeakerNameSource {
    var column: String {
        switch self {
        case .SELF: "self"
        case .PROVISIONAL: "provisional"
        case .INFERRED: "inferred"
        case .MANUAL: "manual"
        }
    }

    init(column: String) throws {
        guard let source = Self.allCases.first(where: { $0.column == column }) else {
            throw IllegalStateException("未知の名前の由来です: \(column)")
        }
        self = source
    }
}

extension SpeakerDecision {
    var column: String {
        switch self {
        case .SELF: "self"
        case .OTHER: "other"
        case .UNKNOWN: "unknown"
        }
    }

    init(column: String) throws {
        guard let decision = Self.allCases.first(where: { $0.column == column }) else {
            throw IllegalStateException("未知の判定です: \(column)")
        }
        self = decision
    }
}
