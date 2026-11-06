package dev.ambientmemo.engine.domain

/**
 * メモへの質問のプロンプト。評価していない。
 *
 * 検索の計画と答えの 2 つに分ける。どちらも JSON にせず、行の形で答えさせる。読めないときに再試行しないため。
 */
internal object AskPrompts {
    /** 日付の表の日数。今日を含む。過去のメモを探すので、今日から遡る。 */
    const val CALENDAR_DAYS = 14

    private const val MAX_PLAN_OUTPUT_TOKENS = 64
    private const val MAX_ANSWER_OUTPUT_TOKENS = 400

    /** 答えのないことを表す文。答えの依頼で、メモから分からないときにこれだけを出させる。 */
    const val NO_ANSWER = "メモには見つかりませんでした"

    private const val SYSTEM = "あなたは個人用メモアプリの検索係です。" +
        "メモは身の回りの会話を自動で文字起こしして要約したもので、誤変換が含まれます。" +
        "「自分」は端末の持ち主です。" +
        "書かれていないことを補ったり推測したりしないでください。"

    private val DAY_LABELS = listOf(" 今日", " 昨日", " おととい")

    /** [tried] は前の検索で見つからなかった語。違う言い方を考えさせる。 */
    fun plan(question: String, today: CalendarDate, tried: List<String>) = LlmRequest(
        SYSTEM,
        """
        |今日は ${label(today)} です。次の質問に答えるために、過去のメモを検索します。検索の条件を考えてください。
        |
        |- 1行目は「語: 」に続けて、メモの本文に出てきそうな短い語を、大事な順に半角のカンマ区切りで1〜5個
        |- 語は名詞か動詞の語幹にする。「こと」「話」「何」「いつ」のような、どのメモにも出る語は入れない
        |- 2行目は「期間: 」に続けて、質問が指す期間を「YYYY-MM-DD〜YYYY-MM-DD」で書く。期間がなければ「なし」
        |- 2行だけを出力する。説明は付けない
        |
        |例: 「先週の打ち合わせで何を決めた?」で、先週が 2026-11-02〜2026-11-08 なら
        |語: 打ち合わせ, 会議, 決定
        |期間: 2026-11-02〜2026-11-08
        |
        |
        """.trimMargin() + triedNote(tried) + "カレンダー(週は月曜始まり):\n" + calendar(today) +
            "\n\n質問: " + question.trim(),
        MAX_PLAN_OUTPUT_TOKENS,
    )

    /** [memos] は番号を付けたメモ。番号で根拠を挙げさせる。 */
    fun answer(question: String, today: CalendarDate, memos: String) = LlmRequest(
        SYSTEM,
        """
        |今日は ${label(today)} です。次のメモだけを根拠に、質問に日本語で答えてください。
        |
        |- 1〜4文で、質問に直接答える
        |- 日時、金額、人や場所の名前はメモの通りに書く
        |- 根拠にしたメモの番号を、文の終わりに [1] のように付ける
        |- メモから答えが分からなければ「$NO_ANSWER」とだけ出力する
        |
        |メモ:
        |
        """.trimMargin() + memos.trim() + "\n\n質問: " + question.trim(),
        MAX_ANSWER_OUTPUT_TOKENS,
    )

    private fun triedNote(tried: List<String>) = if (tried.isEmpty()) {
        ""
    } else {
        "次の語では見つかりませんでした。別の言い方や、関係のある別の語にしてください: ${tried.joinToString(", ")}\n\n"
    }

    fun label(date: CalendarDate) = "$date(${date.weekdayName}曜日)"

    /** 日付の計算をモデルに任せず、対応表を渡す。小型モデルは曜日の暗算を誤る。 */
    private fun calendar(today: CalendarDate): String = (0 until CALENDAR_DAYS).joinToString("\n") { offset ->
        val date = today.plusDays(-offset)
        "$date ${date.weekdayName}${DAY_LABELS.getOrElse(offset) { "" }}"
    }
}
