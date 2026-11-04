@testable import EngineDomain
import EngineDomainTesting
import Foundation

// 話者の記録をメモリに持つ世界。話者の判定、まとめ直し、手の修正、声の登録、聞き取りの流れ、整理が共有する

private let LISTED_EMBEDDINGS = 100
private let DEFAULT_MODEL = "fake-1"

/// JSON の `embedder` から、呼ばれた順の番号で応答を決める Fake を作る。
func scriptedEmbedder(_ json: SpecObject?, _ defaultModel: String) throws -> FakeSpeakerEmbedder {
    let model = try json?.strOrNull("modelVersion") ?? defaultModel
    guard let json, let given = json["responses"] else { return FakeSpeakerEmbedder(modelVersion: model) }
    let isCycling = try json.boolOr("cycle", false)
    // 応答は先に読んでおく。読めないものは、呼ばれたときに失敗にする
    let responses: [Result<[Float]?, SpecFailure>] = try given.arrayValue.map { response in
        switch response {
        case .null: return .success(nil)
        case .string("fail"): return .failure(SpecFailure("embed-failed"))
        default: return .success(try toVector(response))
        }
    }
    return FakeSpeakerEmbedder(modelVersion: model) { index, _ in
        let position = isCycling ? index % responses.count : index
        guard position < responses.count else { throw SpecError("embedder の \(index) 回目の応答がありません") }
        return try responses[position].get()
    }
}

private let SPEAKER_CONFIG_KEYS: Set<String> = [
    "selfThreshold", "selfExitThreshold", "unknownFloor", "provisionalThreshold", "maxProvisionalSpeakers",
    "minNewSpeakerRunMillis", "staleProvisionalMillis", "consolidationThreshold", "strongSelfThreshold",
    "rivalMargin", "selfOverrideMargin", "adaptMinScore", "adaptMinEnrolledScore", "adaptRivalMargin",
    "adaptMinRunMillis", "adaptMaxBoost", "adaptBaseMillis", "adaptMaxMillis", "windowMillis", "hopMillis",
    "trailingSilenceMillis", "minWindowMillis", "minReliableMillis", "minReliableRunMillis", "maxComparedSpeakers",
]

/// 書いていない項目は既定値にする。Float の項目は文字列から直接 Float に読む。
func speakerConfig(_ json: SpecObject?) throws -> SpeakerConfig {
    let d = SpeakerConfig.default
    let v = json ?? [:]
    let f = { (key: String, defaultValue: Float) throws -> Float in try v[key].map(parseFloat) ?? defaultValue }
    let l = { (key: String, defaultValue: Int64) throws -> Int64 in try v.longOr(key, defaultValue) }
    let i = { (key: String, defaultValue: Int) throws -> Int in try v.intOr(key, defaultValue) }
    let config = try SpeakerConfig(
        selfThreshold: try f("selfThreshold", d.selfThreshold),
        selfExitThreshold: try f("selfExitThreshold", d.selfExitThreshold),
        unknownFloor: try f("unknownFloor", d.unknownFloor),
        provisionalThreshold: try f("provisionalThreshold", d.provisionalThreshold),
        maxProvisionalSpeakers: try i("maxProvisionalSpeakers", d.maxProvisionalSpeakers),
        minNewSpeakerRunMillis: try l("minNewSpeakerRunMillis", d.minNewSpeakerRunMillis),
        staleProvisionalMillis: try l("staleProvisionalMillis", d.staleProvisionalMillis),
        consolidationThreshold: try f("consolidationThreshold", d.consolidationThreshold),
        strongSelfThreshold: try f("strongSelfThreshold", d.strongSelfThreshold),
        rivalMargin: try f("rivalMargin", d.rivalMargin),
        selfOverrideMargin: try f("selfOverrideMargin", d.selfOverrideMargin),
        adaptMinScore: try f("adaptMinScore", d.adaptMinScore),
        adaptMinEnrolledScore: try f("adaptMinEnrolledScore", d.adaptMinEnrolledScore),
        adaptRivalMargin: try f("adaptRivalMargin", d.adaptRivalMargin),
        adaptMinRunMillis: try l("adaptMinRunMillis", d.adaptMinRunMillis),
        adaptMaxBoost: try f("adaptMaxBoost", d.adaptMaxBoost),
        adaptBaseMillis: try l("adaptBaseMillis", d.adaptBaseMillis),
        adaptMaxMillis: try l("adaptMaxMillis", d.adaptMaxMillis),
        windowMillis: try l("windowMillis", d.windowMillis),
        hopMillis: try l("hopMillis", d.hopMillis),
        trailingSilenceMillis: try l("trailingSilenceMillis", d.trailingSilenceMillis),
        minWindowMillis: try l("minWindowMillis", d.minWindowMillis),
        minReliableMillis: try l("minReliableMillis", d.minReliableMillis),
        minReliableRunMillis: try l("minReliableRunMillis", d.minReliableRunMillis),
        maxComparedSpeakers: try i("maxComparedSpeakers", d.maxComparedSpeakers)
    )
    try v.requireKeys(SPEAKER_CONFIG_KEYS, "speakerConfig")
    return config
}

/**
 * 話者の記録と、それを使う処理をまとめて持つ。[input] の `modelVersion`、`clockMillis`、`speakerConfig`、
 * `recentVoicesCapacity`、`embedder` から作る。
 */
final class SpeakerWorld: Sendable {
    let model: String
    let clock: FakeClock
    let config: SpeakerConfig
    let utterances = InMemoryUtteranceRepository()
    let speakers: InMemorySpeakerRepository
    let voices: RecentVoices
    let embedder: FakeSpeakerEmbedder
    private let log = InMemorySpeakerDecisionLog()
    let identifier: SpeakerIdentifier
    private let corrections: SpeakerCorrections

    init(_ input: SpecObject) throws {
        model = try input.strOr("modelVersion", DEFAULT_MODEL)
        clock = FakeClock(try input.longOr("clockMillis", 0))
        config = try speakerConfig(try input.objOrNull("speakerConfig"))
        speakers = InMemorySpeakerRepository(utterances: utterances)
        voices = try input.longOrNull("recentVoicesCapacity").map { RecentVoices(capacity: Int($0)) } ?? RecentVoices()
        embedder = try scriptedEmbedder(try input.objOrNull("embedder"), model)
        identifier = SpeakerIdentifier(embedder: embedder, speakers: speakers, clock: clock, config: config, decisionLog: log)
        corrections = SpeakerCorrections(
            speakers: speakers,
            utterances: utterances,
            voices: voices,
            modelVersion: model,
            clock: clock,
            config: config
        )
    }

    /// `setup` を結果を見ずに流す。
    func setUp(_ input: SpecObject) async throws {
        for step in try input.objects("setup") { _ = try await self.step(step) }
    }

    /// 操作を 1 つ流す。知らない op なら nil を返し、呼び出し側が自分の操作として扱えるようにする。
    func stepOrNil(_ step: SpecObject) async throws -> SpecJSON? {
        switch try step.str("op") {
        case "enrollSelf":
            let saved = speakers.saveSelfEmbedding(try embedding(step), sampleCount: try step.intOr("sampleCount", 3), nowMillis: try now(step))
            return obj(["speakerId": saved.id])
        case "createProvisional":
            let created = speakers.createProvisionalSpeaker(
                try embedding(step),
                nowMillis: try now(step),
                voicedMillis: try step.longOr("voicedMillis", 0)
            )
            return obj(["speakerId": created.id])
        case "setManualName":
            return obj(["ok": speakers.setManualName(speakerId: try step.long("speakerId"), name: try step.str("name"))])
        case "setInferredName":
            return obj(["ok": speakers.setInferredName(speakerId: try step.long("speakerId"), name: try step.str("name"))])
        case "saveSelfAdaptation":
            let vector = try toVector(try step.value("vector"))
            speakers.saveSelfAdaptation(SelfAdaptation(vector, try step.long("voicedMillis"), try modelOf(step)), nowMillis: try now(step))
            return obj([:])
        case "insertUtterance":
            return obj(["utteranceId": utterances.insert(try newUtterance(step)).id])
        case "advanceClock":
            clock.advanceBy(try step.long("millis"))
            return obj([:])
        case "rememberVoice":
            let voice = TurnVoice(try toVector(try step.value("vector")), try step.long("voicedMillis"))
            voices.remember(try step.long("utteranceId"), voice)
            return obj([:])
        case "takeVoice":
            return obj(["voice": voices.take(try step.long("utteranceId")).map(voiceJSON)])
        case "identify":
            return try await identify(step)
        case "consolidate":
            return obj(["merged": try await SpeakerConsolidation(speakers: speakers, modelVersion: model, clock: clock, config: config).run()])
        case "reassign":
            return obj(["ok": try await corrections.reassign(try step.long("utteranceId"), try target(try step.value("target")))])
        case "merge":
            return obj(["ok": try await corrections.merge(try step.long("fromId"), try step.long("intoId"))])
        case "rename":
            return obj(["ok": try await corrections.rename(try step.long("speakerId"), try step.str("name"))])
        case "snapshot":
            return try await snapshot(try step.strings("include"))
        default:
            return nil
        }
    }

    func step(_ step: SpecObject) async throws -> SpecJSON {
        guard let result = try await stepOrNil(step) else { throw SpecError("知らない op です: \(try step.str("op"))") }
        return result
    }

    private func now(_ step: SpecObject) throws -> Int64 {
        try step.longOr("nowMillis", 0)
    }

    private func modelOf(_ step: SpecObject) throws -> String {
        try step.strOr("modelVersion", model)
    }

    private func embedding(_ step: SpecObject) throws -> SpeakerEmbedding {
        SpeakerEmbedding(try toVector(try step.value("vector")), try modelOf(step))
    }

    private func newUtterance(_ step: SpecObject) throws -> NewUtterance {
        try NewUtterance(
            startedAtMillis: try step.longOr("startedAtMillis", 0),
            endedAtMillis: try step.longOr("endedAtMillis", 1),
            text: try step.strOr("text", "本文"),
            confidence: try step.doubleOrNull("confidence"),
            createdAtMillis: try step.longOr("createdAtMillis", 1),
            speakerId: try step.longOrNull("speakerId"),
            speakerConfidence: step["speakerConfidence"] != nil ? try step.doubleOrNull("speakerConfidence") : 0.5
        )
    }

    private func target(_ json: SpecJSON) throws -> SpeakerTarget {
        switch json {
        case let .object(values): return .Existing(speakerId: try values.long("existing"))
        case .string("self"): return .Myself
        case .string("unknown"): return .Unknown
        case .string("newOther"): return .NewOther
        default: throw SpecError("知らない target です: \(json)")
        }
    }

    /// 区間の音声は 0 で、特徴量は `embedder` の応答で決まる。`save` なら聞き取りと同じく保存し、声を覚える。
    private func identify(_ step: SpecObject) async throws -> SpecJSON {
        let count = try step.intOr("repeat", 1)
        var calls: [[SpeakerTurn]] = []
        for _ in 0..<count { calls.append(try await identifyOnce(step)) }
        if step["repeat"] != nil {
            return obj(["speakerIds": try calls.map { turns -> Int64? in
                guard turns.count == 1 else { throw SpecError("repeat は 1 行だけ返る区間に使います") }
                return turns[0].speaker?.speakerId
            }])
        }
        return obj(["turns": calls[0].map(turnJSON)])
    }

    private func identifyOnce(_ step: SpecObject) async throws -> [SpeakerTurn] {
        let start = try step.longOr("startedAtMillis", 0)
        let millis = try step.long("millis")
        let samples = try step.intOr("samples", Int(millis * Int64(TEST_SAMPLE_RATE_HZ) / 1_000))
        let segment = SpeechSegment(
            startedAtMillis: start,
            endedAtMillis: start + millis,
            sampleRateHz: TEST_SAMPLE_RATE_HZ,
            samples: [Int16](repeating: 0, count: samples)
        )
        let tokens = try step["tokens"].map { try $0.arrayValue.map(token) } ?? []
        let text = try Transcription(try step.strOr("text", "本文"), confidence: try step.doubleOrNull("confidence"))
        let transcription = TimedTranscription(text, tokens)
        let turns = try await segment.use { try await identifier.identify($0, transcription) }
        if try step.boolOr("save", false) {
            let saved = utterances.insertAll(try turns.map { try $0.toNewUtterance(transcription, clock.nowMillis()) })
            for (row, turn) in zip(saved, turns) {
                if let voice = turn.voice { voices.remember(row.id, voice) }
            }
        }
        return turns
    }

    private func snapshot(_ include: [String]) async throws -> SpecJSON {
        var parts: [String: SpecJSON] = [:]
        for part in include {
            switch part {
            case "speakers":
                parts[part] = .array(speakers.listSpeakers().map(speakerJSON))
            case "embeddings":
                parts[part] = .array(try speakers.listEmbeddings(modelVersion: model, limit: LISTED_EMBEDDINGS).map(embeddingJSON))
            case "adaptation":
                parts[part] = speakers.findSelfAdaptation(modelVersion: model)
                    .map { obj(["vector": vectorJSON($0.vector), "voicedMillis": $0.voicedMillis]) } ?? .null
            case "utterances":
                parts[part] = .array(utterances.listOverlapping(fromMillis: Int64.min, toMillis: Int64.max).map(utteranceJSON))
            case "embedderCalls":
                parts[part] = .array(embedder.received.map(segmentCallJSON))
            case "diagnostics":
                parts[part] = try await diagnosticsJSON()
            default:
                throw SpecError("知らない include です: \(part)")
            }
        }
        return .object(parts)
    }

    private func diagnosticsJSON() async throws -> SpecJSON {
        let read = try await SpeakerDiagnosticsReader(log: log, speakers: speakers, modelVersion: model, clock: clock, config: config).read()
        return obj([
            "days": read.days,
            "selfRuns": read.selfRuns,
            "otherRuns": read.otherRuns,
            "unknownRuns": read.unknownRuns,
            "selfMillis": read.selfMillis,
            "otherMillis": read.otherMillis,
            "unknownMillis": read.unknownMillis,
            "histogram": read.histogram.map { band in
                obj(["floor": band.floor, "selfRuns": band.selfRuns, "otherRuns": band.otherRuns, "unknownRuns": band.unknownRuns])
            },
            "provisionalSpeakers": read.provisionalSpeakers,
            "hasAdaptedVoice": read.hasAdaptedVoice,
        ])
    }
}

/// 区間ごとに world を作って流す。
@Sendable func speakerWorld(_ input: SpecObject) async throws -> SpecJSON {
    let world = try SpeakerWorld(input)
    try await world.setUp(input)
    var results: [SpecJSON] = []
    for step in try input.objects("steps") { results.append(try await world.step(step)) }
    return obj(["steps": results])
}

func token(_ json: SpecJSON) throws -> TranscriptToken {
    let pair = try json.arrayValue
    guard pair.count == 2 else { throw SpecError("トークンは [文字, ミリ秒] で書きます") }
    return TranscriptToken(try pair[0].content, try specLong(pair[1]))
}

extension SpeakerTurn {
    func toNewUtterance(_ transcription: TimedTranscription, _ createdAtMillis: Int64) throws -> NewUtterance {
        try NewUtterance(
            startedAtMillis: startedAtMillis,
            endedAtMillis: endedAtMillis,
            text: text,
            confidence: transcription.transcription.confidence,
            createdAtMillis: createdAtMillis,
            speakerId: speaker?.speakerId,
            speakerConfidence: speaker?.confidence
        )
    }
}

private func turnJSON(_ turn: SpeakerTurn) -> SpecJSON {
    obj([
        "startedAtMillis": turn.startedAtMillis,
        "endedAtMillis": turn.endedAtMillis,
        "text": turn.text,
        "speakerId": turn.speaker?.speakerId,
        "confidence": turn.speaker?.confidence,
        "voice": turn.voice.map(voiceJSON),
    ])
}

private func voiceJSON(_ voice: TurnVoice) -> SpecJSON {
    obj(["vector": vectorJSON(voice.vector), "voicedMillis": voice.voicedMillis])
}

func speakerJSON(_ speaker: Speaker) -> SpecJSON {
    obj(["id": speaker.id, "displayName": speaker.displayName, "nameSource": speaker.nameSource, "isSelf": speaker.isSelf])
}

func embeddingJSON(_ stored: StoredSpeakerEmbedding) -> SpecJSON {
    obj([
        "speakerId": stored.speakerId,
        "isSelf": stored.isSelf,
        "vector": vectorJSON(stored.vector),
        "sampleCount": stored.sampleCount,
        "voicedMillis": stored.voicedMillis,
        "updatedAtMillis": stored.updatedAtMillis,
    ])
}

func utteranceJSON(_ row: Utterance) -> SpecJSON {
    obj([
        "id": row.id,
        "startedAtMillis": row.startedAtMillis,
        "endedAtMillis": row.endedAtMillis,
        "text": row.text,
        "confidence": row.confidence,
        "createdAtMillis": row.createdAtMillis,
        "speakerId": row.speakerId,
        "speakerConfidence": row.speakerConfidence,
    ])
}

func segmentCallJSON(_ segment: SpeechSegment) -> SpecJSON {
    obj([
        "startedAtMillis": segment.startedAtMillis,
        "endedAtMillis": segment.endedAtMillis,
        "sampleCount": segment.sampleCount,
        "released": segment.isReleased,
    ])
}
