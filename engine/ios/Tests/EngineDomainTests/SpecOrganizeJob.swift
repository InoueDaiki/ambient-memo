@testable import EngineDomain
import EngineDomainTesting
import Foundation

// 整理ジョブの実行役。記録はメモリの Fake に持ち、LLM は依頼の種類と本文で応答を決める Fake にする

/// 依頼の種類。FakeLlm.organizing と同じ順で見分ける。
private func requestKind(_ request: LlmRequest) -> String {
    if request.isTopicRequest { return "topics" }
    if request.isTitleRequest { return "title" }
    return "summary"
}

/// `llm` の `rules` の 1 件。書いた条件をすべて満たす最初の規則で応答を決める。
private struct LlmRule: Sendable {
    let kind: String?
    let contains: [String]
    let nthOfKind: Int?
    let calls: Set<Int>?
    let reply: String?
    let failure: String?

    init(_ json: SpecObject) throws {
        try json.requireKeys(["kind", "contains", "nthOfKind", "calls", "reply", "failure"], "rules")
        kind = try json.strOrNull("kind")
        contains = try json.strings("contains")
        nthOfKind = try json.present("nthOfKind").map(specInt)
        calls = try json.present("calls").map { Set(try $0.arrayValue.map(specInt)) }
        reply = try json.strOrNull("reply")
        failure = try json.strOrNull("failure")
        guard (reply == nil) != (failure == nil) else { throw SpecError("rules は reply か failure のどちらかを書きます") }
    }

    func matches(_ request: LlmRequest, kind: String, nthOfKind: Int, call: Int) -> Bool {
        (self.kind == nil || self.kind == kind) &&
            contains.allSatisfy { request.user.kotlinContains($0) } &&
            (self.nthOfKind == nil || self.nthOfKind == nthOfKind) &&
            (calls == nil || calls!.contains(call))
    }

    func respond() throws -> String {
        switch failure {
        case nil: return reply!
        case "failed": throw SpecFailure("llm-failed")
        case "too-long": throw LlmInputTooLongException()
        case let other?: throw SpecError("知らない failure です: \(other)")
        }
    }
}

/// `llm` の形は organize-job.json の inputSchema にある。
private func scriptedLlm(_ json: SpecObject?) throws -> FakeLlm {
    let values = json ?? [:]
    try values.requireKeys(["modelVersion", "summary", "title", "topics", "rules", "probes"], "llm")
    let summary = try values.strOr("summary", "- 要約")
    let title = try values.strOr("title", summary)
    let topics = try values.strOr("topics", "1")
    let rules = try values.objects("rules").map(LlmRule.init)
    let kindCounts = SpecLocked<[String: Int]>([:])
    return FakeLlm(modelVersion: try values.strOr("modelVersion", "fake-1")) { index, request in
        let kind = requestKind(request)
        let nth = kindCounts.withLock { counts in
            defer { counts[kind, default: 0] += 1 }
            return counts[kind, default: 0]
        }
        if let rule = rules.first(where: { $0.matches(request, kind: kind, nthOfKind: nth, call: index) }) {
            return try rule.respond()
        }
        switch kind {
        case "topics": return topics
        case "title": return title
        default: return summary
        }
    }
}

/// 回数をケースの中で共有する。ジョブを何度動かしても同じものを渡す。
private final class SharedAttempts: OrganizeAttempts, @unchecked Sendable {
    private let inner = InMemoryOrganizeAttempts()
    private let lock = NSLock()

    func count(firstUtteranceId: Int64) -> Int {
        lock.withLock { inner.count(firstUtteranceId: firstUtteranceId) }
    }

    func set(firstUtteranceId: Int64, count: Int) {
        lock.withLock { inner.set(firstUtteranceId: firstUtteranceId, count: count) }
    }

    func clear(firstUtteranceId: Int64) {
        lock.withLock { inner.clear(firstUtteranceId: firstUtteranceId) }
    }
}

@Sendable func organizeJob(_ input: SpecObject) async throws -> SpecJSON {
    let world = try SpeakerWorld(input)
    let memos = InMemoryMemoRepository(utterances: world.utterances)
    let llmJSON = try input.objOrNull("llm")
    let llm = try scriptedLlm(llmJSON)
    let probes = try llmJSON?.strings("probes") ?? []
    let zone = try timeZone(input)
    let config = try organizeConfig(try input.objOrNull("config"))
    let attempts = SharedAttempts()
    let job = OrganizeJob(memos: memos, speakers: world.speakers, llm: llm, clock: world.clock, timeZone: zone, config: config, attempts: attempts)

    func step(_ step: SpecObject) async throws -> SpecJSON {
        switch try step.str("op") {
        case "say":
            let start = try step.long("startedAtMillis")
            let end = start + (try step.longOr("durationMillis", 2_000))
            let saved = world.utterances.insert(try NewUtterance(
                startedAtMillis: start,
                endedAtMillis: end,
                text: try step.str("text"),
                confidence: 0.9,
                createdAtMillis: end,
                speakerId: try step.longOrNull("speakerId"),
                speakerConfidence: try step.doubleOrNull("speakerConfidence")
            ))
            return obj(["utteranceId": saved.id])
        case "run":
            let checks = try step.present("continueChecks").map(specInt)
            let checked = SpecLocked(0)
            let progress = SpecLocked<[SpecJSON]>([])
            let result = try await job.run(
                shouldContinue: {
                    let count = checked.withLock { count in
                        count += 1
                        return count
                    }
                    return checks.map { count <= $0 } ?? true
                },
                onProgress: { item in progress.withLock { $0.append(json([item.completedChunks, item.totalChunks])) } }
            )
            return obj([
                "result": obj([
                    "outcome": result.outcome,
                    "memoCount": result.memoCount,
                    "unreadableCount": result.unreadableCount,
                ]),
                "progress": progress.current,
            ])
        case "setAttempts":
            attempts.set(firstUtteranceId: try step.long("utteranceId"), count: try step.int("count"))
            return obj([:])
        case "attempts":
            return obj(["count": attempts.count(firstUtteranceId: try step.long("utteranceId"))])
        case "backlog":
            let backlog = try await organizeBacklog(memos: memos, speakers: world.speakers.listSpeakers(), clock: world.clock, timeZone: zone, config: config)
            return obj(["readyChunks": backlog.readyChunks, "hasOngoing": backlog.hasOngoing])
        case "records":
            var listed: [SpecJSON] = []
            for memo in try await memos.listMemos() {
                listed.append(try await memoJSON(memo, memos))
            }
            return obj(["memos": listed, "unorganized": try await memos.listUnorganizedUtterances().map(\.id)])
        case "llmCalls":
            return obj(["calls": llm.received.map { request in
                obj(["kind": requestKind(request), "matched": probes.filter { request.user.kotlinContains($0) }])
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

private func memoJSON(_ memo: Memo, _ memos: InMemoryMemoRepository) async throws -> SpecJSON {
    return obj([
        "id": memo.id,
        "startedAtMillis": memo.startedAtMillis,
        "endedAtMillis": memo.endedAtMillis,
        "title": memo.title,
        "summary": memo.summary,
        "modelVersion": memo.modelVersion,
        "createdAtMillis": memo.createdAtMillis,
        "utteranceIds": try await memos.listUtteranceIds(memoId: memo.id),
    ])
}
