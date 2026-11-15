package dev.ambientmemo.engine.domain.spec

import dev.ambientmemo.engine.domain.InvalidTuningException
import dev.ambientmemo.engine.domain.OrganizeConfig
import dev.ambientmemo.engine.domain.SpeakerConfig
import dev.ambientmemo.engine.domain.Tuning
import dev.ambientmemo.engine.domain.TuningKey
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// 調整の値の実行役。入力と期待の形は tuning.json の inputSchema と expectedSchema にある

internal fun tuning(input: JsonObject): JsonElement = when (val op = input.str("op")) {
    "keys" -> json(
        TuningKey.entries.map {
            obj(
                "name" to it.wireName,
                "min" to it.min,
                "max" to it.max,
                "step" to it.step,
                "defaultValue" to it.defaultValue,
            )
        },
    )
    "defaults" -> obj(
        "speakerMatchesDefault" to (Tuning.DEFAULT.speakerConfig() == SpeakerConfig()),
        "organizeMatchesDefault" to (Tuning.DEFAULT.organizeConfig() == OrganizeConfig()),
    )
    "of" -> try {
        val values = input.getValue("values").jsonObject.mapValues { it.value.jsonPrimitive.double }
        tuningJson(Tuning.of(values))
    } catch (e: InvalidTuningException) {
        errorJson(e.problem.name)
    }
    "decode" -> tuningJson(Tuning.decode(input.str("text")))
    else -> error("知らない op です: $op")
}

private fun tuningJson(tuning: Tuning): JsonObject {
    val speaker = tuning.speakerConfig()
    val organize = tuning.organizeConfig()
    return obj(
        "changed" to JsonObject(tuning.changed.entries.associate { it.key.wireName to JsonPrimitive(it.value) }),
        "encoded" to tuning.encode(),
        "speaker" to obj(
            "selfThreshold" to speaker.selfThreshold,
            "selfExitThreshold" to speaker.selfExitThreshold,
            "unknownFloor" to speaker.unknownFloor,
            "provisionalThreshold" to speaker.provisionalThreshold,
            "consolidationThreshold" to speaker.consolidationThreshold,
            "maxProvisionalSpeakers" to speaker.maxProvisionalSpeakers,
            "strongSelfThreshold" to speaker.strongSelfThreshold,
            "trailingSilenceMillis" to speaker.trailingSilenceMillis,
        ),
        "organize" to obj(
            "minTopicLines" to organize.minTopicLines,
            "minSilenceGapMillis" to organize.minSilenceGapMillis,
        ),
        "capture" to obj(
            "quietGateDbfs" to tuning.quietGateDbfs,
            "vadThreshold" to tuning.vadThreshold,
            "vadMinSilenceSeconds" to tuning.vadMinSilenceSeconds,
        ),
    )
}
