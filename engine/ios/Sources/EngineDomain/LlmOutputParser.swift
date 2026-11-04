import Foundation

/// LLM が出した ToDo の1件。値はまだ検証していない。
struct RawTodo: Sendable, Hashable {
    let task: String
    let kind: String?
    let due: String?

    init(_ task: String, _ kind: String?, _ due: String?) {
        self.task = task
        self.kind = kind
        self.due = due
    }
}

/// [isComplete] が false なら、[items] は読めた分だけで、欠けているかもしれない。
struct ParsedTodos: Sendable, Hashable {
    let items: [RawTodo]
    let isComplete: Bool

    init(_ items: [RawTodo], isComplete: Bool) {
        self.items = items
        self.isComplete = isComplete
    }
}

// Kotlin は次の正規表現を使う。Swift では同じ判定になるように手で書いた。
// Swift の Regex は書記素で、NSRegularExpression は `\s` と `\d` が Unicode で、どちらも Java と合わないため。
// - THINK_BLOCK `<think>.*?(</think>|\z)`(DOT_MATCHES_ALL)
// - CODE_FENCE_LINE `^\s*```.*$`(MULTILINE)
// - BULLET `^\s*[-*・]\s*`
private let THINK_OPEN = Array("<think>".utf16)
private let THINK_CLOSE = Array("</think>".utf16)
private let CODE_FENCE = Array("```".utf16)
private let BULLET_MARKS = Set("-*・".utf16)
private let JSON_START = Set("{[".utf16)
private let ITEMS_KEY = "items"

/// Java の正規表現の行の終わり(UNIX_LINES なし)。
private func isLineTerminator(_ unit: UInt16) -> Bool {
    unit == 0x0A || unit == 0x0D || unit == 0x85 || unit == 0x2028 || unit == 0x2029
}

private func matches(_ units: [UInt16], _ pattern: [UInt16], at index: Int) -> Bool {
    index + pattern.count <= units.count && Array(units[index..<index + pattern.count]) == pattern
}

/// Kotlin の `replace(THINK_BLOCK, "")`。閉じていない `<think>` は終わりまで消す。
private func removingThinkBlocks(_ units: [UInt16]) -> [UInt16] {
    var result: [UInt16] = []
    var index = 0
    while index < units.count {
        if matches(units, THINK_OPEN, at: index) {
            var end = index + THINK_OPEN.count
            while end < units.count, !matches(units, THINK_CLOSE, at: end) { end += 1 }
            index = end < units.count ? end + THINK_CLOSE.count : units.count
        } else {
            result.append(units[index])
            index += 1
        }
    }
    return result
}

/// Java の MULTILINE の `^`。入力の終わりでは合わず、`\r\n` の間でも合わない。
private func isLineStart(_ units: [UInt16], _ index: Int) -> Bool {
    if index >= units.count { return false }
    if index == 0 { return true }
    let previous = units[index - 1]
    return isLineTerminator(previous) && !(previous == 0x0D && units[index] == 0x0A)
}

/// Kotlin の `replace(CODE_FENCE_LINE, "")`。`\s*` は改行も含むので、前の空行ごと消す。
private func removingCodeFenceLines(_ units: [UInt16]) -> [UInt16] {
    var result: [UInt16] = []
    var index = 0
    while index < units.count {
        if isLineStart(units, index) {
            var fence = index
            while fence < units.count, isRegexSpace(units[fence]) { fence += 1 }
            if matches(units, CODE_FENCE, at: fence) {
                // `.*$`: 行の終わりの手前までを消す
                var end = fence + CODE_FENCE.count
                while end < units.count, !isLineTerminator(units[end]) { end += 1 }
                index = end
                continue
            }
        }
        result.append(units[index])
        index += 1
    }
    return result
}

/// 先頭の `[-*・]` と前後の ASCII の空白の長さ。合わなければ nil。
private func bulletLength(_ units: [UInt16]) -> Int? {
    var index = 0
    while index < units.count, isRegexSpace(units[index]) { index += 1 }
    guard index < units.count, BULLET_MARKS.contains(units[index]) else { return nil }
    index += 1
    while index < units.count, isRegexSpace(units[index]) { index += 1 }
    return index
}

/// Kotlin の `BULLET.containsMatchIn(text)`。
func hasBullet(_ text: String) -> Bool {
    bulletLength(Array(text.utf16)) != nil
}

/// Kotlin の `replace(BULLET, "")`。
func removingBullet(_ text: String) -> String {
    let units = Array(text.utf16)
    guard let length = bulletLength(units) else { return text }
    return stringFromUTF16(units[length...])
}

extension String {
    func withoutWrapping() -> String {
        stringFromUTF16(removingCodeFenceLines(removingThinkBlocks(Array(utf16)))).kotlinTrim()
    }
}

/// 箇条書きの要約を整える。前置きは捨て、行頭は「- 」に揃える。読めるものがなければ nil。
func parseSummary(_ text: String) -> String? {
    let lines = text.withoutWrapping().kotlinLines().map { $0.kotlinTrim() }.filter { !$0.isEmpty }
    let bullets = lines.filter(hasBullet).map { "- " + removingBullet($0) }
    let kept = bullets.isEmpty ? lines : bullets
    return kept.isEmpty ? nil : kept.joined(separator: "\n")
}

/**
 * 自由形式の出力から ToDo を読む。読めなければ nil。
 *
 * 検証の E4B はほぼ素直な JSON を出すが、前後の説明、コードブロック、末尾のカンマ、
 * 出力の上限での打ち切りには耐える。打ち切られたら、閉じている要素までを読む。
 */
func parseTodos(_ text: String) -> ParsedTodos? {
    let cleaned = Array(text.withoutWrapping().utf16)
    var start = indexOfJsonStart(cleaned, from: 0)
    var sawBroken = false
    while start >= 0 {
        let region = scanRegion(cleaned, start)
        let element = region.flatMap { parseJsonOrNull($0.json) }
        let items = element?.todoElements()
        if let region, let items {
            let parsed = items.compactMap(toRawTodo)
            return ParsedTodos(parsed, isComplete: !sawBroken && !region.isTruncated && parsed.count == items.count)
        }
        sawBroken = sawBroken || element == nil
        let next = if let region, element != nil { region.endIndex } else { start + 1 }
        start = indexOfJsonStart(cleaned, from: next)
    }
    return nil
}

private func indexOfJsonStart(_ units: [UInt16], from start: Int) -> Int {
    var index = max(start, 0)
    while index < units.count {
        if JSON_START.contains(units[index]) { return index }
        index += 1
    }
    return -1
}

private func parseJsonOrNull(_ json: [UInt16]) -> LenientJsonElement? {
    try? LenientJson.parse(json)
}

extension LenientJsonElement {
    fileprivate func todoElements() -> [LenientJsonElement]? {
        switch self {
        case let .object(object):
            if case let .array(array) = object[ITEMS_KEY] { return array }
            return nil
        case let .array(array):
            return array.allSatisfy(\.isObject) ? array : nil
        case .literal, .null:
            return nil
        }
    }
}

private func toRawTodo(_ element: LenientJsonElement) -> RawTodo? {
    guard case let .object(item) = element else { return nil }
    let task = stringOrNull(item, "task")?.kotlinTrim() ?? ""
    return task.isEmpty ? nil : RawTodo(task, stringOrNull(item, "kind"), stringOrNull(item, "due"))
}

/// Kotlin の `(this[key] as? JsonPrimitive)?.contentOrNull`。数や真偽は書かれたままの文字列になる。
private func stringOrNull(_ object: [String: LenientJsonElement], _ key: String) -> String? {
    switch object[key] {
    case let .literal(content, _):
        return content
    default:
        return nil
    }
}

private struct JsonRegion {
    let json: [UInt16]
    let endIndex: Int
    let isTruncated: Bool
}

/// [start] の括弧に対応する閉じ括弧までを取り出す。閉じていなければ、閉じた要素までで括弧を補う。
private func scanRegion(_ text: [UInt16], _ start: Int) -> JsonRegion? {
    let quote: UInt16 = 0x22
    let backslash: UInt16 = 0x5C
    let openBrace: UInt16 = 0x7B
    let closeBrace: UInt16 = 0x7D
    let openBracket: UInt16 = 0x5B
    let closeBracket: UInt16 = 0x5D
    var closers: [UInt16] = []
    var inString = false
    var isEscaped = false
    var safeEnd = -1
    var safeClosers: [UInt16] = []
    for index in start..<text.count {
        let char = text[index]
        if isEscaped {
            isEscaped = false
        } else if inString, char == backslash {
            isEscaped = true
        } else if char == quote {
            inString.toggle()
        } else if inString {
            continue
        } else if char == openBrace {
            closers.append(closeBrace)
        } else if char == openBracket {
            closers.append(closeBracket)
        } else if char == closeBrace || char == closeBracket {
            // Kotlin の ArrayDeque.removeLast() は空なら投げる。start は開き括弧なので空にならない
            closers.removeLast()
            if closers.isEmpty { return JsonRegion(json: Array(text[start...index]), endIndex: index + 1, isTruncated: false) }
            safeEnd = index + 1
            safeClosers = closers.reversed()
        }
    }
    return safeEnd < 0 ? nil : JsonRegion(json: Array(text[start..<safeEnd]) + safeClosers, endIndex: safeEnd, isTruncated: true)
}
