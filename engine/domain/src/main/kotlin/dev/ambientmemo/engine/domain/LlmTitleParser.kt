package dev.ambientmemo.engine.domain

private val TITLE_LABEL = Regex("""^(タイトル|題名)\s*[:：]\s*""")
private const val TITLE_WRAPPERS = "「」『』\"*# 　"
private val DATE_ONLY = Regex("""[\d\s年月日曜火水木金土/()（）-]+""")

/** 自由形式の出力からタイトルを読む。読めないか、日付だけなら null。 */
internal fun parseTitle(text: String): String? =
    text.withoutWrapping().lines().map(::cleanTitle).firstOrNull { it.isNotEmpty() }?.takeUnless(::isDateOnly)

/** 飾りを外した題名の候補を返す。 */
internal fun cleanTitle(line: String): String =
    line.trim().replace(BULLET, "").replace(TITLE_LABEL, "").trim { it in TITLE_WRAPPERS }

/** 話題が分からない題名を見分ける。 */
internal fun isDateOnly(title: String): Boolean = DATE_ONLY.matches(title)
