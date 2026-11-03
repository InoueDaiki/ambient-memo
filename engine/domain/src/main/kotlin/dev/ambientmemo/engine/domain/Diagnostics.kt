package dev.ambientmemo.engine.domain

import kotlin.time.TimeSource

/**
 * 実機の確認のために残す出来事。利用者が書き出して端末の外へ持ち出す値なので、数値と列挙だけを持つ。
 *
 * 文字列の項目を足さない。本文や例外の文言が入る余地をなくすため。
 */
sealed interface DiagnosticEvent

enum class SegmentOutcome {
    SAVED,

    /** 文字起こしの結果が空だった。 */
    BLANK,

    /** 雑音の文字として捨てた。 */
    FILTERED,
    FAILED,
}

/** 発話区間を 1 つ処理した。VAD の発火 1 回にあたる。 */
data class SegmentProcessed(
    val segmentMillis: Long,
    /** 区間の終わりから処理を始めるまでの待ち。 */
    val waitMillis: Long,
    val transcribeMillis: Long,
    /** 話者の判定と保存。話者のロックの待ちを含む。 */
    val saveMillis: Long,
    val textLength: Int,
    val turns: Int,
    val outcome: SegmentOutcome,
) : DiagnosticEvent

data class CaptureGapRecorded(val reason: CaptureGapReason, val gapMillis: Long) : DiagnosticEvent

data class CaptureChanged(val isListening: Boolean, val isInterrupted: Boolean) : DiagnosticEvent

enum class LlmBackend { GPU, CPU }

data class LlmBackendChosen(val backend: LlmBackend) : DiagnosticEvent

data class LlmLoaded(val loadMillis: Long, val isLoaded: Boolean) : DiagnosticEvent

/** [maxOutputTokens] で要約や ToDo とタイトルを見分ける。 */
data class LlmGenerated(
    val inputChars: Int,
    val maxOutputTokens: Int,
    val outputChars: Int,
    val generateMillis: Long,
    val isFailed: Boolean,
) : DiagnosticEvent

data class OrganizeFinished(
    val outcome: OrganizeOutcome,
    val memoCount: Int,
    val todoCount: Int,
    val unreadableCount: Int,
    val totalMillis: Long,
) : DiagnosticEvent

/** 端末とプロセスの状態。得られない値は -1。 */
data class DeviceSample(
    val batteryPercent: Int,
    val chargeMicroAmpereHours: Long,
    val temperatureDeciCelsius: Int,
    val isCharging: Boolean,
    val thermalStatus: Int,
    val isScreenOn: Boolean,
    val isDeviceIdle: Boolean,
    val isPowerSave: Boolean,
    /** 起動からの時間。眠っている間も進む。 */
    val elapsedMillis: Long,
    /** 起動からの時間。眠っている間は止まるので、[elapsedMillis] との差で眠った時間が分かる。 */
    val uptimeMillis: Long,
    val processCpuMillis: Long,
    val rssKilobytes: Long,
    val peakRssKilobytes: Long,
) : DiagnosticEvent

/** 記録の受け口。実装は記録が止まっていれば何もしない。失敗しても聞き取りや整理を止めない。 */
fun interface DiagnosticsLog {
    fun record(event: DiagnosticEvent)

    companion object {
        val NONE = DiagnosticsLog { }
    }
}

/** 欠落を保存するときに記録する。 */
class RecordingCaptureGaps(
    private val delegate: CaptureGapRepository,
    private val diagnostics: DiagnosticsLog,
) : CaptureGapRepository by delegate {
    override suspend fun insert(gap: CaptureGap) {
        delegate.insert(gap)
        diagnostics.record(CaptureGapRecorded(gap.reason, gap.endedAtMillis - gap.startedAtMillis))
    }
}

/** 生成の時間を記録する。入出力は長さだけを見る。 */
class TimedLlm(
    private val delegate: Llm,
    private val diagnostics: DiagnosticsLog,
) : Llm by delegate {
    override suspend fun generate(request: LlmRequest): String {
        val started = TimeSource.Monotonic.markNow()
        var outputChars: Int? = null
        try {
            return delegate.generate(request).also { outputChars = it.length }
        } finally {
            diagnostics.record(
                LlmGenerated(
                    inputChars = request.system.length + request.user.length,
                    maxOutputTokens = request.maxOutputTokens,
                    outputChars = outputChars ?: 0,
                    generateMillis = started.elapsedNow().inWholeMilliseconds,
                    isFailed = outputChars == null,
                ),
            )
        }
    }
}

/** 1 行の JSON にする。項目の名前と値はこのファイルの型からしか来ないので、エスケープは要らない。 */
fun encodeDiagnostic(atMillis: Long, process: DiagnosticProcess, event: DiagnosticEvent): String {
    val fields = listOf("at" to atMillis, "process" to process.name, "event" to event.name) + event.fields()
    return encodeFields(fields)
}

enum class DiagnosticProcess { MAIN, ORGANIZE }

/** 値は数値、真偽、列挙、定数の文字列だけを渡す。 */
fun encodeFields(fields: List<Pair<String, Any>>): String = fields.joinToString(",", "{", "}") { (name, value) ->
    val encoded = when (value) {
        is Number, is Boolean -> value.toString()
        else -> "\"$value\""
    }
    "\"$name\":$encoded"
}

private val DiagnosticEvent.name: String
    get() = when (this) {
        is SegmentProcessed -> "segment"
        is CaptureGapRecorded -> "gap"
        is CaptureChanged -> "capture"
        is LlmBackendChosen -> "llm_backend"
        is LlmLoaded -> "llm_load"
        is LlmGenerated -> "llm_generate"
        is OrganizeFinished -> "organize"
        is DeviceSample -> "device"
        is AudioLevelSummary -> "audio_level"
    }

private fun DiagnosticEvent.fields(): List<Pair<String, Any>> = when (this) {
    is SegmentProcessed -> listOf(
        "segmentMs" to segmentMillis,
        "waitMs" to waitMillis,
        "transcribeMs" to transcribeMillis,
        "saveMs" to saveMillis,
        "textLength" to textLength,
        "turns" to turns,
        "outcome" to outcome.name,
    )
    is CaptureGapRecorded -> listOf("reason" to reason.name, "gapMs" to gapMillis)
    is CaptureChanged -> listOf("listening" to isListening, "interrupted" to isInterrupted)
    is LlmBackendChosen -> listOf("backend" to backend.name)
    is LlmLoaded -> listOf("loadMs" to loadMillis, "loaded" to isLoaded)
    is LlmGenerated -> listOf(
        "inputChars" to inputChars,
        "maxOutputTokens" to maxOutputTokens,
        "outputChars" to outputChars,
        "generateMs" to generateMillis,
        "failed" to isFailed,
    )
    is OrganizeFinished -> listOf(
        "outcome" to outcome.name,
        "memos" to memoCount,
        "todos" to todoCount,
        "unreadable" to unreadableCount,
        "totalMs" to totalMillis,
    )
    is DeviceSample -> listOf(
        "batteryPercent" to batteryPercent,
        "chargeMicroAh" to chargeMicroAmpereHours,
        "temperatureDeciC" to temperatureDeciCelsius,
        "charging" to isCharging,
        "thermalStatus" to thermalStatus,
        "screenOn" to isScreenOn,
        "deviceIdle" to isDeviceIdle,
        "powerSave" to isPowerSave,
        "elapsedMs" to elapsedMillis,
        "uptimeMs" to uptimeMillis,
        "processCpuMs" to processCpuMillis,
        "rssKb" to rssKilobytes,
        "peakRssKb" to peakRssKilobytes,
    )
    is AudioLevelSummary -> listOf(
        "frames" to frames,
        "skippedFrames" to skippedFrames,
        // 帯の下端の dBFS:フレームの数
        "histogram" to histogram.joinToString(" ") { (band, count) -> "$band:$count" },
    )
}
