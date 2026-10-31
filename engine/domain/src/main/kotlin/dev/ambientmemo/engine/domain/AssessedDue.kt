package dev.ambientmemo.engine.domain

/** 検証の結果を受けた期限の判定。[date] が null でも、[isConfident] が false なら LLM は何か書こうとした。 */
internal data class AssessedDue(
    val date: CalendarDate?,
    val isConfident: Boolean,
)

private val NO_DUE_WORDS = setOf("", "null", "none", "なし", "不明")

// 日付の手がかりがない会話の期限は、モデルが作った疑いがある。漢数字は ASR の出力に多い
private val DATE_EXPRESSION = Regex(
    "今日|本日|きょう|明日|あした|あす|明後日|あさって|しあさって|昨日|今週|来週|再来週|週末|今月|来月|再来月|月末|今年|来年|" +
        "曜|[0-9０-９一二三四五六七八九十]+\\s*(月|日)|[0-9０-９]+[/／][0-9０-９]+",
)

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
internal fun assessDue(
    rawDue: String?,
    spokenOn: CalendarDate,
    endedOn: CalendarDate,
    transcript: String,
): AssessedDue {
    val text = rawDue?.trim().orEmpty()
    val date = CalendarDate.parse(text)
    return when {
        text.lowercase() in NO_DUE_WORDS -> AssessedDue(null, isConfident = true)
        date == null -> AssessedDue(null, isConfident = false)
        else -> AssessedDue(
            date,
            isConfident = date >= spokenOn &&
                date < spokenOn.plusDays(OrganizePrompts.CALENDAR_DAYS) &&
                DATE_EXPRESSION.containsMatchIn(transcript) &&
                spokenOn == endedOn,
        )
    }
}
