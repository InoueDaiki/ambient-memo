package dev.ambientmemo.engine.domain

private val TOPIC_STARTS = Regex("""[\d\s,、，]+""")
private val NUMBER = Regex("""\d+""")
private const val TOPIC_WRAPPERS = "「」\"*# 　"

/**
 * 話題が始まる行の番号を読む。番号は 1 から [lineCount] までに絞り、小さい順に重ねずに並べ、1 を必ず含める。
 * 番号と区切りのほかの文字があれば、読めないものとして null を返す。
 */
internal fun parseTopicStarts(text: String, lineCount: Int): List<Int>? {
    val line = text.withoutWrapping().lines().map { it.trim().trim { char -> char in TOPIC_WRAPPERS } }
        .firstOrNull { it.isNotEmpty() }
    if (line == null || !TOPIC_STARTS.matches(line)) return null
    val starts = NUMBER.findAll(line).mapNotNull { it.value.toIntOrNull() }.filter { it in 1..lineCount }
    return (starts + 1).distinct().sorted().toList()
}
