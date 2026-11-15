@testable import EngineDomain
import Foundation

// 調整の値の実行役。入力と期待の形は tuning.json の inputSchema と expectedSchema にある

@Sendable func tuning(_ input: SpecObject) async throws -> SpecJSON {
    switch try input.str("op") {
    case "keys":
        return .array(TuningKey.allCases.map {
            obj([
                "name": $0.wireName,
                "min": $0.min,
                "max": $0.max,
                "step": $0.step,
                "defaultValue": $0.defaultValue,
            ])
        })
    case "defaults":
        return obj([
            "speakerMatchesDefault": Tuning.DEFAULT.speakerConfig() == SpeakerConfig.default,
            "organizeMatchesDefault": Tuning.DEFAULT.organizeConfig() == OrganizeConfig.default,
        ])
    case "of":
        var values: [String: Double] = [:]
        for (name, value) in try input.obj("values") {
            guard case let .number(text) = value, let number = Double(text) else {
                throw SpecError("\(name) は数で書きます")
            }
            values[name] = number
        }
        do {
            return tuningJSON(try Tuning.of(values))
        } catch let error as InvalidTuningException {
            return errorJSON(error.problem.rawValue)
        }
    case "decode":
        return tuningJSON(Tuning.decode(try input.str("text")))
    case let op:
        throw SpecError("知らない op です: \(op)")
    }
}

private func tuningJSON(_ tuning: Tuning) -> SpecJSON {
    let speaker = tuning.speakerConfig()
    let organize = tuning.organizeConfig()
    var changed: [String: SpecJSON] = [:]
    for (key, value) in tuning.changed { changed[key.wireName] = value.specJSON }
    return obj([
        "changed": SpecJSON.object(changed),
        "encoded": tuning.encode(),
        "speaker": obj([
            "selfThreshold": speaker.selfThreshold,
            "selfExitThreshold": speaker.selfExitThreshold,
            "unknownFloor": speaker.unknownFloor,
            "provisionalThreshold": speaker.provisionalThreshold,
            "consolidationThreshold": speaker.consolidationThreshold,
            "maxProvisionalSpeakers": speaker.maxProvisionalSpeakers,
            "strongSelfThreshold": speaker.strongSelfThreshold,
            "trailingSilenceMillis": speaker.trailingSilenceMillis,
        ]),
        "organize": obj([
            "minTopicLines": organize.minTopicLines,
            "minSilenceGapMillis": organize.minSilenceGapMillis,
        ]),
        "capture": obj([
            "quietGateDbfs": tuning.quietGateDbfs,
            "vadThreshold": tuning.vadThreshold,
            "vadMinSilenceSeconds": tuning.vadMinSilenceSeconds,
        ]),
    ])
}
