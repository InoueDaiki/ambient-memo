package dev.ambientmemo.bridge

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.ambientmemo.engine.android.CaptureState
import dev.ambientmemo.engine.android.Engine
import dev.ambientmemo.engine.android.EngineStartException
import dev.ambientmemo.engine.android.ModelGroup
import dev.ambientmemo.engine.android.StartFailure
import dev.ambientmemo.engine.android.TranscriptionMode
import dev.ambientmemo.engine.domain.ModelDownloadFailure
import dev.ambientmemo.engine.domain.ModelDownloadPhase
import dev.ambientmemo.engine.domain.ModelDownloadState
import dev.ambientmemo.engine.domain.currentEngineStatus
import expo.modules.interfaces.permissions.PermissionsResponseListener
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.functions.Coroutine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

private const val EVENT_ENGINE = "engine"
private const val LISTENING = "listening"
private const val STOPPED = "stopped"

private class StartFailedException(failure: StartFailure) : CodedException(
  code = when (failure) {
    StartFailure.MICROPHONE_PERMISSION -> "microphone-permission"
    StartFailure.TRANSCRIPTION_UNAVAILABLE -> "transcription-unavailable"
    StartFailure.MICROPHONE_UNAVAILABLE -> "microphone-unavailable"
  },
  message = failure.name,
  cause = null,
)

class EngineBridgeModule : Module() {
  private val context: Context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  private val engine: Engine
    get() = Engine.get(context)

  private var scope: CoroutineScope? = null
  private val publishLock = Any()
  private var publishedStatus: String? = null

  override fun definition() = ModuleDefinition {
    Name("EngineBridge")

    Events(EVENT_ENGINE)

    OnCreate {
      val moduleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      scope = moduleScope
      moduleScope.launch { engine.captureState.collect(::publish) }
      moduleScope.launch {
        engine.modelDownloads.state.collect {
          publishModelDownload(it)
          publishOrganizerModel(it)
        }
      }
      moduleScope.launch {
        engine.utterancesChanged.collect { sendEvent(EVENT_ENGINE, mapOf("type" to "utterances-changed")) }
      }
    }

    OnDestroy {
      scope?.cancel()
    }

    // 設定の画面で権限を変えて戻ってきたときに、案内を更新する
    OnActivityEntersForeground {
      publishPermissions()
    }

    AsyncFunction("getStatus") {
      val status = currentEngineStatus()
      mapOf(
        "engineName" to status.engineName,
        "apiVersion" to status.apiVersion,
        "transcription" to when (engine.transcriptionMode) {
          TranscriptionMode.READY -> "ready"
          TranscriptionMode.UNAVAILABLE -> "unavailable"
        },
      )
    }

    AsyncFunction("getListeningStatus") {
      statusOf(engine.captureState.value)
    }

    AsyncFunction("getPermissions") {
      permissions()
    }

    AsyncFunction("openPermissionSettings") {
      val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
    }

    AsyncFunction("startListening") Coroutine { ->
      // モデルがないのにマイクの許可を求めない
      if (engine.transcriptionMode == TranscriptionMode.UNAVAILABLE) {
        throw StartFailedException(StartFailure.TRANSCRIPTION_UNAVAILABLE)
      }
      requestPermissions()
      publishPermissions()
      try {
        engine.startCapture()
      } catch (e: EngineStartException) {
        // 確認で壊れたモデルが消えたときに、取得の案内を出す
        if (e.failure == StartFailure.TRANSCRIPTION_UNAVAILABLE) {
          publishModelDownload(engine.modelDownloads.state.value)
        }
        throw StartFailedException(e.failure)
      }
      publish(engine.captureState.value)
    }

    AsyncFunction("stopListening") Coroutine { ->
      engine.stopCapture()
      publish(engine.captureState.value)
    }

    AsyncFunction("getModelDownloadStatus") {
      modelDownloadStatus(engine.modelDownloads.state.value)
    }

    // 利用者の操作でだけ呼ぶ。通信はここからしか始まらない
    AsyncFunction("startModelDownload") {
      engine.modelDownloads.start()
    }

    AsyncFunction("cancelModelDownload") {
      engine.modelDownloads.cancel()
    }

    AsyncFunction("getOrganizerModelStatus") {
      organizerModelStatus(engine.modelDownloads.state.value)
    }

    // 利用者の操作でだけ呼ぶ。数 GB あるので、聞き取りのモデルとは別に選んでもらう
    AsyncFunction("startOrganizerModelDownload") {
      engine.modelDownloads.start(ModelGroup.ORGANIZER)
    }

    AsyncFunction("listUtterances") Coroutine { limit: Int ->
      engine.recentUtterances(limit).map {
        mapOf(
          "id" to it.id.toString(),
          "text" to it.text,
          // 話者識別ができるまでは、自分の声と見分けられないので「それ以外」にする
          "speaker" to "other",
          "startedAt" to it.startedAtMillis.toDouble(),
        )
      }
    }
  }

  private fun statusOf(state: CaptureState): String = when (state) {
    is CaptureState.Listening, is CaptureState.Stopping -> LISTENING
    is CaptureState.Starting, is CaptureState.Stopped -> STOPPED
  }

  /**
   * 状態が変わったときだけ JS へ知らせる。開始と停止の Promise を解決する前にも呼び、
   * 「状態が変わるときは listening-changed を届けてから解決する」という境界の約束を守る。
   */
  private fun publish(state: CaptureState) {
    val status = statusOf(state)
    val interrupted = state is CaptureState.Stopped && state.interrupted
    synchronized(publishLock) {
      if (status == publishedStatus) return
      val isFirst = publishedStatus == null
      publishedStatus = status
      if (isFirst && status == STOPPED) return
      sendEvent(
        EVENT_ENGINE,
        mapOf("type" to "listening-changed", "status" to status, "interrupted" to interrupted),
      )
    }
  }

  private fun publishModelDownload(state: ModelDownloadState) {
    sendEvent(EVENT_ENGINE, mapOf("type" to "model-download-changed", "status" to modelDownloadStatus(state)))
  }

  private fun publishOrganizerModel(state: ModelDownloadState) {
    sendEvent(EVENT_ENGINE, mapOf("type" to "organizer-model-changed", "status" to organizerModelStatus(state)))
  }

  private fun organizerModelStatus(state: ModelDownloadState): Map<String, Any> {
    val downloads = engine.modelDownloads
    return when {
      downloads.plan(ModelGroup.ORGANIZER).isComplete -> plannedStatus("installed", ModelGroup.ORGANIZER)
      !downloads.isAvailable(ModelGroup.ORGANIZER) -> mapOf("state" to "unavailable")
      else -> modelDownloadStatus(state, ModelGroup.ORGANIZER)
    }
  }

  /** 取得は組をまたいで1本なので、別の組の取得の状態は [group] の状態として見せない。 */
  private fun modelDownloadStatus(
    state: ModelDownloadState,
    group: ModelGroup = ModelGroup.SPEECH,
  ): Map<String, Any> {
    val visible = if (engine.modelDownloads.activeGroup == group) state else ModelDownloadState.Idle
    return when (visible) {
    is ModelDownloadState.Running -> mapOf(
      "state" to "running",
      "phase" to when (visible.progress.phase) {
        ModelDownloadPhase.DOWNLOADING -> "downloading"
        ModelDownloadPhase.VERIFYING -> "verifying"
        ModelDownloadPhase.EXTRACTING -> "extracting"
      },
      "completedBytes" to visible.progress.completedBytes.toDouble(),
      "totalBytes" to visible.progress.totalBytes.toDouble(),
    )
    is ModelDownloadState.Failed -> plannedStatus("failed", group) + mapOf(
      "failure" to when (visible.failure) {
        ModelDownloadFailure.NETWORK -> "network"
        ModelDownloadFailure.CORRUPTED -> "corrupted"
        ModelDownloadFailure.INSUFFICIENT_STORAGE -> "insufficient-storage"
        ModelDownloadFailure.UNKNOWN -> "unknown"
      },
      "shortageBytes" to visible.shortageBytes.toDouble(),
    )
    ModelDownloadState.Idle ->
      plannedStatus(if (engine.modelDownloads.plan(group).isComplete) "installed" else "idle", group)
    }
  }

  private fun plannedStatus(name: String, group: ModelGroup): Map<String, Any> {
    val plan = engine.modelDownloads.plan(group)
    return mapOf(
      "state" to name,
      "downloadedBytes" to plan.downloadedBytes.toDouble(),
      "totalBytes" to plan.totalBytes.toDouble(),
    )
  }

  private fun publishPermissions() {
    sendEvent(EVENT_ENGINE, mapOf("type" to "permissions-changed"))
  }

  private fun permissions(): Map<String, Boolean> {
    val microphone = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
    val notifications = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    return mapOf("microphone" to (microphone == PackageManager.PERMISSION_GRANTED), "notifications" to notifications)
  }

  /** 開始の操作のときに OS の確認を出す。結果は [Engine.startCapture] と getPermissions が見る。 */
  private suspend fun requestPermissions() {
    val manager = appContext.permissions ?: return
    val requested = buildList {
      add(Manifest.permission.RECORD_AUDIO)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    suspendCancellableCoroutine { continuation ->
      val listener = PermissionsResponseListener { if (continuation.isActive) continuation.resume(Unit) }
      manager.askForPermissions(listener, *requested.toTypedArray())
    }
  }
}
