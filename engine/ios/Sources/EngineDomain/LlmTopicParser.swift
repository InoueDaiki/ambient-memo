import Foundation

// Kotlin の正規表現 TOPIC_STARTS `[\d\s,、，]+` と NUMBER `\d+` を、同じ判定になるように手で書いた。
// `\d` と `\s` は Java と同じく ASCII だけにする。
private let TOPIC_SEPARATORS = Set(",、，".utf16)
private let TOPIC_WRAPPERS = Set("「」\"*# 　".utf16)

/**
 * 話題が始まる行の番号を読む。番号は 1 から [lineCount] までに絞り、小さい順に重ねずに並べ、1 を必ず含める。
 * 番号と区切りのほかの文字があれば、読めないものとして nil を返す。
 */
func parseTopicStarts(_ text: String, _ lineCount: Int) -> [Int]? {
    let line = text.withoutWrapping().kotlinLines()
        .map { $0.kotlinTrim().kotlinTrim(where: TOPIC_WRAPPERS.contains) }
        .first { !$0.isEmpty }
    guard let line else { return nil }
    let units = Array(line.utf16)
    guard units.allSatisfy({ isRegexDigit($0) || isRegexSpace($0) || TOPIC_SEPARATORS.contains($0) }) else {
        return nil
    }
    var starts: [Int] = []
    var digits: [UInt16] = []
    for unit in units + [0x20] {
        if isRegexDigit(unit) {
            digits.append(unit)
            continue
        }
        // Kotlin の toIntOrNull と同じく、Int32 に収まらない番号は読まない
        if !digits.isEmpty, let number = Int32(stringFromUTF16(digits[...])), number >= 1, Int(number) <= lineCount {
            starts.append(Int(number))
        }
        digits = []
    }
    return Array(Set(starts + [1])).sorted()
}
