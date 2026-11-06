package dev.ambientmemo.engine.domain.spec

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** area の名前と実行役。area は `engine/spec/<area>.json` のファイル名と同じ。 */
internal object SpecAreas {
    val handlers: Map<String, (JsonObject) -> JsonElement> = mapOf(
        "ask-parsers" to ::askParsers,
        "ask-prompts" to ::askPrompts,
        "calendar-date" to ::calendarDate,
        "capture-pipeline" to ::capturePipeline,
        "diagnostics" to ::diagnostics,
        "engine-status" to ::engineStatus,
        "llm-output-parser" to ::llmOutputParser,
        "llm-title-parser" to ::llmTitleParser,
        "memo-question" to ::memoQuestion,
        "model-validation" to ::modelValidation,
        "noise-text-filter" to ::noiseTextFilter,
        "organize-job" to ::organizeJob,
        "organize-policy" to ::organizePolicy,
        "organize-prompts" to ::organizePrompts,
        "quiet-gate" to ::quietGate,
        "segment-backlog" to ::segmentBacklog,
        "self-enrollment" to ::selfEnrollment,
        "speaker-consolidation" to ::speakerWorld,
        "speaker-corrections" to ::speakerWorld,
        "speaker-identifier" to ::speakerWorld,
        "speaker-long-conversation" to ::speakerWorld,
        "speaker-turns" to ::capturePipeline,
        "utterance-chunk" to ::utteranceChunk,
        "utterance-segmenter" to ::utteranceSegmenter,
    )
}
