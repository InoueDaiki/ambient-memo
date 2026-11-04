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
                INSERT INTO memo (started_at_ms, ended_at_ms, title, summary, todo_extraction, model_version, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                [
                    .integer(memo.startedAtMillis),
                    .integer(memo.endedAtMillis),
                    memo.title.sqlite,
                    memo.summary.sqlite,
                    .text(memo.todoExtraction.column),
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
            for todo in memo.todos {
                try connection.execute(
                    """
                    INSERT INTO todo (memo_id, task, title, kind, due_date, status, created_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    [
                        .integer(memoId),
                        .text(todo.task),
                        .text(todo.title),
                        .text(todo.kind.column),
                        todo.dueDate.map(\.description).sqlite,
                        .text(todo.status.column),
                        .integer(memo.createdAtMillis),
                    ]
                )
            }
            return Memo(
                id: memoId,
                startedAtMillis: memo.startedAtMillis,
                endedAtMillis: memo.endedAtMillis,
                title: memo.title,
                summary: memo.summary,
                todoExtraction: memo.todoExtraction,
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

    public func listTodos(memoId: Int64) async throws -> [Todo] {
        try await database.perform { connection in
            try connection.query("SELECT \(TODO_COLUMNS) FROM todo WHERE memo_id = ? ORDER BY id", [.integer(memoId)], toTodo)
        }
    }

    public func listTodosByPriority(limit: Int) async throws -> [Todo] {
        try await database.perform { connection in
            try connection.query(
                """
                SELECT \(TODO_COLUMNS)
                FROM todo
                ORDER BY status = 'done', due_date IS NULL, due_date, created_at_ms DESC, id
                LIMIT ?
                """,
                [.integer(Int64(limit))],
                toTodo
            )
        }
    }

    public func completeTodo(todoId: Int64, completedAtMillis: Int64) async throws -> TodoCompletion {
        try await database.transaction { connection in
            let status = try connection.queryOne("SELECT status FROM todo WHERE id = ?", [.integer(todoId)]) {
                try TodoStatus(column: $0.string(0))
            }
            switch status {
            case nil:
                return .NOT_FOUND
            case .DONE:
                return .ALREADY_DONE
            default:
                // 完了の前の状態は、取り消しのために残す
                try connection.execute(
                    """
                    UPDATE todo
                    SET completed_from_status = status, status = ?, completed_at_ms = ?
                    WHERE id = ?
                    """,
                    [.text(TodoStatus.DONE.column), .integer(completedAtMillis), .integer(todoId)]
                )
                return .COMPLETED
            }
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

private let MEMO_COLUMNS = "id, started_at_ms, ended_at_ms, title, summary, todo_extraction, model_version, created_at_ms"

@Sendable
private func toMemo(_ row: SQLiteRow) throws -> Memo {
    Memo(
        id: row.int64(0),
        startedAtMillis: row.int64(1),
        endedAtMillis: row.int64(2),
        title: row.optionalString(3),
        summary: row.optionalString(4),
        todoExtraction: try TodoExtraction(column: row.string(5)),
        modelVersion: row.string(6),
        createdAtMillis: row.int64(7)
    )
}

/// completed_from_status は取り消しのための値で、domain へは渡さないので読まない。
private let TODO_COLUMNS = "id, memo_id, task, title, kind, due_date, status, created_at_ms, completed_at_ms"

@Sendable
private func toTodo(_ row: SQLiteRow) throws -> Todo {
    Todo(
        id: row.int64(0),
        memoId: row.int64(1),
        task: row.string(2),
        title: row.string(3),
        kind: try TodoKind(column: row.string(4)),
        dueDate: try row.optionalString(5).map(toCalendarDate),
        status: try TodoStatus(column: row.string(6)),
        createdAtMillis: row.int64(7),
        completedAtMillis: row.optionalInt64(8)
    )
}

private func toCalendarDate(_ text: String) throws -> CalendarDate {
    guard let date = CalendarDate.parse(text) else { throw IllegalStateException("読めない日付です: \(text)") }
    return date
}

// enum の名前を変えても保存済みの値が読めるように、列の値は対応表で固定する。Android と同じ値
extension TodoExtraction {
    var column: String {
        switch self {
        case .COMPLETE: "complete"
        case .INCOMPLETE: "incomplete"
        }
    }

    init(column: String) throws {
        guard let value = Self.allCases.first(where: { $0.column == column }) else {
            throw IllegalStateException("未知の抽出の状態です: \(column)")
        }
        self = value
    }
}

extension TodoKind {
    var column: String {
        switch self {
        case .TODO: "todo"
        case .REMINDER: "reminder"
        }
    }

    init(column: String) throws {
        guard let value = Self.allCases.first(where: { $0.column == column }) else {
            throw IllegalStateException("未知の ToDo の種類です: \(column)")
        }
        self = value
    }
}

extension TodoStatus {
    var column: String {
        switch self {
        case .CANDIDATE: "candidate"
        case .OPEN: "open"
        case .DONE: "done"
        }
    }

    init(column: String) throws {
        guard let value = Self.allCases.first(where: { $0.column == column }) else {
            throw IllegalStateException("未知の ToDo の状態です: \(column)")
        }
        self = value
    }
}
