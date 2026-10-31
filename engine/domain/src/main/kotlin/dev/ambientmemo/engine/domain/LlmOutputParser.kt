package dev.ambientmemo.engine.domain

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** LLM が出した ToDo の1件。値はまだ検証していない。 */
internal data class RawTodo(
    val task: String,
    val kind: String?,
    val due: String?,
)

/** [isComplete] が false なら、[items] は読めた分だけで、欠けているかもしれない。 */
internal data class ParsedTodos(
    val items: List<RawTodo>,
    val isComplete: Boolean,
)

private val THINK_BLOCK = Regex("""<think>.*?(</think>|\z)""", RegexOption.DOT_MATCHES_ALL)
private val CODE_FENCE_LINE = Regex("""^\s*```.*$""", RegexOption.MULTILINE)
private val BULLET = Regex("""^\s*[-*・]\s*""")
private val JSON_START = charArrayOf('{', '[')
private const val ITEMS_KEY = "items"

@OptIn(ExperimentalSerializationApi::class) // allowTrailingComma
private val lenientJson = Json {
    isLenient = true
    allowTrailingComma = true
}

private fun String.withoutWrapping(): String = replace(THINK_BLOCK, "").replace(CODE_FENCE_LINE, "").trim()

/** 箇条書きの要約を整える。前置きは捨て、行頭は「- 」に揃える。読めるものがなければ null。 */
internal fun parseSummary(text: String): String? {
    val lines = text.withoutWrapping().lines().map { it.trim() }.filter { it.isNotEmpty() }
    val bullets = lines.filter { BULLET.containsMatchIn(it) }.map { "- " + it.replace(BULLET, "") }
    return bullets.ifEmpty { lines }.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

/**
 * 自由形式の出力から ToDo を読む。読めなければ null。
 *
 * 検証の E4B はほぼ素直な JSON を出すが、前後の説明、コードブロック、末尾のカンマ、
 * 出力の上限での打ち切りには耐える。打ち切られたら、閉じている要素までを読む。
 */
internal fun parseTodos(text: String): ParsedTodos? {
    val cleaned = text.withoutWrapping()
    var start = cleaned.indexOfAny(JSON_START)
    var sawBroken = false
    while (start >= 0) {
        val region = scanRegion(cleaned, start)
        val element = region?.let { parseJsonOrNull(it.json) }
        val items = element?.todoElements()
        if (region != null && items != null) {
            val parsed = items.mapNotNull(::toRawTodo)
            return ParsedTodos(parsed, !sawBroken && !region.isTruncated && parsed.size == items.size)
        }
        sawBroken = sawBroken || element == null
        val next = if (element != null) region.endIndex else start + 1
        start = cleaned.indexOfAny(JSON_START, next)
    }
    return null
}

private fun parseJsonOrNull(json: String): JsonElement? = try {
    lenientJson.parseToJsonElement(json)
} catch (_: SerializationException) {
    null
}

private fun JsonElement.todoElements(): JsonArray? = when (this) {
    is JsonObject -> this[ITEMS_KEY] as? JsonArray
    is JsonArray -> takeIf { array -> array.all { it is JsonObject } }
    is JsonPrimitive -> null
}

private fun toRawTodo(element: JsonElement): RawTodo? {
    val item = element as? JsonObject
    val task = item?.stringOrNull("task")?.trim().orEmpty()
    return if (item == null || task.isEmpty()) {
        null
    } else {
        RawTodo(task, item.stringOrNull("kind"), item.stringOrNull("due"))
    }
}

private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private class JsonRegion(
    val json: String,
    val endIndex: Int,
    val isTruncated: Boolean,
)

/** [start] の括弧に対応する閉じ括弧までを取り出す。閉じていなければ、閉じた要素までで括弧を補う。 */
private fun scanRegion(text: String, start: Int): JsonRegion? {
    val closers = ArrayDeque<Char>()
    var inString = false
    var isEscaped = false
    var safeEnd = -1
    var safeClosers = emptyList<Char>()
    for (index in start until text.length) {
        val char = text[index]
        when {
            isEscaped -> isEscaped = false
            inString && char == '\\' -> isEscaped = true
            char == '"' -> inString = !inString
            inString -> Unit
            char == '{' -> closers.addLast('}')
            char == '[' -> closers.addLast(']')
            char == '}' || char == ']' -> {
                closers.removeLast()
                if (closers.isEmpty()) return JsonRegion(text.substring(start, index + 1), index + 1, false)
                safeEnd = index + 1
                safeClosers = closers.reversed()
            }
        }
    }
    return if (safeEnd < 0) {
        null
    } else {
        JsonRegion(text.substring(start, safeEnd) + safeClosers.joinToString(""), safeEnd, true)
    }
}
