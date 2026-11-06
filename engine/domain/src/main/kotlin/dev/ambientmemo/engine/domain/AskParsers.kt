package dev.ambientmemo.engine.domain

private val TERMS_LINE = Regex("""^\s*[-*・]?\s*(語|検索語|キーワード)\s*[:：]\s*(.*)$""")
private val RANGE_LINE = Regex("""^\s*[-*・]?\s*期間\s*[:：]\s*(.*)$""")
private val TERM_SEPARATORS = Regex("""[,、，/／]""")
private val ISO_DATES = Regex("""\d{4}-\d{2}-\d{2}""")
private val CITATION = Regex("""\[\s*(\d+)\s*]|［\s*(\d+)\s*］""")
private const val TERM_WRAPPERS = "「」『』\"'*#() 　（）"
private const val MAX_TERMS = 5
private const val MIN_TERM_CHARS = 2

/** 検索の計画。[from] と [to] はどちらも含む。期間がなければどちらも null。 */
internal data class SearchPlan(
    val terms: List<String>,
    val from: CalendarDate?,
    val to: CalendarDate?,
)

/**
 * 「語: 」と「期間: 」の行を読む。どちらの行もなければ null。
 *
 * 語は重ねず、2 文字より短いものを捨て、最大 5 個にする。期間は日付が 2 つ読めたときだけ使い、逆順なら入れ替える。
 * 日付が 1 つなら、その 1 日にする。
 */
internal fun parseSearchPlan(text: String): SearchPlan? {
    val lines = text.withoutWrapping().lines()
    val termsLine = lines.firstNotNullOfOrNull { TERMS_LINE.matchEntire(it)?.groupValues?.get(2) }
    val rangeLine = lines.firstNotNullOfOrNull { RANGE_LINE.matchEntire(it)?.groupValues?.get(1) }
    if (termsLine == null && rangeLine == null) return null
    val terms = termsLine.orEmpty()
        .split(TERM_SEPARATORS)
        .map { it.trim { char -> char in TERM_WRAPPERS } }
        .filter { it.length >= MIN_TERM_CHARS }
        .distinct()
        .take(MAX_TERMS)
    val dates = ISO_DATES.findAll(rangeLine.orEmpty()).mapNotNull { CalendarDate.parse(it.value) }.take(2).toList()
    val from = dates.minOrNull()
    val to = dates.maxOrNull()
    return SearchPlan(terms, from, to)
}

/** 答えの本文と、挙げたメモの番号(1 から、挙げた順、重ねない)。 */
internal data class ParsedAnswer(
    val text: String,
    val citations: List<Int>,
)

/**
 * 答えを読む。番号の印は本文から外し、1 から [sourceCount] までの番号だけを残す。
 * 空か、答えがないことを表す文で始まれば null。
 */
internal fun parseAnswer(text: String, sourceCount: Int): ParsedAnswer? {
    val cleaned = text.withoutWrapping()
    val citations = CITATION.findAll(cleaned)
        .mapNotNull { match -> match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull() }
        .filter { it in 1..sourceCount }
        .distinct()
        .toList()
    val body = cleaned.replace(CITATION, "")
        .lines()
        .map { it.trim().replace(Regex("""\s+([。、.,])"""), "$1") }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
    return body.takeUnless { it.isEmpty() || it.startsWith(AskPrompts.NO_ANSWER) }?.let { ParsedAnswer(it, citations) }
}
