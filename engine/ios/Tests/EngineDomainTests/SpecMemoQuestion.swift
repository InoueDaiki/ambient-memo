@testable import EngineDomain
import EngineDomainTesting
import Foundation

// 質問の実行役。メモはメモリの Fake に持ち、LLM は依頼の種類ごとに決めた順で応答する Fake にする

private let UTTERANCE_MILLIS: Int64 = 2_000
private let UTTERANCE_CONFIDENCE = 0.9

@Sendable func askParsers(_ input: SpecObject) async throws -> SpecJSON {
    switch try input.str("op") {
    case "plan":
        let plan = parseSearchPlan(try input.str("text")).map { plan in
            obj(["terms": plan.terms, "from": plan.from?.description, "to": plan.to?.description])
        }
        return obj(["plan": plan])
    case "answer":
        let answer = parseAnswer(try input.str("text"), try input.int("sourceCount")).map { answer in
            obj(["text": answer.text, "citations": answer.citations])
        }
        return obj(["answer": answer])
    case let op:
        throw SpecError("知らない op です: \(op)")
    }
}

@Sendable func askPrompts(_ input: SpecObject) async throws -> SpecJSON {
    switch try input.str("op") {
    case "plan":
        return request(try AskPrompts.plan(try input.str("question"), try date(input, "today"), try input.strings("tried")))
    case "answer":
        return request(AskPrompts.answer(try input.str("question"), try date(input, "today"), try input.str("memos")))
    case "constants":
        return obj(["noAnswer": AskPrompts.NO_ANSWER, "calendarDays": AskPrompts.CALENDAR_DAYS])
    case let op:
        throw SpecError("知らない op です: \(op)")
    }
}

private func askRequestKind(_ request: LlmRequest) -> String {
    request.isSearchPlanRequest ? "plan" : "answer"
}

/// `llm` の形は memo-question.json の inputSchema にある。
private func askingLlm(_ json: SpecObject?) throws -> FakeLlm {
    let values = json ?? [:]
    try values.requireKeys(["plans", "answer", "failOn", "probes"], "llm")
    let plans = try values.strings("plans")
    let answer = try values.strOr("answer", "")
    let failOn = try values.strOrNull("failOn")
    let planCalls = SpecLocked(0)
    return FakeLlm { _, request in
        let kind = askRequestKind(request)
        if kind == failOn { throw SpecFailure("llm-failed") }
        if kind != "plan" { return answer }
        let index = planCalls.withLock { count in
            defer { count += 1 }
            return count
        }
        return index < plans.count ? plans[index] : ""
    }
}

/// 書いていない項目は既定値にする。
private func askConfig(_ json: SpecObject?) throws -> AskConfig {
    let defaults = AskConfig.default
    let values = json ?? [:]
    try values.requireKeys(
        ["maxSearchRounds", "maxSources", "maxContextChars", "candidatesPerTerm", "maxQuotes", "maxQuoteChars"],
        "config"
    )
    return try AskConfig(
        maxSearchRounds: try values.intOr("maxSearchRounds", defaults.maxSearchRounds),
        maxSources: try values.intOr("maxSources", defaults.maxSources),
        maxContextChars: try values.intOr("maxContextChars", defaults.maxContextChars),
        candidatesPerTerm: try values.intOr("candidatesPerTerm", defaults.candidatesPerTerm),
        maxQuotes: try values.intOr("maxQuotes", defaults.maxQuotes),
        maxQuoteChars: try values.intOr("maxQuoteChars", defaults.maxQuoteChars)
    )
}

/// 質問の操作を、話者と発話の世界の操作と一緒に流す。
@Sendable func memoQuestion(_ input: SpecObject) async throws -> SpecJSON {
    let world = try SpeakerWorld(input)
    let memos = InMemoryMemoRepository(utterances: world.utterances)
    let llmJSON = try input.objOrNull("llm")
    let llm = try askingLlm(llmJSON)
    let probes = try llmJSON?.strings("probes") ?? []
    let answerer = MemoQuestionAnswerer(
        memos: memos,
        speakers: world.speakers,
        llm: llm,
        clock: world.clock,
        timeZone: try timeZone(input),
        config: try askConfig(try input.objOrNull("config"))
    )

    /// 発話を保存してからメモにする。発話は開始時刻から 2 秒ずつ続ける。
    func memo(_ step: SpecObject) async throws -> SpecJSON {
        let start = try step.long("startedAtMillis")
        let saved = try step.objects("utterances").enumerated().map { index, utterance in
            let begin = start + Int64(index) * UTTERANCE_MILLIS
            return world.utterances.insert(try NewUtterance(
                startedAtMillis: begin,
                endedAtMillis: begin + UTTERANCE_MILLIS,
                text: try utterance.str("text"),
                confidence: UTTERANCE_CONFIDENCE,
                createdAtMillis: begin + UTTERANCE_MILLIS,
                speakerId: try utterance.longOrNull("speakerId"),
                speakerConfidence: nil
            ))
        }
        let memo = try NewMemo(
            startedAtMillis: start,
            endedAtMillis: saved.last!.endedAtMillis,
            title: try step.strOrNull("title"),
            summary: try step.strOrNull("summary"),
            modelVersion: "fake-1",
            createdAtMillis: world.clock.nowMillis(),
            utteranceIds: saved.map(\.id)
        )
        guard let recorded = try await memos.recordMemo(memo) else { throw SpecError("メモを記録できませんでした") }
        return obj(["memoId": recorded.id])
    }

    func ask(_ step: SpecObject) async throws -> SpecJSON {
        let phases = SpecLocked<[AskPhase]>([])
        do {
            let answer = try await answerer.answer(try step.str("question")) { phase in phases.withLock { $0.append(phase) } }
            return obj([
                "outcome": answer.outcome,
                "text": answer.text,
                "sourceIds": answer.sources.map(\.id),
                "searchRounds": answer.searchRounds,
                "phases": phases.current,
            ])
        } catch is IllegalArgumentException {
            return errorJSON("invalid-argument")
        } catch let error as SpecFailure {
            return obj(["failure": error.kind, "phases": phases.current])
        }
    }

    func step(_ step: SpecObject) async throws -> SpecJSON {
        switch try step.str("op") {
        case "memo":
            return try await memo(step)
        case "ask":
            return try await ask(step)
        case "llmCalls":
            return obj(["calls": llm.received.map { request in
                obj(["kind": askRequestKind(request), "matched": probes.filter { request.user.kotlinContains($0) }])
            }])
        default:
            guard let result = try await world.stepOrNil(step) else { throw SpecError("知らない op です: \(try step.str("op"))") }
            return result
        }
    }

    for item in try input.objects("setup") { _ = try await step(item) }
    var results: [SpecJSON] = []
    for item in try input.objects("steps") { results.append(try await step(item)) }
    return obj(["steps": results])
}
