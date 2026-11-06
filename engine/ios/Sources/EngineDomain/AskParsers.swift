import Foundation

// Kotlin は次の正規表現を使う。Swift では同じ判定になるように手で書いた。`\s` と `\d` は Java と同じく ASCII だけにする。
// - TERMS_LINE `^\s*[-*・]?\s*(語|検索語|キーワード)\s*[:：]\s*(.*)$`
// - RANGE_LINE `^\s*[-*・]?\s*期間\s*[:：]\s*(.*)$`
// - TERM_SEPARATORS `[,、，/／]`
// - ISO_DATES `\d{4}-\d{2}-\d{2}`
// - CITATION `\[\s*(\d+)\s*]|［\s*(\d+)\s*］`
private let TERMS_LABELS = [Array("語".utf16), Array("検索語".utf16), Array("キーワード".utf16)]
private let RANGE_LABELS = [Array("期間".utf16)]
private let LINE_BULLETS = Set("-*・".utf16)
private let LABEL_COLONS = Set(":：".utf16)
private let TERM_SEPARATORS = Set(",、，/／".utf16)
private let TERM_WRAPPERS = Set("「」『』\"'*#() 　（）".utf16)
private let CITATION_BRACKETS: [(open: UInt16, close: UInt16)] = [(0x5B, 0x5D), (0xFF3B, 0xFF3D)]
private let PUNCTUATION_AFTER_SPACE = Set("。、.,".utf16)
private let MAX_TERMS = 5
private let MIN_TERM_CHARS = 2

/// 検索の計画。[from] と [to] はどちらも含む。期間がなければどちらも nil。
struct SearchPlan: Sendable, Hashable {
    let terms: [String]
    let from: CalendarDate?
    let to: CalendarDate?

    init(_ terms: [String], _ from: CalendarDate?, _ to: CalendarDate?) {
        self.terms = terms
        self.from = from
        self.to = to
    }
}

/// Java の `.` が合わない行の終わり。
private func isJavaLineTerminator(_ unit: UInt16) -> Bool {
    unit == 0x0A || unit == 0x0D || unit == 0x85 || unit == 0x2028 || unit == 0x2029
}

/// `^\s*[-*・]?\s*(ラベル)\s*[:：]\s*(.*)$` の全体一致で、最後の組を返す。合わなければ nil。
private func labeledValue(_ line: String, _ labels: [[UInt16]]) -> String? {
    let units = Array(line.utf16)
    var index = 0
    while index < units.count, isRegexSpace(units[index]) { index += 1 }
    if index < units.count, LINE_BULLETS.contains(units[index]) { index += 1 }
    while index < units.count, isRegexSpace(units[index]) { index += 1 }
    for label in labels where Array(units[index...]).starts(with: label) {
        var cursor = index + label.count
        while cursor < units.count, isRegexSpace(units[cursor]) { cursor += 1 }
        guard cursor < units.count, LABEL_COLONS.contains(units[cursor]) else { continue }
        cursor += 1
        while cursor < units.count, isRegexSpace(units[cursor]) { cursor += 1 }
        let rest = units[cursor...]
        // `.` は行の終わりに合わないので、残りに行の終わりがあれば全体一致にならない
        guard !rest.contains(where: isJavaLineTerminator) else { return nil }
        return stringFromUTF16(rest)
    }
    return nil
}

/// Kotlin の `split(TERM_SEPARATORS)`。区切りの前後の空の要素も残す。
private func splitTerms(_ text: String) -> [String] {
    var parts: [String] = []
    var current: [UInt16] = []
    for unit in text.utf16 {
        if TERM_SEPARATORS.contains(unit) {
            parts.append(stringFromUTF16(current))
            current = []
        } else {
            current.append(unit)
        }
    }
    parts.append(stringFromUTF16(current))
    return parts
}

/// Kotlin の `ISO_DATES.findAll(text)` の一致した文字列。重ならないように左から探す。
private func isoDates(_ text: String) -> [String] {
    let units = Array(text.utf16)
    let pattern: [Bool] = [true, true, true, true, false, true, true, false, true, true]
    var found: [String] = []
    var index = 0
    while index + pattern.count <= units.count {
        let isMatch = pattern.enumerated().allSatisfy { offset, isDigit in
            let unit = units[index + offset]
            return isDigit ? isRegexDigit(unit) : unit == 0x2D
        }
        if isMatch {
            found.append(stringFromUTF16(units[index..<index + pattern.count]))
            index += pattern.count
        } else {
            index += 1
        }
    }
    return found
}

/**
 * 「語: 」と「期間: 」の行を読む。どちらの行もなければ nil。
 *
 * 語は重ねず、2 文字より短いものを捨て、最大 5 個にする。期間は日付が 2 つ読めたときだけ使い、逆順なら入れ替える。
 * 日付が 1 つなら、その 1 日にする。
 */
func parseSearchPlan(_ text: String) -> SearchPlan? {
    let lines = text.withoutWrapping().kotlinLines()
    let termsLine = lines.lazy.compactMap { labeledValue($0, TERMS_LABELS) }.first
    let rangeLine = lines.lazy.compactMap { labeledValue($0, RANGE_LABELS) }.first
    if termsLine == nil, rangeLine == nil { return nil }
    var seen = Set<[UInt16]>()
    let terms = splitTerms(termsLine ?? "")
        .map { $0.kotlinTrim(where: TERM_WRAPPERS.contains) }
        .filter { $0.kotlinLength >= MIN_TERM_CHARS }
        // Kotlin の distinct と同じく、UTF-16 の単位で同じものを重ねない
        .filter { seen.insert(Array($0.utf16)).inserted }
        .prefix(MAX_TERMS)
    let dates = isoDates(rangeLine ?? "").compactMap(CalendarDate.parse).prefix(2)
    return SearchPlan(Array(terms), dates.min(), dates.max())
}

/// 答えの本文と、挙げたメモの番号(1 から、挙げた順、重ねない)。
struct ParsedAnswer: Sendable, Hashable {
    let text: String
    let citations: [Int]

    init(_ text: String, _ citations: [Int]) {
        self.text = text
        self.citations = citations
    }
}

/// [start] から CITATION が合えば、終わりの位置と番号の数字を返す。
private func citationAt(_ units: [UInt16], _ start: Int) -> (end: Int, digits: [UInt16])? {
    for bracket in CITATION_BRACKETS where units[start] == bracket.open {
        var index = start + 1
        while index < units.count, isRegexSpace(units[index]) { index += 1 }
        let digitsStart = index
        while index < units.count, isRegexDigit(units[index]) { index += 1 }
        if index == digitsStart { continue }
        let digits = Array(units[digitsStart..<index])
        while index < units.count, isRegexSpace(units[index]) { index += 1 }
        if index < units.count, units[index] == bracket.close { return (index + 1, digits) }
    }
    return nil
}

/// 番号の印を外した本文と、印の番号の数字を、出てきた順に返す。
private func removingCitations(_ text: String) -> (body: String, numbers: [[UInt16]]) {
    let units = Array(text.utf16)
    var body: [UInt16] = []
    var numbers: [[UInt16]] = []
    var index = 0
    while index < units.count {
        if let citation = citationAt(units, index) {
            numbers.append(citation.digits)
            index = citation.end
        } else {
            body.append(units[index])
            index += 1
        }
    }
    return (stringFromUTF16(body), numbers)
}

/// Kotlin の `replace(Regex("""\s+([。、.,])"""), "$1")`。句読点の前の ASCII の空白を消す。
private func removingSpaceBeforePunctuation(_ text: String) -> String {
    let units = Array(text.utf16)
    var result: [UInt16] = []
    var index = 0
    while index < units.count {
        if isRegexSpace(units[index]) {
            var end = index
            while end < units.count, isRegexSpace(units[end]) { end += 1 }
            if end < units.count, PUNCTUATION_AFTER_SPACE.contains(units[end]) {
                index = end
                continue
            }
            result.append(contentsOf: units[index..<end])
            index = end
        } else {
            result.append(units[index])
            index += 1
        }
    }
    return stringFromUTF16(result)
}

/**
 * 答えを読む。番号の印は本文から外し、1 から [sourceCount] までの番号だけを残す。
 * 空か、答えがないことを表す文で始まれば nil。
 */
func parseAnswer(_ text: String, _ sourceCount: Int) -> ParsedAnswer? {
    let (body, numbers) = removingCitations(text.withoutWrapping())
    var citations: [Int] = []
    for digits in numbers {
        // Kotlin の toIntOrNull と同じく、Int32 に収まらない番号は読まない
        guard let number = Int32(stringFromUTF16(digits)).map(Int.init), number >= 1, number <= sourceCount,
              !citations.contains(number) else { continue }
        citations.append(number)
    }
    let joined = body.kotlinLines()
        .map { removingSpaceBeforePunctuation($0.kotlinTrim()) }
        .filter { !$0.isEmpty }
        .joined(separator: "\n")
    if joined.isEmpty || joined.utf16.starts(with: AskPrompts.NO_ANSWER.utf16) { return nil }
    return ParsedAnswer(joined, citations)
}
