package dev.ambientmemo.engine.domain

/**
 * 要約と ToDo 抽出のプロンプト。
 *
 * 文面は `tools/spike-llm/scripts/prompts.py`(spike-llm ブランチ)と同じにする。
 * 変えると `docs/検証-LLM.md` の成績が根拠にならなくなるので、変えるときは評価をやり直す。
 * タイトルと話題の切れ目のプロンプトは評価していない。
 */
internal object OrganizePrompts {
    /** 日付の表の日数。今日を含む。 */
    const val CALENDAR_DAYS = 22

    private const val WEEKDAYS = "月火水木金土日"
    private const val MAX_OUTPUT_TOKENS = 400
    private const val MAX_TITLE_OUTPUT_TOKENS = 48
    private const val MAX_TOPIC_OUTPUT_TOKENS = 64

    private const val SYSTEM = "あなたは個人用メモアプリの整理係です。" +
        "入力は音声の自動文字起こしで、誤変換、言いよどみ、話者ラベルの誤りが含まれます。" +
        "「自分」は端末の持ち主です。" +
        "書かれていないことを補ったり推測したりしないでください。"

    private val DAY_LABELS = listOf(" 今日", " 明日", " あさって")

    const val SUMMARY_RETRY_NOTE = "\n\n前回の出力は空でした。「- 」で始まる箇条書きだけを出力してください。"
    const val TODO_RETRY_NOTE = "\n\n前回の出力は JSON として読めませんでした。指定の形式の JSON だけを、最後まで出力してください。"

    fun summary(date: CalendarDate, transcript: String) = request(
        """
        |今日は ${label(date)} です。次の文字起こしを、あとで読み返すためのメモに要約してください。
        |
        |- 日本語の箇条書きで3〜6行。各行は「- 」で始める
        |- 決まったこと、日時、金額、人や場所の名前を残す
        |- 誤変換は正しい言葉に直して書く
        |- 箇条書きだけを出力する
        |
        |文字起こし:
        |
        """.trimMargin() + transcript.trim(),
    )

    /** 文字起こしより短くて誤りの少ない要約から作る。 */
    fun memoTitle(summary: String) = LlmRequest(
        SYSTEM,
        """
        |次のメモに、一覧で見分けるためのタイトルを付けてください。
        |
        |- 話題が分かる日本語の短い名詞句で、20文字以内
        |- 日付や曜日だけのタイトルにしない
        |- タイトルだけを1行で出力する。記号や説明は付けない
        |
        |メモ:
        |
        """.trimMargin() + summary.trim(),
        MAX_TITLE_OUTPUT_TOKENS,
    )

    /** 抽出した内容から作る。文字起こしは渡さず、内容にないことを足させない。 */
    fun todoTitle(task: String) = LlmRequest(
        SYSTEM,
        """
        |次の ToDo に、一覧で見分けるためのタイトルを付けてください。
        |
        |- やることが分かる日本語の短い句で、20文字以内
        |- 日付や曜日だけのタイトルにしない
        |- タイトルだけを1行で出力する。記号や説明は付けない
        |
        |ToDo:
        |
        """.trimMargin() + task.trim(),
        MAX_TITLE_OUTPUT_TOKENS,
    )

    /** 行番号を付けて渡し、新しい話題が始まる行の番号だけを答えさせる。文字起こしを書き直させない。 */
    fun topics(lines: List<String>) = LlmRequest(
        SYSTEM,
        """
        |次の文字起こしを話題ごとに分けてください。行の先頭の数字は行番号です。
        |
        |- 新しい話題が始まる行の番号を、小さい順に半角のカンマ区切りで出力する
        |- 最初の番号は 1 にする。話題が1つだけなら 1 だけを出力する
        |- 相づちや言いよどみだけの行では話題を変えない
        |- 番号だけを1行で出力する。説明は付けない
        |
        |例: 1〜4 行目が昼食の店の相談、5〜9 行目が週末の予定、10 行目から仕事の締め切りの話なら「1, 5, 10」
        |
        |文字起こし:
        |
        """.trimMargin() + lines.withIndex().joinToString("\n") { (index, line) -> "${index + 1} $line" },
        MAX_TOPIC_OUTPUT_TOKENS,
    )

    fun todo(date: CalendarDate, transcript: String) = request(
        """
        |今日は ${label(date)} です。次の文字起こしから、「自分」に関するものだけを抽出してください。
        |
        |抽出するもの:
        |- todo: 自分がこれからやること
        |- reminder: 自分が日時を覚えておくべき予定
        |
        |抽出しないもの:
        |- 自分以外の人がやること
        |- もう終わったこと
        |- 取り消したこと、やめたこと
        |- 願望、仮定、冗談、雑談
        |
        |出力の形式:
        |- JSON だけを出力する。説明やコードブロックは書かない
        |- kind は todo か reminder
        |- due は YYYY-MM-DD 形式の期限や予定の日付。日付が分からなければ null
        |- 該当するものがなければ {"items": []}
        |
        |例: 「[09:00] 話者1: 請求書、明日までに送ってくれる? [09:00] 自分: はい、送ります。」の出力は
        |{"items": [{"task": "請求書を送る", "kind": "todo", "due": "${date.plusDays(1)}"}]}
        |
        |カレンダー(日付は必ずここから選ぶ。週は月曜始まり。範囲外の日付は月日から直接書く):
        |${calendar(date)}
        |
        |文字起こし:
        |
        """.trimMargin() + transcript.trim(),
    )

    private fun request(user: String) = LlmRequest(SYSTEM, user, MAX_OUTPUT_TOKENS)

    private fun label(date: CalendarDate) = "$date(${WEEKDAYS[date.weekdayIndex]}曜日)"

    /** 日付の計算をモデルに任せず、対応表を渡す。小型モデルは曜日の暗算を誤る。 */
    private fun calendar(start: CalendarDate): String = (0 until CALENDAR_DAYS).joinToString("\n") { offset ->
        val date = start.plusDays(offset)
        "$date ${WEEKDAYS[date.weekdayIndex]}${DAY_LABELS.getOrElse(offset) { "" }}"
    }
}
