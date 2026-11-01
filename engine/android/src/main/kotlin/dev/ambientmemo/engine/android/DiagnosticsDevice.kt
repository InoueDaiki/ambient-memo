package dev.ambientmemo.engine.android

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.content.FileProvider
import dev.ambientmemo.engine.domain.DeviceSample
import dev.ambientmemo.engine.domain.SpeakerDiagnostics
import dev.ambientmemo.engine.domain.currentEngineStatus
import dev.ambientmemo.engine.domain.encodeFields
import dev.ambientmemo.engine.sherpa.ModelCatalog
import java.io.File
import java.util.TimeZone

internal const val DIAGNOSTICS_EXPORT_DIRECTORY = "diagnostics-export"

private const val PERCENT = 100
private const val MILLIS_PER_MINUTE = 60_000
private const val UNAVAILABLE = -1

/** 端末とこのプロセスの今の状態を読む。 */
internal fun deviceSample(context: Context): DeviceSample {
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, UNAVAILABLE) ?: UNAVAILABLE
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, UNAVAILABLE) ?: UNAVAILABLE
    val power = context.getSystemService(PowerManager::class.java)
    val memory = processMemory()
    return DeviceSample(
        batteryPercent = if (level >= 0 && scale > 0) level * PERCENT / scale else UNAVAILABLE,
        chargeMicroAmpereHours = context.getSystemService(BatteryManager::class.java)
            .getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
        temperatureDeciCelsius = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, UNAVAILABLE) ?: UNAVAILABLE,
        isCharging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
        thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power.currentThermalStatus else UNAVAILABLE,
        isScreenOn = power.isInteractive,
        isDeviceIdle = power.isDeviceIdleMode,
        isPowerSave = power.isPowerSaveMode,
        elapsedMillis = SystemClock.elapsedRealtime(),
        uptimeMillis = SystemClock.uptimeMillis(),
        processCpuMillis = Process.getElapsedCpuTime(),
        rssKilobytes = memory["VmRSS"] ?: UNAVAILABLE.toLong(),
        peakRssKilobytes = memory["VmHWM"] ?: UNAVAILABLE.toLong(),
    )
}

/** `/proc/self/status` のメモリの行。値は kB。 */
private fun processMemory(): Map<String, Long> = runCatching {
    File("/proc/self/status").readLines().mapNotNull { line ->
        val parts = line.split(":", limit = 2)
        val value = parts.getOrNull(1)?.trim()?.substringBefore(' ')?.toLongOrNull()
        value?.let { parts[0] to it }
    }.toMap()
}.getOrDefault(emptyMap())

/** 書き出しの 1 行目。読むときに要る版と端末の種類だけを持つ。 */
internal fun diagnosticsHeader(context: Context): String {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    return encodeFields(
        listOf(
            "event" to "header",
            "format" to 1,
            "engineApi" to currentEngineStatus().apiVersion,
            "appVersion" to packageInfo.versionName.orEmpty(),
            "model" to Build.MODEL,
            "sdk" to Build.VERSION.SDK_INT,
            "utcOffsetMinutes" to TimeZone.getDefault().rawOffset / MILLIS_PER_MINUTE,
            "asr" to ModelCatalog.ACTIVE.asr.modelPackage.id,
            "speaker" to ModelCatalog.ACTIVE_SPEAKER.version,
            "llm" to ModelCatalog.ACTIVE_LLM.version,
        ),
    )
}

/** 書き出しの最後の行。話者の判定の集計だけで、話者の名前や特徴量は含まない。 */
internal fun encodeSpeakerSummary(diagnostics: SpeakerDiagnostics): String {
    val histogram = diagnostics.histogram.joinToString(" ") { band ->
        "${band.floor}:${band.selfRuns}/${band.otherRuns}/${band.unknownRuns}"
    }
    return encodeFields(
        listOf(
            "event" to "speaker_summary",
            "days" to diagnostics.days,
            "selfRuns" to diagnostics.selfRuns,
            "otherRuns" to diagnostics.otherRuns,
            "unknownRuns" to diagnostics.unknownRuns,
            "selfMs" to diagnostics.selfMillis,
            "otherMs" to diagnostics.otherMillis,
            "unknownMs" to diagnostics.unknownMillis,
            "provisionalSpeakers" to diagnostics.provisionalSpeakers,
            "adaptedVoice" to diagnostics.hasAdaptedVoice,
            "selfThreshold" to diagnostics.config.selfThreshold,
            // 帯の下端:自分/他人/不明
            "histogram" to histogram,
        ),
    )
}

/** OS の共有の画面を開く。送り先は利用者が選ぶ。 */
internal fun shareDiagnosticsFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
