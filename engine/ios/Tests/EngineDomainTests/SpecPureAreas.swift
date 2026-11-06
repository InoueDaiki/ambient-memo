@testable import EngineDomain
import Foundation

// 純粋な判定と解析の実行役。入力と期待の形は各 JSON の inputSchema と expectedSchema にある

@Sendable func engineStatus(_: SpecObject) async throws -> SpecJSON {
    let status = currentEngineStatus()
    return obj(["engineName": status.engineName, "apiVersion": status.apiVersion])
}

@Sendable func noiseTextFilter(_ input: SpecObject) async throws -> SpecJSON {
    let filter = try input.longOrNull("longDurationMillis").map { NoiseTextFilter(longDurationMillis: $0) } ?? NoiseTextFilter()
    return obj(["shouldSave": filter.shouldSave(try input.str("text"), durationMillis: try input.long("durationMillis"))])
}

@Sendable func organizePolicy(_ input: SpecObject) async throws -> SpecJSON {
    let conditions = OrganizeConditions(
        stopRequested: try input.bool("stopRequested"),
        isCharging: try input.bool("isCharging"),
        isTooHot: try input.bool("isTooHot")
    )
    let trigger: OrganizeTrigger = try input.enumValue("trigger")
    return obj(["stopReason": trigger.stopReason(conditions)])
}

@Sendable func calendarDate(_ input: SpecObject) async throws -> SpecJSON {
    switch try input.str("op") {
    case "of":
        return obj(["date": try CalendarDate.of(try input.long("millis"), try timeZone(input)).description])
    case "plusDays":
        return obj(["date": try date(input, "date").plusDays(try input.int("days")).description])
    case "weekday":
        return obj(["weekdayIndex": try date(input, "date").weekdayIndex])
    case "compare":
        let first = try date(input, "first")
        let second = try date(input, "second")
        return obj(["order": first < second ? -1 : (first == second ? 0 : 1)])
    case "parse":
        return obj(["date": CalendarDate.parse(try input.str("text"))?.description])
    case "create":
        do {
            return obj(["date": try CalendarDate(try input.int("year"), try input.int("month"), try input.int("day")).description])
        } catch is IllegalArgumentException {
            return errorJSON("invalid-date")
        }
    case let op:
        throw SpecError("知らない op です: \(op)")
    }
}

func date(_ input: SpecObject, _ key: String) throws -> CalendarDate {
    guard let date = CalendarDate.parse(try input.str(key)) else { throw SpecError("\(key) は YYYY-MM-DD で書きます") }
    return date
}

func timeZone(_ input: SpecObject) throws -> TimeZone {
    let name = try input.str("timeZone")
    guard let zone = TimeZone(identifier: name) else { throw SpecError("知らないタイムゾーンです: \(name)") }
    return zone
}

@Sendable func llmOutputParser(_ input: SpecObject) async throws -> SpecJSON {
    switch try input.str("op") {
    case "summary":
        return obj(["summary": parseSummary(try input.str("text"))])
    case let op:
        throw SpecError("知らない op です: \(op)")
    }
}

@Sendable func llmTitleParser(_ input: SpecObject) async throws -> SpecJSON {
    obj(["title": parseTitle(try input.str("text"))])
}

@Sendable func organizePrompts(_ input: SpecObject) async throws -> SpecJSON {
    switch try input.str("op") {
    case "summary":
        return request(OrganizePrompts.summary(try date(input, "date"), try input.str("transcript")))
    case "memoTitle":
        return request(OrganizePrompts.memoTitle(try input.str("summary")))
    case "topics":
        return request(OrganizePrompts.topics(try input.strings("lines")))
    case "retryNotes":
        return obj(["summary": OrganizePrompts.SUMMARY_RETRY_NOTE])
    case let op:
        throw SpecError("知らない op です: \(op)")
    }
}

func request(_ request: LlmRequest) -> SpecJSON {
    obj(["system": request.system, "user": request.user, "maxOutputTokens": request.maxOutputTokens])
}

/// 値の検査だけを見る。作れたら `{"valid": true}`、検査で断られたら `{"error": "invalid-argument"}`。
@Sendable func modelValidation(_ input: SpecObject) async throws -> SpecJSON {
    let args = try input.obj("args")
    do {
        switch try input.str("type") {
        case "Transcription":
            _ = try Transcription(try args.str("text"), confidence: try args.doubleOrNull("confidence"))
        case "NewUtterance":
            _ = try NewUtterance(
                startedAtMillis: try args.long("startedAtMillis"),
                endedAtMillis: try args.long("endedAtMillis"),
                text: try args.str("text"),
                confidence: try args.doubleOrNull("confidence"),
                createdAtMillis: try args.long("createdAtMillis"),
                speakerId: try args.longOrNull("speakerId"),
                speakerConfidence: try args.doubleOrNull("speakerConfidence")
            )
        case "CaptureGap":
            _ = try CaptureGap(try args.long("startedAtMillis"), try args.long("endedAtMillis"), try args.enumValue("reason"))
        case "CaptureConfig":
            _ = try captureConfig(args)
        case let type:
            throw SpecError("知らない type です: \(type)")
        }
        return obj(["valid": true])
    } catch is IllegalArgumentException {
        return errorJSON("invalid-argument")
    }
}

/// 書いていない項目は既定値にする。
func captureConfig(_ json: SpecObject?) throws -> CaptureConfig {
    let defaults = CaptureConfig.default
    let values = json ?? [:]
    try values.requireKeys(
        [
            "maxUtteranceMillis",
            "minSilenceMillis",
            "preRollMillis",
            "minSpeechMillis",
            "maxBacklogMillis",
            "discontinuityToleranceMillis",
        ],
        "captureConfig"
    )
    return try CaptureConfig(
        maxUtteranceMillis: try values.longOr("maxUtteranceMillis", defaults.maxUtteranceMillis),
        minSilenceMillis: try values.longOr("minSilenceMillis", defaults.minSilenceMillis),
        preRollMillis: try values.longOr("preRollMillis", defaults.preRollMillis),
        minSpeechMillis: try values.longOr("minSpeechMillis", defaults.minSpeechMillis),
        maxBacklogMillis: try values.longOr("maxBacklogMillis", defaults.maxBacklogMillis),
        discontinuityToleranceMillis: try values.longOr("discontinuityToleranceMillis", defaults.discontinuityToleranceMillis)
    )
}
