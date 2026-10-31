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
import dev.ambientmemo.engine.android.StartFailure
import dev.ambientmemo.engine.android.TranscriptionMode
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
        throw StartFailedException(e.failure)
      }
      publish(engine.captureState.value)
    }

    AsyncFunction("stopListening") Coroutine { ->
      engine.stopCapture()
      publish(engine.captureState.value)
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
