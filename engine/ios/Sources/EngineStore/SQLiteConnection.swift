import Foundation
import SQLite3

/// SQLite の失敗。文言は SQLite のものをそのまま持つ。発話の本文は文言に入らない(値はすべてバインドで渡す)。
public struct SQLiteError: Error, Equatable, CustomStringConvertible {
    /// SQLite の拡張結果コード。
    public let code: Int32
    public let message: String

    public var description: String { "SQLite のエラー(\(code)): \(message)" }
}

/// バインドする値。
enum SQLiteValue {
    case integer(Int64)
    case real(Double)
    case text(String)
    case blob(Data)
    case null
}

extension Optional where Wrapped == Int64 {
    var sqlite: SQLiteValue { map(SQLiteValue.integer) ?? .null }
}

extension Optional where Wrapped == Double {
    var sqlite: SQLiteValue { map(SQLiteValue.real) ?? .null }
}

extension Optional where Wrapped == String {
    var sqlite: SQLiteValue { map(SQLiteValue.text) ?? .null }
}

/// sqlite3 の接続。スレッドをまたいで同時に使わない。[EngineDatabase] が 1 つの直列のキューからだけ使う。
final class SQLiteConnection {
    private let handle: OpaquePointer
    private var statements: [String: OpaquePointer] = [:]
    private var isClosed = false

    init(path: String, flags: Int32) throws {
        var handle: OpaquePointer?
        let result = sqlite3_open_v2(path, &handle, flags, nil)
        guard result == SQLITE_OK, let handle else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "開けません"
            if let handle { sqlite3_close_v2(handle) }
            throw SQLiteError(code: result, message: message)
        }
        self.handle = handle
        sqlite3_extended_result_codes(handle, 1)
    }

    deinit {
        close()
    }

    func close() {
        if isClosed { return }
        isClosed = true
        for statement in statements.values { sqlite3_finalize(statement) }
        statements.removeAll()
        sqlite3_close_v2(handle)
    }

    /// 値を返さない文を、バインドなしで続けて実行する。スキーマの作成と PRAGMA に使う。
    func executeScript(_ sql: String) throws {
        try check(sqlite3_exec(handle, sql, nil, nil, nil))
    }

    /// 1 つの文を実行し、変えた行の数を返す。
    @discardableResult
    func execute(_ sql: String, _ values: [SQLiteValue] = []) throws -> Int {
        try withStatement(sql, values) { statement in
            let result = sqlite3_step(statement)
            guard result == SQLITE_DONE || result == SQLITE_ROW else { throw error(result) }
        }
        return Int(sqlite3_changes(handle))
    }

    /// 行を読む。[read] は行ごとに呼ぶ。
    func query<Row>(_ sql: String, _ values: [SQLiteValue] = [], _ read: (SQLiteRow) throws -> Row) throws -> [Row] {
        try withStatement(sql, values) { statement in
            var rows: [Row] = []
            while true {
                let result = sqlite3_step(statement)
                if result == SQLITE_DONE { break }
                guard result == SQLITE_ROW else { throw error(result) }
                rows.append(try read(SQLiteRow(statement: statement)))
            }
            return rows
        }
    }

    func queryOne<Row>(_ sql: String, _ values: [SQLiteValue] = [], _ read: (SQLiteRow) throws -> Row) throws -> Row? {
        let rows = try query(sql, values, read)
        try requireAtMostOne(rows.count)
        return rows.first
    }

    var lastInsertedId: Int64 { sqlite3_last_insert_rowid(handle) }

    /**
     * [body] を 1 つのトランザクションで行う。投げたら巻き戻して、同じものを投げる。
     *
     * SQLDelight の transaction と同じく、入れ子にはしない(このパッケージの中で入れ子にしていない)。
     * IMMEDIATE で始めて、読んでから書くまでの間にほかの接続に書かれないようにする。
     */
    func transaction<Result>(_ body: () throws -> Result) throws -> Result {
        try executeScript("BEGIN IMMEDIATE")
        do {
            let result = try body()
            try executeScript("COMMIT")
            return result
        } catch {
            // 巻き戻しの失敗より、もとの失敗を伝える
            _ = sqlite3_exec(handle, "ROLLBACK", nil, nil, nil)
            throw error
        }
    }

    private func withStatement<Result>(_ sql: String, _ values: [SQLiteValue], _ body: (OpaquePointer) throws -> Result) throws -> Result {
        let statement = try prepared(sql)
        defer {
            sqlite3_reset(statement)
            sqlite3_clear_bindings(statement)
        }
        for (index, value) in values.enumerated() {
            try bind(value, to: statement, at: Int32(index + 1))
        }
        return try body(statement)
    }

    /// 同じ文は作り直さずに使い回す。
    private func prepared(_ sql: String) throws -> OpaquePointer {
        if let statement = statements[sql] { return statement }
        var statement: OpaquePointer?
        try check(sqlite3_prepare_v3(handle, sql, -1, UInt32(SQLITE_PREPARE_PERSISTENT), &statement, nil))
        guard let statement else { throw SQLiteError(code: SQLITE_MISUSE, message: "文がありません") }
        statements[sql] = statement
        return statement
    }

    private func bind(_ value: SQLiteValue, to statement: OpaquePointer, at index: Int32) throws {
        let result: Int32
        switch value {
        case let .integer(value):
            result = sqlite3_bind_int64(statement, index, value)
        case let .real(value):
            result = sqlite3_bind_double(statement, index, value)
        case let .text(value):
            result = sqlite3_bind_text(statement, index, value, -1, SQLITE_TRANSIENT)
        case let .blob(value):
            result = value.withUnsafeBytes { bytes in
                // 長さ 0 の Data は baseAddress が nil になりうる。nil を渡すと NULL になるので、空の BLOB にする
                if bytes.isEmpty { return sqlite3_bind_zeroblob(statement, index, 0) }
                return sqlite3_bind_blob(statement, index, bytes.baseAddress, Int32(bytes.count), SQLITE_TRANSIENT)
            }
        case .null:
            result = sqlite3_bind_null(statement, index)
        }
        try check(result)
    }

    private func check(_ result: Int32) throws {
        guard result == SQLITE_OK else { throw error(result) }
    }

    private func error(_ result: Int32) -> SQLiteError {
        SQLiteError(code: result, message: String(cString: sqlite3_errmsg(handle)))
    }

    private func requireAtMostOne(_ count: Int) throws {
        // SQLDelight の executeAsOneOrNull と同じく、2 行以上は誤り
        if count > 1 { throw SQLiteError(code: SQLITE_MISUSE, message: "1 行のはずの問い合わせが \(count) 行を返しました") }
    }
}

/// 読んでいる 1 行。列は 0 から数える。
struct SQLiteRow {
    fileprivate let statement: OpaquePointer

    func isNull(_ column: Int32) -> Bool {
        sqlite3_column_type(statement, column) == SQLITE_NULL
    }

    func int64(_ column: Int32) -> Int64 {
        sqlite3_column_int64(statement, column)
    }

    func optionalInt64(_ column: Int32) -> Int64? {
        isNull(column) ? nil : int64(column)
    }

    func double(_ column: Int32) -> Double {
        sqlite3_column_double(statement, column)
    }

    func optionalDouble(_ column: Int32) -> Double? {
        isNull(column) ? nil : double(column)
    }

    func string(_ column: Int32) -> String {
        guard let text = sqlite3_column_text(statement, column) else { return "" }
        let count = Int(sqlite3_column_bytes(statement, column))
        return String(decoding: UnsafeBufferPointer(start: text, count: count), as: UTF8.self)
    }

    func optionalString(_ column: Int32) -> String? {
        isNull(column) ? nil : string(column)
    }

    func data(_ column: Int32) -> Data {
        let count = Int(sqlite3_column_bytes(statement, column))
        guard count > 0, let bytes = sqlite3_column_blob(statement, column) else { return Data() }
        return Data(bytes: bytes, count: count)
    }
}

/// SQLite に値を写させる。Swift から C のマクロ SQLITE_TRANSIENT は見えないので作る。
private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
