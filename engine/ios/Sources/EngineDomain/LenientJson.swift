import Foundation

// Kotlin の LlmOutputParser.kt が使う kotlinx.serialization の
// `Json { isLenient = true; allowTrailingComma = true }.parseToJsonElement` を、同じ寛容さで書いたもの。
// Kotlin のファイルには対応しない。
//
// Foundation の JSONSerialization は、厳密か(既定)、JSON5 でより寛容か(`.json5Allowed`)のどちらかで、
// kotlinx と同じ範囲を受け付けない。数を Double にして書かれた文字列を失うこともある。そこで手で書いた。
//
// kotlinx の挙動として再現したもの:
// - 引用符のない文字列(キーと値)。`{`、`}`、`[`、`]`、`:`、`,`、`"`、`\`、空白、制御文字の手前で終わる
// - 引用符のない `null` だけが null。`true` や数は、書かれたままの文字列の値になる。数としては確かめない
// - 末尾のカンマ。先頭のカンマは受け付けない
// - 空白は ` `、`\t`、`\n`、`\r` だけ
// - 文字列の中の改行などの制御文字はそのまま受け付ける。エスケープは `\"`、`\\`、`\/`、`\b`、`\f`、`\n`、`\r`、`\t`、`\uXXXX`
// - 同じキーが複数あれば後のものを使う
// - 配列は閉じ括弧の後も値が続けば読み続ける(kotlinx の JsonTreeReader と同じ)
//
// 違い: `\uD800` のような対にならないサロゲートは、Swift の String が持てないので U+FFFD になる。

/// 読んだ JSON の値。kotlinx の JsonElement にあたる。
enum LenientJsonElement: Sendable, Hashable {
    case object([String: LenientJsonElement])
    case array([LenientJsonElement])
    /// [content] は文字列の中身か、引用符のない値の書かれたままの文字列。
    case literal(content: String, isString: Bool)
    case null

    var isObject: Bool {
        if case .object = self { return true }
        return false
    }
}

/// 読めなかった。kotlinx の SerializationException にあたる。
struct LenientJsonException: Error {
    let message: String
    let position: Int
}

enum LenientJson {
    static func parse(_ text: String) throws -> LenientJsonElement {
        try parse(Array(text.utf16))
    }

    static func parse(_ source: [UInt16]) throws -> LenientJsonElement {
        var reader = Reader(source: source)
        let element = try reader.read()
        // parseToJsonElement は値の後ろに何か残っていれば失敗する
        if reader.consumeNextToken() != .eof {
            throw LenientJsonException(message: "Expected EOF after parsing", position: reader.position)
        }
        return element
    }
}

private enum TokenClass: Equatable {
    case other
    case string
    case stringEscape
    case whitespace
    case comma
    case colon
    case beginObject
    case endObject
    case beginList
    case endList
    case invalid
    case eof
}

/// kotlinx の CharMappings の charToTokenClass。
private func tokenClass(_ unit: UInt16) -> TokenClass {
    switch unit {
    case 0x09, 0x0A, 0x0D, 0x20: return .whitespace
    case 0x00...0x20: return .invalid
    case 0x22: return .string
    case 0x2C: return .comma
    case 0x3A: return .colon
    case 0x5B: return .beginList
    case 0x5C: return .stringEscape
    case 0x5D: return .endList
    case 0x7B: return .beginObject
    case 0x7D: return .endObject
    default: return .other
    }
}

private struct Reader {
    let source: [UInt16]
    var position = 0

    init(source: [UInt16]) {
        self.source = source
    }

    private func fail(_ message: String) -> LenientJsonException {
        LenientJsonException(message: message, position: position)
    }

    // MARK: 字句

    mutating func skipWhitespaces() -> Int {
        while position < source.count, tokenClass(source[position]) == .whitespace { position += 1 }
        return position
    }

    func peekNextToken() -> TokenClass {
        var current = position
        while current < source.count {
            let token = tokenClass(source[current])
            if token != .whitespace { return token }
            current += 1
        }
        return .eof
    }

    mutating func consumeNextToken() -> TokenClass {
        while position < source.count {
            let token = tokenClass(source[position])
            position += 1
            if token != .whitespace { return token }
        }
        return .eof
    }

    mutating func consumeNextToken(_ expected: TokenClass) throws {
        let token = consumeNextToken()
        if token != expected { throw fail("Expected \(expected), but had \(token)") }
    }

    /// 次が値の始まりになりうるか。kotlinx の canConsumeValue。
    mutating func canConsumeValue() -> Bool {
        let current = skipWhitespaces()
        guard current < source.count else { return false }
        switch source[current] {
        case 0x7D, 0x5D, 0x3A, 0x2C: return false
        default: return true
        }
    }

    /// 引用符のある文字列か、引用符のない文字列を読む。kotlinx の consumeStringLenient。
    mutating func consumeStringLenient() throws -> String {
        let current = skipWhitespaces()
        if current >= source.count { throw fail("EOF") }
        let token = tokenClass(source[current])
        if token == .string { return try consumeString() }
        if token != .other { throw fail("Expected beginning of the string") }
        var end = current
        while end < source.count, tokenClass(source[end]) == .other { end += 1 }
        position = end
        return stringFromUTF16(source[current..<end])
    }

    /// 引用符のある文字列を読む。kotlinx の consumeString。
    mutating func consumeString() throws -> String {
        try consumeNextToken(.string)
        var units: [UInt16] = []
        while true {
            guard position < source.count else { throw fail("Unexpected EOF") }
            let unit = source[position]
            if unit == 0x22 {
                position += 1
                return stringFromUTF16(units)
            }
            if unit == 0x5C {
                position += 1
                try appendEscape(&units)
            } else {
                units.append(unit)
                position += 1
            }
        }
    }

    private mutating func appendEscape(_ units: inout [UInt16]) throws {
        guard position < source.count else { throw fail("Expected escape sequence to continue, got EOF") }
        let unit = source[position]
        position += 1
        switch unit {
        case 0x75:
            // \uXXXX
            guard position + 4 <= source.count else { throw fail("Unexpected EOF during unicode escape") }
            var value: UInt16 = 0
            for offset in 0..<4 {
                guard let digit = hexValue(source[position + offset]) else { throw fail("Invalid toHexChar char") }
                value = value << 4 | digit
            }
            position += 4
            units.append(value)
        case 0x62: units.append(0x08)
        case 0x74: units.append(0x09)
        case 0x6E: units.append(0x0A)
        case 0x66: units.append(0x0C)
        case 0x72: units.append(0x0D)
        case 0x2F, 0x22, 0x5C: units.append(unit)
        default: throw fail("Invalid escaped char")
        }
    }

    private func hexValue(_ unit: UInt16) -> UInt16? {
        switch unit {
        case 0x30...0x39: return unit - 0x30
        case 0x41...0x46: return unit - 0x41 + 10
        case 0x61...0x66: return unit - 0x61 + 10
        default: return nil
        }
    }

    // MARK: 木

    /// kotlinx の JsonTreeReader.read。
    mutating func read() throws -> LenientJsonElement {
        switch peekNextToken() {
        case .string:
            return .literal(content: try consumeStringLenient(), isString: true)
        case .other:
            let content = try consumeStringLenient()
            return content == "null" ? .null : .literal(content: content, isString: false)
        case .beginObject:
            return try readObject()
        case .beginList:
            return try readArray()
        default:
            throw fail("Cannot read Json element because of unexpected token")
        }
    }

    private mutating func readObject() throws -> LenientJsonElement {
        try consumeNextToken(.beginObject)
        var lastToken = TokenClass.beginObject
        if peekNextToken() == .comma { throw fail("Unexpected leading comma") }
        var result: [String: LenientJsonElement] = [:]
        while canConsumeValue() {
            let key = try consumeStringLenient()
            try consumeNextToken(.colon)
            result[key] = try read()
            lastToken = consumeNextToken()
            if lastToken == .comma { continue }
            if lastToken == .endObject { break }
            throw fail("Expected end of the object or comma")
        }
        if lastToken == .beginObject || lastToken == .comma {
            // 空のオブジェクトか、末尾のカンマ
            try consumeNextToken(.endObject)
        }
        return .object(result)
    }

    private mutating func readArray() throws -> LenientJsonElement {
        var lastToken = consumeNextToken()
        if peekNextToken() == .comma { throw fail("Unexpected leading comma") }
        var result: [LenientJsonElement] = []
        while canConsumeValue() {
            result.append(try read())
            lastToken = consumeNextToken()
            if lastToken != .comma, lastToken != .endList {
                throw fail("Expected end of the array or comma")
            }
        }
        if lastToken == .beginList || lastToken == .comma {
            try consumeNextToken(.endList)
        }
        return .array(result)
    }
}
