package dev.ambientmemo.engine.domain.spec

import dev.ambientmemo.engine.domain.normalizedOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.math.abs

// 共通テストの JSON を読み書きする道具。比べ方は engine/spec/README.md の決まりに合わせる

private const val NUMBER_TOLERANCE = 1e-6

/** [expected] と [actual] の違いを、場所の付いた文で返す。同じなら空。 */
internal fun jsonMismatches(expected: JsonElement, actual: JsonElement, path: String = "$"): List<String> =
    when (expected) {
        is JsonNull -> if (actual is JsonNull) emptyList() else listOf("$path: null のはずが $actual")
        is JsonPrimitive -> primitiveMismatches(expected, actual, path)
        is JsonArray -> arrayMismatches(expected, actual, path)
        is JsonObject -> objectMismatches(expected, actual, path)
    }

private fun primitiveMismatches(expected: JsonPrimitive, actual: JsonElement, path: String): List<String> {
    val other = actual as? JsonPrimitive
    val isSame = when {
        other == null || other is JsonNull -> false
        expected.isString || other.isString -> expected.isString && other.isString && expected.content == other.content
        expected.booleanOrNull != null -> expected.booleanOrNull == other.booleanOrNull
        else -> {
            val expectedNumber = expected.doubleOrNull
            val actualNumber = other.doubleOrNull
            expectedNumber != null && actualNumber != null && abs(expectedNumber - actualNumber) <= NUMBER_TOLERANCE
        }
    }
    return if (isSame) emptyList() else listOf("$path: $expected のはずが $actual")
}

private fun arrayMismatches(expected: JsonArray, actual: JsonElement, path: String): List<String> = when {
    actual !is JsonArray -> listOf("$path: 配列のはずが $actual")
    actual.size != expected.size -> listOf("$path: 要素は ${expected.size} 個のはずが ${actual.size} 個")
    else -> expected.indices.flatMap { jsonMismatches(expected[it], actual[it], "$path[$it]") }
}

private fun objectMismatches(expected: JsonObject, actual: JsonElement, path: String): List<String> = when {
    actual !is JsonObject -> listOf("$path: オブジェクトのはずが $actual")
    actual.keys != expected.keys -> listOf("$path: キーは ${expected.keys} のはずが ${actual.keys}")
    else -> expected.keys.flatMap { jsonMismatches(expected.getValue(it), actual.getValue(it), "$path.$it") }
}

/** Kotlin の値を JSON にする。null、文字列、数、真偽、列挙、特徴量、リスト、JSON を受け付ける。 */
internal fun json(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Enum<*> -> JsonPrimitive(value.name)
    is FloatArray -> vectorJson(value)
    is List<*> -> JsonArray(value.map(::json))
    else -> error("JSON にできない値です: $value")
}

internal fun obj(vararg pairs: Pair<String, Any?>): JsonObject =
    JsonObject(pairs.associate { (key, value) -> key to json(value) })

/** 特徴量は 0 でない要素だけを `{"size": 次元, "values": {"添字": 値}}` の形で書く。 */
internal fun vectorJson(vector: FloatArray): JsonObject = JsonObject(
    mapOf(
        "size" to JsonPrimitive(vector.size),
        "values" to JsonObject(
            vector.withIndex().filter { it.value != 0f }.associate { it.index.toString() to JsonPrimitive(it.value) },
        ),
    ),
)

/**
 * 特徴量を読む。数の配列か、`{"size": 次元, "values": {"添字": 値}, "normalize": true}` の形を受け付ける。
 * 後者で書かない要素は 0。`normalize` が true なら長さ 1 に正規化する。値は文字列から直接 Float に読む。
 */
internal fun JsonElement.toVector(): FloatArray = when (this) {
    is JsonArray -> FloatArray(size) { this[it].jsonPrimitive.float }
    is JsonObject -> {
        val vector = FloatArray(int("size"))
        getValue("values").jsonObject.forEach { (index, value) -> vector[index.toInt()] = value.jsonPrimitive.float }
        if (boolOr("normalize", false)) checkNotNull(normalizedOrNull(vector)) { "正規化できない特徴量です" } else vector
    }
    else -> error("特徴量ではありません: $this")
}

internal fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

internal fun JsonObject.strOrNull(key: String): String? =
    get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

internal fun JsonObject.strOr(key: String, default: String): String = strOrNull(key) ?: default

internal fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

internal fun JsonObject.intOr(key: String, default: Int): Int = get(key)?.jsonPrimitive?.int ?: default

internal fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

internal fun JsonObject.longOr(key: String, default: Long): Long = get(key)?.jsonPrimitive?.long ?: default

internal fun JsonObject.longOrNull(key: String): Long? = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.long

internal fun JsonObject.double(key: String): Double = getValue(key).jsonPrimitive.double

internal fun JsonObject.doubleOrNull(key: String): Double? =
    get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.double

internal fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean

internal fun JsonObject.boolOr(key: String, default: Boolean): Boolean = get(key)?.jsonPrimitive?.boolean ?: default

internal fun JsonObject.objOrNull(key: String): JsonObject? = get(key)?.takeUnless { it is JsonNull }?.jsonObject

internal fun JsonObject.objects(key: String): List<JsonObject> = get(key)?.jsonArray?.map { it.jsonObject }.orEmpty()

internal fun JsonObject.strings(key: String): List<String> =
    get(key)?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

internal inline fun <reified T : Enum<T>> JsonObject.enum(key: String): T = enumValueOf(str(key))

/** 知らないキーがあれば失敗にする。書き間違いを、既定値のまま通さないため。 */
internal fun JsonObject.requireKeys(allowed: Set<String>, what: String) {
    val unknown = keys - allowed
    require(unknown.isEmpty()) { "$what に知らないキーがあります: $unknown" }
}

/** 例外を確かめるケースの期待の形。 */
internal fun errorJson(kind: String): JsonObject = obj("error" to kind)
