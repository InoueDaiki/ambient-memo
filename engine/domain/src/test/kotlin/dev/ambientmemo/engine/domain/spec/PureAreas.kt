package dev.ambientmemo.engine.domain.spec

import dev.ambientmemo.engine.domain.CalendarDate
import dev.ambientmemo.engine.domain.CaptureConfig
import dev.ambientmemo.engine.domain.CaptureGap
import dev.ambientmemo.engine.domain.LlmRequest
import dev.ambientmemo.engine.domain.NewUtterance
import dev.ambientmemo.engine.domain.NoiseTextFilter
import dev.ambientmemo.engine.domain.OrganizeConditions
import dev.ambientmemo.engine.domain.OrganizePrompts
import dev.ambientmemo.engine.domain.OrganizeTrigger
import dev.ambientmemo.engine.domain.Transcription
import dev.ambientmemo.engine.domain.currentEngineStatus
import dev.ambientmemo.engine.domain.parseSummary
import dev.ambientmemo.engine.domain.parseTitle
import dev.ambientmemo.engine.domain.stopReason
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.TimeZone

// 純粋な判定と解析の実行役。入力と期待の形は各 JSON の inputSchema と expectedSchema にある

internal fun engineStatus(@Suppress("UNUSED_PARAMETER") input: JsonObject): JsonElement =
    currentEngineStatus().let { obj("engineName" to it.engineName, "apiVersion" to it.apiVersion) }

internal fun noiseTextFilter(input: JsonObject): JsonElement {
    val filter = input.longOrNull("longDurationMillis")?.let(::NoiseTextFilter) ?: NoiseTextFilter()
    return obj("shouldSave" to filter.shouldSave(input.str("text"), input.long("durationMillis")))
}

internal fun organizePolicy(input: JsonObject): JsonElement {
    val conditions = OrganizeConditions(input.bool("stopRequested"), input.bool("isCharging"), input.bool("isTooHot"))
    return obj("stopReason" to input.enum<OrganizeTrigger>("trigger").stopReason(conditions))
}

internal fun calendarDate(input: JsonObject): JsonElement = when (val op = input.str("op")) {
    "of" -> obj("date" to CalendarDate.of(input.long("millis"), timeZone(input)).toString())
    "plusDays" -> obj("date" to date(input, "date").plusDays(input.int("days")).toString())
    "weekday" -> obj("weekdayIndex" to date(input, "date").weekdayIndex)
    "compare" -> obj("order" to Integer.signum(date(input, "first").compareTo(date(input, "second"))))
    "parse" -> obj("date" to CalendarDate.parse(input.str("text"))?.toString())
    "create" -> try {
        obj("date" to CalendarDate(input.int("year"), input.int("month"), input.int("day")).toString())
    } catch (_: IllegalArgumentException) {
        errorJson("invalid-date")
    }
    else -> error("知らない op です: $op")
}

internal fun date(input: JsonObject, key: String): CalendarDate =
    checkNotNull(CalendarDate.parse(input.str(key))) { "$key は YYYY-MM-DD で書きます" }

internal fun timeZone(input: JsonObject): TimeZone = TimeZone.getTimeZone(input.str("timeZone")).also {
    check(it.id == input.str("timeZone")) { "知らないタイムゾーンです: ${input.str("timeZone")}" }
}

internal fun llmOutputParser(input: JsonObject): JsonElement = when (val op = input.str("op")) {
    "summary" -> obj("summary" to parseSummary(input.str("text")))
    else -> error("知らない op です: $op")
}

internal fun llmTitleParser(input: JsonObject): JsonElement = obj("title" to parseTitle(input.str("text")))

internal fun organizePrompts(input: JsonObject): JsonElement = when (val op = input.str("op")) {
    "summary" -> request(OrganizePrompts.summary(date(input, "date"), input.str("transcript")))
    "memoTitle" -> request(OrganizePrompts.memoTitle(input.str("summary")))
    "topics" -> request(OrganizePrompts.topics(input.strings("lines")))
    "retryNotes" -> obj("summary" to OrganizePrompts.SUMMARY_RETRY_NOTE)
    else -> error("知らない op です: $op")
}

internal fun request(request: LlmRequest) =
    obj("system" to request.system, "user" to request.user, "maxOutputTokens" to request.maxOutputTokens)

/** 値の検査だけを見る。作れたら `{"valid": true}`、検査で断られたら `{"error": "invalid-argument"}`。 */
internal fun modelValidation(input: JsonObject): JsonElement {
    val args = input.getValue("args") as JsonObject
    return try {
        when (val type = input.str("type")) {
            "Transcription" -> Transcription(args.str("text"), args.doubleOrNull("confidence"))
            "NewUtterance" -> NewUtterance(
                args.long("startedAtMillis"),
                args.long("endedAtMillis"),
                args.str("text"),
                args.doubleOrNull("confidence"),
                args.long("createdAtMillis"),
                args.longOrNull("speakerId"),
                args.doubleOrNull("speakerConfidence"),
            )
            "CaptureGap" -> CaptureGap(args.long("startedAtMillis"), args.long("endedAtMillis"), args.enum("reason"))
            "CaptureConfig" -> captureConfig(args)
            else -> error("知らない type です: $type")
        }
        obj("valid" to true)
    } catch (_: IllegalArgumentException) {
        errorJson("invalid-argument")
    }
}

/** 書いていない項目は既定値にする。 */
internal fun captureConfig(json: JsonObject?): CaptureConfig {
    val default = CaptureConfig()
    val values = json ?: JsonObject(emptyMap())
    values.requireKeys(
        setOf(
            "maxUtteranceMillis",
            "minSilenceMillis",
            "preRollMillis",
            "minSpeechMillis",
            "maxBacklogMillis",
            "discontinuityToleranceMillis",
        ),
        "captureConfig",
    )
    return CaptureConfig(
        maxUtteranceMillis = values.longOr("maxUtteranceMillis", default.maxUtteranceMillis),
        minSilenceMillis = values.longOr("minSilenceMillis", default.minSilenceMillis),
        preRollMillis = values.longOr("preRollMillis", default.preRollMillis),
        minSpeechMillis = values.longOr("minSpeechMillis", default.minSpeechMillis),
        maxBacklogMillis = values.longOr("maxBacklogMillis", default.maxBacklogMillis),
        discontinuityToleranceMillis = values.longOr(
            "discontinuityToleranceMillis",
            default.discontinuityToleranceMillis,
        ),
    )
}
