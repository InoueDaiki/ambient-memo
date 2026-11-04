import Foundation

// Kotlin の正規表現 TITLE_LABEL `^(タイトル|題名)\s*[:：]\s*` と DATE_ONLY `[\d\s年月日曜火水木金土/()（）-]+` を、
// 同じ判定になるように手で書いた。`\d` と `\s` は Java と同じく ASCII だけにする。
private let TITLE_LABELS = [Array("タイトル".utf16), Array("題名".utf16)]
private let TITLE_COLONS = Set(":：".utf16)
private let TITLE_WRAPPERS = Set("「」『』\"*# 　".utf16)
private let DATE_ONLY_CHARACTERS = Set("年月日曜火水木金土/()（）-".utf16)

/// 自由形式の出力からタイトルを読む。読めないか、日付だけなら nil。
func parseTitle(_ text: String) -> String? {
    guard let title = text.withoutWrapping().kotlinLines().map(cleanTitle).first(where: { !$0.isEmpty }) else {
        return nil
    }
    return isDateOnly(title) ? nil : title
}

/// 飾りを外した題名の候補を返す。
func cleanTitle(_ line: String) -> String {
    removingTitleLabel(removingBullet(line.kotlinTrim())).kotlinTrim(where: TITLE_WRAPPERS.contains)
}

/// 話題が分からない題名を見分ける。
func isDateOnly(_ title: String) -> Bool {
    let units = title.utf16
    return !units.isEmpty && units.allSatisfy { isRegexDigit($0) || isRegexSpace($0) || DATE_ONLY_CHARACTERS.contains($0) }
}

/// Kotlin の `replace(TITLE_LABEL, "")`。先頭にだけ合う。
private func removingTitleLabel(_ text: String) -> String {
    let units = Array(text.utf16)
    for label in TITLE_LABELS where units.starts(with: label) {
        var index = label.count
        while index < units.count, isRegexSpace(units[index]) { index += 1 }
        guard index < units.count, TITLE_COLONS.contains(units[index]) else { continue }
        index += 1
        while index < units.count, isRegexSpace(units[index]) { index += 1 }
        return stringFromUTF16(units[index...])
    }
    return text
}
