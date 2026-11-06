import Foundation

// Kotlin は次の正規表現を使う。Swift では同じ判定になるように手で書いた。
// Swift の Regex は書記素で、NSRegularExpression は `\s` と `\d` が Unicode で、どちらも Java と合わないため。
// - THINK_BLOCK `<think>.*?(</think>|\z)`(DOT_MATCHES_ALL)
// - CODE_FENCE_LINE `^\s*```.*$`(MULTILINE)
// - BULLET `^\s*[-*・]\s*`
private let THINK_OPEN = Array("<think>".utf16)
private let THINK_CLOSE = Array("</think>".utf16)
private let CODE_FENCE = Array("```".utf16)
private let BULLET_MARKS = Set("-*・".utf16)

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
