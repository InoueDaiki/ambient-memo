package dev.ambientmemo.engine.domain


private val THINK_BLOCK = Regex("""<think>.*?(</think>|\z)""", RegexOption.DOT_MATCHES_ALL)
private val CODE_FENCE_LINE = Regex("""^\s*```.*$""", RegexOption.MULTILINE)
internal val BULLET = Regex("""^\s*[-*・]\s*""")

internal fun String.withoutWrapping(): String = replace(THINK_BLOCK, "").replace(CODE_FENCE_LINE, "").trim()

/** 箇条書きの要約を整える。前置きは捨て、行頭は「- 」に揃える。読めるものがなければ null。 */
internal fun parseSummary(text: String): String? {
    val lines = text.withoutWrapping().lines().map { it.trim() }.filter { it.isNotEmpty() }
    val bullets = lines.filter { BULLET.containsMatchIn(it) }.map { "- " + it.replace(BULLET, "") }
    return bullets.ifEmpty { lines }.takeIf { it.isNotEmpty() }?.joinToString("\n")
}
