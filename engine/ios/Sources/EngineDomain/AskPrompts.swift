import Foundation

/**
 * メモへの質問のプロンプト。評価していない。
 *
 * 検索の計画と答えの 2 つに分ける。どちらも JSON にせず、行の形で答えさせる。読めないときに再試行しないため。
 *
 * Kotlin の `trimMargin()` の文字列を、同じ文字列になる Swift の複数行の文字列で書いた。Kotlin と一字一句揃える。
 * 日付の計算が範囲外になると [IllegalArgumentException] を投げるので、Swift では [plan] を `throws` にした。
 */
enum AskPrompts {
    /// 日付の表の日数。今日を含む。過去のメモを探すので、今日から遡る。
    static let CALENDAR_DAYS = 14

    private static let MAX_PLAN_OUTPUT_TOKENS = 64
    private static let MAX_ANSWER_OUTPUT_TOKENS = 400

    /// 答えのないことを表す文。答えの依頼で、メモから分からないときにこれだけを出させる。
    static let NO_ANSWER = "メモには見つかりませんでした"

    private static let SYSTEM = "あなたは個人用メモアプリの検索係です。" +
        "メモは身の回りの会話を自動で文字起こしして要約したもので、誤変換が含まれます。" +
        "「自分」は端末の持ち主です。" +
        "書かれていないことを補ったり推測したりしないでください。"

    private static let DAY_LABELS = [" 今日", " 昨日", " おととい"]

    /// [tried] は前の検索で見つからなかった語。違う言い方を考えさせる。
    static func plan(_ question: String, _ today: CalendarDate, _ tried: [String]) throws -> LlmRequest {
        let table = try calendar(today)
        return LlmRequest(
            SYSTEM,
            """
            今日は \(label(today)) です。次の質問に答えるために、過去のメモを検索します。検索の条件を考えてください。

            - 1行目は「語: 」に続けて、メモの本文に出てきそうな短い語を、大事な順に半角のカンマ区切りで1〜5個
            - 語は名詞か動詞の語幹にする。「こと」「話」「何」「いつ」のような、どのメモにも出る語は入れない
            - 2行目は「期間: 」に続けて、質問が指す期間を「YYYY-MM-DD〜YYYY-MM-DD」で書く。期間がなければ「なし」
            - 2行だけを出力する。説明は付けない

            例: 「先週の打ち合わせで何を決めた?」で、先週が 2026-11-02〜2026-11-08 なら
            語: 打ち合わせ, 会議, 決定
            期間: 2026-11-02〜2026-11-08


            """ + triedNote(tried) + "カレンダー(週は月曜始まり):\n" + table +
                "\n\n質問: " + question.kotlinTrim(),
            MAX_PLAN_OUTPUT_TOKENS
        )
    }

    /// [memos] は番号を付けたメモ。番号で根拠を挙げさせる。
    static func answer(_ question: String, _ today: CalendarDate, _ memos: String) -> LlmRequest {
        LlmRequest(
            SYSTEM,
            """
            今日は \(label(today)) です。次のメモだけを根拠に、質問に日本語で答えてください。

            - 1〜4文で、質問に直接答える
            - 日時、金額、人や場所の名前はメモの通りに書く
            - 根拠にしたメモの番号を、文の終わりに [1] のように付ける
            - メモから答えが分からなければ「\(NO_ANSWER)」とだけ出力する

            メモ:

            """ + memos.kotlinTrim() + "\n\n質問: " + question.kotlinTrim(),
            MAX_ANSWER_OUTPUT_TOKENS
        )
    }

    private static func triedNote(_ tried: [String]) -> String {
        if tried.isEmpty { return "" }
        return "次の語では見つかりませんでした。別の言い方や、関係のある別の語にしてください: \(tried.joined(separator: ", "))\n\n"
    }

    static func label(_ date: CalendarDate) -> String {
        "\(date)(\(date.weekdayName)曜日)"
    }

    /// 日付の計算をモデルに任せず、対応表を渡す。小型モデルは曜日の暗算を誤る。
    private static func calendar(_ today: CalendarDate) throws -> String {
        try (0..<CALENDAR_DAYS).map { offset in
            let date = try today.plusDays(-offset)
            let dayLabel = offset < DAY_LABELS.count ? DAY_LABELS[offset] : ""
            return "\(date) \(date.weekdayName)\(dayLabel)"
        }.joined(separator: "\n")
    }
}
