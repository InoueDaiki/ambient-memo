@testable import EngineDomain
import Foundation

// 発話を整理のまとまりと文字起こしの行にする処理の実行役

@Sendable func utteranceChunk(_ input: SpecObject) async throws -> SpecJSON {
    let zone = try timeZone(input)
    let labels = SpeakerLabels(try input.objects("speakers").map(speaker))
    let utterances = try input.objects("utterances").map(utterance)
    switch try input.str("op") {
    case "line":
        guard utterances.count == 1 else { throw SpecError("line は発話を 1 件だけ書きます") }
        return obj(["line": utterances[0].toTranscriptLine(zone, labels)])
    case "transcript":
        let chunk = UtteranceChunk(utterances)
        return obj([
            "transcript": chunk.toTranscript(zone, labels),
            "isBlank": chunk.isBlank,
            "startedAtMillis": chunk.startedAtMillis,
            "endedAtMillis": chunk.endedAtMillis,
            "utteranceIds": chunk.utterances.map(\.id),
        ])
    case "split":
        let config = try organizeConfig(try input.objOrNull("config"))
        let chunks = splitIntoChunks(utterances, config, zone, labels)
        let kept = try input.longOrNull("nowMillis").map { chunks.withoutOngoing($0, config) } ?? chunks
        return obj(["chunks": kept.map { $0.utterances.map(\.id) }])
    case let op:
        throw SpecError("知らない op です: \(op)")
    }
}

func speaker(_ json: SpecObject) throws -> Speaker {
    Speaker(
        try json.long("id"),
        try json.str("displayName"),
        try json.enumValue("nameSource"),
        try json.bool("isSelf"),
        try json.longOr("createdAtMillis", 0)
    )
}

/// 文字起こしの信頼度は nil、保存の時刻は終わりの時刻にする。整理のまとまりはどちらも見ない。
func utterance(_ json: SpecObject) throws -> Utterance {
    Utterance(
        id: try json.long("id"),
        startedAtMillis: try json.long("startedAtMillis"),
        endedAtMillis: try json.long("endedAtMillis"),
        text: try json.str("text"),
        confidence: nil,
        createdAtMillis: try json.long("endedAtMillis"),
        speakerId: try json.longOrNull("speakerId")
    )
}

/// 書いていない項目は既定値にする。
func organizeConfig(_ json: SpecObject?) throws -> OrganizeConfig {
    let defaults = OrganizeConfig.default
    let values = json ?? [:]
    try values.requireKeys(["minSilenceGapMillis", "maxChunkChars", "maxRetries", "maxChunkAttempts"], "config")
    return try OrganizeConfig(
        minSilenceGapMillis: try values.longOr("minSilenceGapMillis", defaults.minSilenceGapMillis),
        maxChunkChars: try values.intOr("maxChunkChars", defaults.maxChunkChars),
        maxRetries: try values.intOr("maxRetries", defaults.maxRetries),
        maxChunkAttempts: try values.intOr("maxChunkAttempts", defaults.maxChunkAttempts)
    )
}
