import Foundation

/// 検証の結果を受けた期限の判定。[date] が nil でも、[isConfident] が false なら LLM は何か書こうとした。
struct AssessedDue: Sendable, Hashable {
    let date: CalendarDate?
    let isConfident: Bool

    init(_ date: CalendarDate?, isConfident: Bool) {
        self.date = date
        self.isConfident = isConfident
    }
}

private let NO_DUE_WORDS: Set<String> = ["", "null", "none", "なし", "不明"]

// 日付の手がかりがない会話の期限は、モデルが作った疑いがある。漢数字は ASR の出力に多い
//
// Kotlin の正規表現
// "今日|本日|きょう|明日|あした|あす|明後日|あさって|しあさって|昨日|今週|来週|再来週|週末|今月|来月|再来月|月末|今年|来年|" +
//     "曜|[0-9０-９一二三四五六七八九十]+\\s*(月|日)|[0-9０-９]+[/／][0-9０-９]+"
// を、同じ判定になるように手で書いた。`\s` は Java と同じく ASCII の空白だけにする。
private let DATE_WORDS = [
    "今日", "本日", "きょう", "明日", "あした", "あす", "明後日", "あさって", "しあさって", "昨日", "今週", "来週", "再来週",
    "週末", "今月", "来月", "再来月", "月末", "今年", "来年", "曜",
]

private let DATE_NUMERALS = Set("0123456789０１２３４５６７８９一二三四五六七八九十".utf16)
private let DATE_DIGITS = Set("0123456789０１２３４５６７８９".utf16)
private let MONTH_OR_DAY = Set("月日".utf16)
private let DATE_SLASHES = Set("/／".utf16)

/// Kotlin の `DATE_EXPRESSION.containsMatchIn(text)`。
func containsDateExpression(_ text: String) -> Bool {
    if DATE_WORDS.contains(where: { text.kotlinContains($0) }) { return true }
    let units = Array(text.utf16)
    for (index, unit) in units.enumerated() {
        if MONTH_OR_DAY.contains(unit) {
            // [数字]+\s*(月|日): 前に空白を挟んで数字があるか
            var position = index - 1
            while position >= 0, isRegexSpace(units[position]) { position -= 1 }
            if position >= 0, DATE_NUMERALS.contains(units[position]) { return true }
        } else if DATE_SLASHES.contains(unit) {
            // [数字]+[/／][数字]+: 前後に数字があるか
            if index > 0, index + 1 < units.count, DATE_DIGITS.contains(units[index - 1]),
               DATE_DIGITS.contains(units[index + 1]) {
                return true
            }
        }
    }
    return false
}

/**
 * LLM の期限を確かめる。検証での期限の正解率は 0.85 で、年をまたぐ日付の解決は全モデルが誤った。
 * そこで、モデルに確度を聞かず、確かめられる条件で判定する。次のときは確度が低い。
 *
 * - 形式が違うか、存在しない日付
 * - 会話の日より前
 * - プロンプトで渡した日付の表の範囲外
 * - 文字起こしに日付の手がかりがない
 * - まとまりが日付をまたぐ。「明日」の基準がずれる
 */
func assessDue(
    _ rawDue: String?,
    _ spokenOn: CalendarDate,
    _ endedOn: CalendarDate,
    _ transcript: String
) throws -> AssessedDue {
    let text = rawDue?.kotlinTrim() ?? ""
    let date = CalendarDate.parse(text)
    if NO_DUE_WORDS.contains(text.kotlinLowercase()) { return AssessedDue(nil, isConfident: true) }
    guard let date else { return AssessedDue(nil, isConfident: false) }
    // Kotlin の && と同じ順に評価する。plusDays は範囲外で投げる
    var isConfident = date >= spokenOn
    if isConfident { isConfident = try date < spokenOn.plusDays(OrganizePrompts.CALENDAR_DAYS) }
    if isConfident { isConfident = containsDateExpression(transcript) }
    if isConfident { isConfident = spokenOn == endedOn }
    return AssessedDue(date, isConfident: isConfident)
}
