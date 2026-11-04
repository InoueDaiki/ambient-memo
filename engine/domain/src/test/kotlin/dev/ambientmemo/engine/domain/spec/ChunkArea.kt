package dev.ambientmemo.engine.domain.spec

import dev.ambientmemo.engine.domain.OrganizeConfig
import dev.ambientmemo.engine.domain.Speaker
import dev.ambientmemo.engine.domain.SpeakerLabels
import dev.ambientmemo.engine.domain.Utterance
import dev.ambientmemo.engine.domain.UtteranceChunk
import dev.ambientmemo.engine.domain.splitIntoChunks
import dev.ambientmemo.engine.domain.toTranscript
import dev.ambientmemo.engine.domain.toTranscriptLine
import dev.ambientmemo.engine.domain.withoutOngoing
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// 発話を整理のまとまりと文字起こしの行にする処理の実行役

internal fun utteranceChunk(input: JsonObject): JsonElement {
    val zone = timeZone(input)
    val labels = SpeakerLabels(input.objects("speakers").map(::speaker))
    val utterances = input.objects("utterances").map(::utterance)
    return when (val op = input.str("op")) {
        "line" -> obj("line" to utterances.single().toTranscriptLine(zone, labels))
        "transcript" -> UtteranceChunk(utterances).let { chunk ->
            obj(
                "transcript" to chunk.toTranscript(zone, labels),
                "isBlank" to chunk.isBlank,
                "startedAtMillis" to chunk.startedAtMillis,
                "endedAtMillis" to chunk.endedAtMillis,
                "utteranceIds" to chunk.utterances.map { it.id },
            )
        }
        "split" -> {
            val config = organizeConfig(input.objOrNull("config"))
            val chunks = splitIntoChunks(utterances, config, zone, labels)
            val kept = input.longOrNull("nowMillis")?.let { chunks.withoutOngoing(it, config) } ?: chunks
            obj("chunks" to kept.map { chunk -> chunk.utterances.map { it.id } })
        }
        else -> error("知らない op です: $op")
    }
}

internal fun speaker(json: JsonObject) = Speaker(
    json.long("id"),
    json.str("displayName"),
    json.enum("nameSource"),
    json.bool("isSelf"),
    json.longOr("createdAtMillis", 0),
)

/** 文字起こしの信頼度は null、保存の時刻は終わりの時刻にする。整理のまとまりはどちらも見ない。 */
internal fun utterance(json: JsonObject) = Utterance(
    id = json.long("id"),
    startedAtMillis = json.long("startedAtMillis"),
    endedAtMillis = json.long("endedAtMillis"),
    text = json.str("text"),
    confidence = null,
    createdAtMillis = json.long("endedAtMillis"),
    speakerId = json.longOrNull("speakerId"),
)

internal fun organizeConfig(json: JsonObject?): OrganizeConfig {
    val default = OrganizeConfig()
    val values = json ?: JsonObject(emptyMap())
    values.requireKeys(setOf("minSilenceGapMillis", "maxChunkChars"), "config")
    return default.copy(
        minSilenceGapMillis = values.longOr("minSilenceGapMillis", default.minSilenceGapMillis),
        maxChunkChars = values.intOr("maxChunkChars", default.maxChunkChars),
    )
}
