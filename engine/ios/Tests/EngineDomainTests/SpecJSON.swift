@testable import EngineDomain
import Foundation

// 共通テストの JSON を読み書きする道具。比べ方は engine/spec/README.md の決まりに合わせる。
//
// Foundation の JSONSerialization は文字列の中の U+FEFF を黙って消し、JSONDecoder は数の書き方を残さない。
// 数を Float として文字列から直接読む決まりがあるので、小さな読み取りを自分で書き、数は書かれたままの文字列で持つ。

/// 任意の JSON。数は書かれたままの文字列で持つ。
enum SpecJSON: Sendable, CustomStringConvertible {
    case null
    case bool(Bool)
    case number(String)
    case string(String)
    case array([SpecJSON])
    case object([String: SpecJSON])

    var description: String {
        switch self {
        case .null: return "null"
        case let .bool(value): return value ? "true" : "false"
        case let .number(text): return text
        case let .string(text): return quoted(text)
        case let .array(values): return "[" + values.map(\.description).joined(separator: ",") + "]"
        case let .object(values):
            let pairs = values.keys.sorted().map { quoted($0) + ":" + values[$0]!.description }
            return "{" + pairs.joined(separator: ",") + "}"
        }
    }

    /// Kotlin の `jsonPrimitive.content`。文字列はそのまま、数と真偽は書いた文字。
    var content: String {
        get throws {
            switch self {
            case let .string(text): return text
            case let .number(text): return text
            case let .bool(value): return value ? "true" : "false"
            case .null: return "null"
            default: throw SpecError("基本型ではありません: \(self)")
            }
        }
    }

    var objectValue: [String: SpecJSON] {
        get throws {
            guard case let .object(values) = self else { throw SpecError("オブジェクトではありません: \(self)") }
            return values
        }
    }

    var arrayValue: [SpecJSON] {
        get throws {
            guard case let .array(values) = self else { throw SpecError("配列ではありません: \(self)") }
            return values
        }
    }

    var isNull: Bool {
        if case .null = self { return true }
        return false
    }
}

private func quoted(_ text: String) -> String {
    var result = "\""
    for scalar in text.unicodeScalars {
        switch scalar {
        case "\"": result += "\\\""
        case "\\": result += "\\\\"
        case "\n": result += "\\n"
        case "\r": result += "\\r"
        case "\t": result += "\\t"
        default:
            if scalar.value < 0x20 || scalar.value == 0xFEFF {
                result += String(format: "\\u%04X", scalar.value)
            } else {
                result.unicodeScalars.append(scalar)
            }
        }
    }
    return result + "\""
}

/// 実行役の書き間違いや、ケースの形の誤り。テストの失敗にする。
struct SpecError: Error, CustomStringConvertible {
    let description: String

    init(_ description: String) {
        self.description = description
    }
}

/// Fake が投げる、種類の名前の付いた失敗。実行役はこれを捕まえて種類を結果に書く。
struct SpecFailure: Error {
    let kind: String

    init(_ kind: String) {
        self.kind = kind
    }
}

// MARK: - 読み取り

/// JSON の文字列を読む。文字列の中の文字は U+FEFF を含めてそのまま残す。
func parseSpecJSON(_ text: String) throws -> SpecJSON {
    var parser = SpecJSONParser(Array(text.unicodeScalars))
    parser.skipWhitespace()
    let value = try parser.value()
    parser.skipWhitespace()
    guard parser.isAtEnd else { throw SpecError("JSON の後ろに余計な文字があります") }
    return value
}

private struct SpecJSONParser {
    private let scalars: [Unicode.Scalar]
    private var index = 0

    init(_ scalars: [Unicode.Scalar]) {
        self.scalars = scalars
    }

    var isAtEnd: Bool { index >= scalars.count }

    mutating func skipWhitespace() {
        while !isAtEnd, [" ", "\n", "\r", "\t"].contains(scalars[index]) { index += 1 }
    }

    private func peek() throws -> Unicode.Scalar {
        guard !isAtEnd else { throw SpecError("JSON が途中で終わっています") }
        return scalars[index]
    }

    private mutating func expect(_ literal: String) throws {
        for scalar in literal.unicodeScalars {
            guard try peek() == scalar else { throw SpecError("JSON に \(literal) がありません") }
            index += 1
        }
    }

    mutating func value() throws -> SpecJSON {
        switch try peek() {
        case "{": return try object()
        case "[": return try array()
        case "\"": return .string(try string())
        case "t": try expect("true"); return .bool(true)
        case "f": try expect("false"); return .bool(false)
        case "n": try expect("null"); return .null
        default: return try number()
        }
    }

    private mutating func object() throws -> SpecJSON {
        try expect("{")
        var values: [String: SpecJSON] = [:]
        skipWhitespace()
        if try peek() == "}" {
            index += 1
            return .object(values)
        }
        while true {
            skipWhitespace()
            let key = try string()
            skipWhitespace()
            try expect(":")
            skipWhitespace()
            guard values.updateValue(try value(), forKey: key) == nil else { throw SpecError("キーが重なっています: \(key)") }
            skipWhitespace()
            if try peek() == "," {
                index += 1
                continue
            }
            try expect("}")
            return .object(values)
        }
    }

    private mutating func array() throws -> SpecJSON {
        try expect("[")
        var values: [SpecJSON] = []
        skipWhitespace()
        if try peek() == "]" {
            index += 1
            return .array(values)
        }
        while true {
            skipWhitespace()
            values.append(try value())
            skipWhitespace()
            if try peek() == "," {
                index += 1
                continue
            }
            try expect("]")
            return .array(values)
        }
    }

    private mutating func string() throws -> String {
        try expect("\"")
        var units: [UInt16] = []
        while true {
            let scalar = try peek()
            index += 1
            switch scalar {
            case "\"":
                return String(decoding: units, as: UTF16.self)
            case "\\":
                let escaped = try peek()
                index += 1
                switch escaped {
                case "\"": units.append(0x22)
                case "\\": units.append(0x5C)
                case "/": units.append(0x2F)
                case "b": units.append(0x08)
                case "f": units.append(0x0C)
                case "n": units.append(0x0A)
                case "r": units.append(0x0D)
                case "t": units.append(0x09)
                case "u":
                    guard index + 4 <= scalars.count,
                          let unit = UInt16(String(String.UnicodeScalarView(scalars[index..<index + 4])), radix: 16)
                    else { throw SpecError("\\u の後ろが 16 進の 4 桁ではありません") }
                    index += 4
                    units.append(unit)
                default:
                    throw SpecError("知らないエスケープです: \\\(escaped)")
                }
            default:
                units.append(contentsOf: String(scalar).utf16)
            }
        }
    }

    private mutating func number() throws -> SpecJSON {
        let start = index
        while !isAtEnd, "+-0123456789.eE".unicodeScalars.contains(scalars[index]) { index += 1 }
        let text = String(String.UnicodeScalarView(scalars[start..<index]))
        guard !text.isEmpty, Double(text) != nil else { throw SpecError("JSON の値ではありません: \(text)") }
        return .number(text)
    }
}

// MARK: - 比べ方

private let NUMBER_TOLERANCE = 1e-6

/// [expected] と [actual] の違いを、場所の付いた文で返す。同じなら空。
func jsonMismatches(_ expected: SpecJSON, _ actual: SpecJSON, _ path: String = "$") -> [String] {
    switch (expected, actual) {
    case (.null, .null):
        return []
    case (.null, _):
        return ["\(path): null のはずが \(actual)"]
    case let (.string(first), .string(second)):
        // Swift の String の == は正規化して比べるので、Kotlin と同じく UTF-16 の並びで比べる
        return first.utf16.elementsEqual(second.utf16) ? [] : ["\(path): \(expected) のはずが \(actual)"]
    case let (.bool(first), .bool(second)):
        return first == second ? [] : ["\(path): \(expected) のはずが \(actual)"]
    case let (.number(first), .number(second)):
        guard let first = Double(first), let second = Double(second), abs(first - second) <= NUMBER_TOLERANCE else {
            return ["\(path): \(expected) のはずが \(actual)"]
        }
        return []
    case (.string, _), (.bool, _), (.number, _):
        return ["\(path): \(expected) のはずが \(actual)"]
    case let (.array(first), .array(second)):
        guard first.count == second.count else {
            return ["\(path): 要素は \(first.count) 個のはずが \(second.count) 個"]
        }
        return first.indices.flatMap { jsonMismatches(first[$0], second[$0], "\(path)[\($0)]") }
    case (.array, _):
        return ["\(path): 配列のはずが \(actual)"]
    case let (.object(first), .object(second)):
        guard Set(first.keys) == Set(second.keys) else {
            return ["\(path): キーは \(first.keys.sorted()) のはずが \(second.keys.sorted())"]
        }
        return first.keys.sorted().flatMap { jsonMismatches(first[$0]!, second[$0]!, "\(path).\($0)") }
    case (.object, _):
        return ["\(path): オブジェクトのはずが \(actual)"]
    }
}

// MARK: - 書き出し

/// JSON にできる値。null、文字列、数、真偽、列挙、リスト、JSON を受け付ける。特徴量は [vectorJSON] で書く。
protocol SpecJSONConvertible {
    var specJSON: SpecJSON { get }
}

extension SpecJSON: SpecJSONConvertible {
    var specJSON: SpecJSON { self }
}

extension String: SpecJSONConvertible {
    var specJSON: SpecJSON { .string(self) }
}

extension Bool: SpecJSONConvertible {
    var specJSON: SpecJSON { .bool(self) }
}

extension Int: SpecJSONConvertible {
    var specJSON: SpecJSON { .number(String(self)) }
}

extension Int64: SpecJSONConvertible {
    var specJSON: SpecJSON { .number(String(self)) }
}

extension Double: SpecJSONConvertible {
    var specJSON: SpecJSON { .number(String(self)) }
}

/// Kotlin の JsonPrimitive(Float) と同じく、Float の短い 10 進の表記を数にする。
extension Float: SpecJSONConvertible {
    var specJSON: SpecJSON { .number(String(self)) }
}

extension Optional: SpecJSONConvertible where Wrapped: SpecJSONConvertible {
    var specJSON: SpecJSON { map(\.specJSON) ?? .null }
}

extension Array: SpecJSONConvertible where Element: SpecJSONConvertible {
    var specJSON: SpecJSON { .array(map(\.specJSON)) }
}

/// 列挙は Kotlin の名前の文字列にする。
protocol SpecEnum: SpecJSONConvertible, RawRepresentable where RawValue == String {}

extension SpecEnum {
    var specJSON: SpecJSON { .string(rawValue) }
}

extension CaptureGapReason: SpecEnum {}
extension SpeakerNameSource: SpecEnum {}
extension SegmentOutcome: SpecEnum {}
extension OrganizeStopReason: SpecEnum {}
extension EnrollmentOutcome: SpecEnum {}
extension OrganizeOutcome: SpecEnum {}
extension AnswerOutcome: SpecEnum {}
extension AskPhase: SpecEnum {}

func obj(_ pairs: KeyValuePairs<String, any SpecJSONConvertible>) -> SpecJSON {
    var values: [String: SpecJSON] = [:]
    for (key, value) in pairs { values[key] = value.specJSON }
    return .object(values)
}

func json(_ value: any SpecJSONConvertible) -> SpecJSON {
    value.specJSON
}

/// 特徴量は 0 でない要素だけを `{"size": 次元, "values": {"添字": 値}}` の形で書く。
func vectorJSON(_ vector: [Float]) -> SpecJSON {
    var values: [String: SpecJSON] = [:]
    for (index, value) in vector.enumerated() where value != 0 { values[String(index)] = value.specJSON }
    return obj(["size": vector.count, "values": SpecJSON.object(values)])
}

func vectorJSON(_ vector: [Float]?) -> SpecJSON {
    vector.map { vectorJSON($0) } ?? .null
}

/// 例外を確かめるケースの期待の形。
func errorJSON(_ kind: String) -> SpecJSON {
    obj(["error": kind])
}

// MARK: - 入力の読み取り

typealias SpecObject = [String: SpecJSON]

extension Dictionary where Key == String, Value == SpecJSON {
    func value(_ key: String) throws -> SpecJSON {
        guard let value = self[key] else { throw SpecError("\(key) がありません") }
        return value
    }

    /// キーがないか null なら nil。
    func present(_ key: String) -> SpecJSON? {
        guard let value = self[key], !value.isNull else { return nil }
        return value
    }

    func str(_ key: String) throws -> String {
        try value(key).content
    }

    func strOrNull(_ key: String) throws -> String? {
        try present(key)?.content
    }

    func strOr(_ key: String, _ defaultValue: String) throws -> String {
        try strOrNull(key) ?? defaultValue
    }

    func int(_ key: String) throws -> Int {
        try parseInt(value(key), key)
    }

    func intOr(_ key: String, _ defaultValue: Int) throws -> Int {
        try self[key].map { try parseInt($0, key) } ?? defaultValue
    }

    func long(_ key: String) throws -> Int64 {
        try parseLong(value(key), key)
    }

    func longOr(_ key: String, _ defaultValue: Int64) throws -> Int64 {
        try self[key].map { try parseLong($0, key) } ?? defaultValue
    }

    func longOrNull(_ key: String) throws -> Int64? {
        try present(key).map { try parseLong($0, key) }
    }

    func double(_ key: String) throws -> Double {
        try parseDouble(value(key), key)
    }

    func doubleOrNull(_ key: String) throws -> Double? {
        try present(key).map { try parseDouble($0, key) }
    }

    func bool(_ key: String) throws -> Bool {
        try parseBool(value(key), key)
    }

    func boolOr(_ key: String, _ defaultValue: Bool) throws -> Bool {
        try self[key].map { try parseBool($0, key) } ?? defaultValue
    }

    func objOrNull(_ key: String) throws -> SpecObject? {
        try present(key)?.objectValue
    }

    func obj(_ key: String) throws -> SpecObject {
        try value(key).objectValue
    }

    func objects(_ key: String) throws -> [SpecObject] {
        try self[key]?.arrayValue.map { try $0.objectValue } ?? []
    }

    func strings(_ key: String) throws -> [String] {
        try self[key]?.arrayValue.map { try $0.content } ?? []
    }

    /// Kotlin の `enumValueOf`。知らない名前は [IllegalArgumentException] にする。
    func enumValue<T: RawRepresentable>(_ key: String, _: T.Type = T.self) throws -> T where T.RawValue == String {
        let name = try str(key)
        guard let value = T(rawValue: name) else { throw IllegalArgumentException("知らない名前です: \(name)") }
        return value
    }

    /// 知らないキーがあれば失敗にする。書き間違いを、既定値のまま通さないため。
    func requireKeys(_ allowed: Set<String>, _ what: String) throws {
        let unknown = Set(keys).subtracting(allowed)
        // Kotlin の require と同じく IllegalArgumentException にする
        guard unknown.isEmpty else { throw IllegalArgumentException("\(what) に知らないキーがあります: \(unknown.sorted())") }
    }
}

private func parseInt(_ value: SpecJSON, _ key: String) throws -> Int {
    guard let parsed = Int32(try value.content) else { throw SpecError("\(key) は Int ではありません: \(value)") }
    return Int(parsed)
}

private func parseLong(_ value: SpecJSON, _ key: String) throws -> Int64 {
    guard let parsed = Int64(try value.content) else { throw SpecError("\(key) は Long ではありません: \(value)") }
    return parsed
}

private func parseDouble(_ value: SpecJSON, _ key: String) throws -> Double {
    guard let parsed = Double(try value.content) else { throw SpecError("\(key) は数ではありません: \(value)") }
    return parsed
}

private func parseBool(_ value: SpecJSON, _ key: String) throws -> Bool {
    switch try value.content {
    case "true": return true
    case "false": return false
    default: throw SpecError("\(key) は真偽ではありません: \(value)")
    }
}

/// 配列の要素など、キーのない値を Int として読む。
func specInt(_ value: SpecJSON) throws -> Int {
    try parseInt(value, "値")
}

/// 配列の要素など、キーのない値を Long として読む。
func specLong(_ value: SpecJSON) throws -> Int64 {
    try parseLong(value, "値")
}

/// Kotlin の `content.toFloat()`。文字列から直接 Float にする。
func parseFloat(_ value: SpecJSON) throws -> Float {
    guard let parsed = Float(try value.content) else { throw SpecError("Float ではありません: \(value)") }
    return parsed
}

/**
 * 特徴量を読む。数の配列か、`{"size": 次元, "values": {"添字": 値}, "normalize": true}` の形を受け付ける。
 * 後者で書かない要素は 0。`normalize` が true なら長さ 1 に正規化する。値は文字列から直接 Float に読む。
 */
func toVector(_ value: SpecJSON) throws -> [Float] {
    switch value {
    case let .array(values):
        return try values.map(parseFloat)
    case let .object(values):
        var vector = [Float](repeating: 0, count: try values.int("size"))
        for (index, element) in try values.obj("values") {
            guard let position = Int(index) else { throw SpecError("添字ではありません: \(index)") }
            vector[position] = try parseFloat(element)
        }
        guard try values.boolOr("normalize", false) else { return vector }
        guard let normalized = normalizedOrNull(vector) else { throw SpecError("正規化できない特徴量です") }
        return normalized
    default:
        throw SpecError("特徴量ではありません: \(value)")
    }
}

/// 値をロックで守る。Fake の中で受け取ったものを数えるのに使う。
final class SpecLocked<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Value

    init(_ value: Value) {
        self.value = value
    }

    func withLock<Result>(_ body: (inout Value) throws -> Result) rethrows -> Result {
        lock.lock()
        defer { lock.unlock() }
        return try body(&value)
    }

    var current: Value { withLock { $0 } }
}

extension SpecJSON: ExpressibleByStringLiteral {
    init(stringLiteral value: String) {
        self = .string(value)
    }
}
