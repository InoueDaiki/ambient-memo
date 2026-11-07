package dev.ambientmemo.engine.domain

/**
 * 整理のプロンプト。
 *
 * 文面は `tools/spike-llm/scripts/prompts.py`(spike-llm ブランチ)と同じにする。
 * 要約の文面を変えると `docs/検証-LLM.md` の成績が根拠にならなくなるので、変えるときは評価をやり直す。
 * タイトルのプロンプトは評価していない。
 * 話題の切れ目のプロンプトは `docs/検証-話題の切れ目.md` で評価した。変えるときは `tools/spike-llm/scripts/topic_eval.py` で測り直す。
 */
internal object OrganizePrompts {
    private const val MAX_OUTPUT_TOKENS = 400
    private const val MAX_TITLE_OUTPUT_TOKENS = 48
    private const val MAX_TOPIC_OUTPUT_TOKENS = 64

    private const val SYSTEM = "あなたは個人用メモアプリの整理係です。" +
        "入力は音声の自動文字起こしで、誤変換、言いよどみ、話者ラベルの誤りが含まれます。" +
        "「自分」は端末の持ち主です。" +
        "書かれていないことを補ったり推測したりしないでください。"

    const val SUMMARY_RETRY_NOTE = "\n\n前回の出力は空でした。「- 」で始まる箇条書きだけを出力してください。"

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

    /** 行番号を付けて渡し、新しい話題が始まる行の番号だけを答えさせる。文字起こしを書き直させない。 */
    fun topics(lines: List<String>) = LlmRequest(
        SYSTEM,
        """
        |次の文字起こしを話題ごとに分けてください。行の先頭の数字は行番号です。
        |
        |- 新しい話題が始まる行の番号を、小さい順に半角のカンマ区切りで出力する
        |- 最初の番号は 1 にする。話題が1つだけなら 1 だけを出力する
        |- 話している対象(用事、予定、出来事、もの)がはっきり別のものに変わったときだけ、新しい話題にする
        |- 同じ対象についての質問、詳しい話、感想、言い換えは、同じ話題に含める
        |- 前の話から自然につながる話(同じ人、場所、用事、出来事に関わる話)も、同じ話題に含める
        |- 短い寄り道や、一言だけの別の話では分けない
        |- 相づちや言いよどみだけの行では話題を変えない
        |- 迷ったら分けない
        |- 番号だけを1行で出力する。説明は付けない
        |
        |例: 1〜4 行目が昼食の店の相談、5〜9 行目が週末の予定、10 行目から仕事の締め切りの話なら「1, 5, 10」
        |例: 1〜8 行目がずっと週末の予定の話で、途中で店や時間の細かい話になっても「1」
        |例: 1〜5 行目が旅行の宿の相談で、6〜9 行目が同じ旅行の移動の相談なら「1」
        |
        |文字起こし:
        |
        """.trimMargin() + lines.withIndex().joinToString("\n") { (index, line) -> "${index + 1} $line" },
        MAX_TOPIC_OUTPUT_TOKENS,
    )

    private fun request(user: String) = LlmRequest(SYSTEM, user, MAX_OUTPUT_TOKENS)

    private fun label(date: CalendarDate) = "$date(${date.weekdayName}曜日)"
}
