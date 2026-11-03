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
import dev.ambientmemo.engine.android.EnrollmentFailure
import dev.ambientmemo.engine.android.MemoChange
import dev.ambientmemo.engine.android.ModelGroup
import dev.ambientmemo.engine.android.SelfEnrollmentException
import dev.ambientmemo.engine.android.SelfEnrollmentStatus
import dev.ambientmemo.engine.android.StartFailure
import dev.ambientmemo.engine.android.TodoNotFoundException
import dev.ambientmemo.engine.android.TranscriptionMode
import dev.ambientmemo.engine.android.parseSpeakerTarget
import dev.ambientmemo.engine.android.toWire
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

private class EnrollmentFailedException(failure: EnrollmentFailure) : CodedException(
  code = when (failure) {
    EnrollmentFailure.MICROPHONE_PERMISSION -> "microphone-permission"
    EnrollmentFailure.MICROPHONE_UNAVAILABLE -> "microphone-unavailable"
    EnrollmentFailure.SPEAKER_MODEL_UNAVAILABLE -> "speaker-model-unavailable"
    EnrollmentFailure.LISTENING -> "listening"
    EnrollmentFailure.NOT_ENOUGH_SPEECH -> "not-enough-speech"
    EnrollmentFailure.CANCELLED -> "cancelled"
  },
  message = failure.name,
  cause = null,
)

private class SpeakerNotFoundCodedException : CodedException(
  code = "speaker-not-found",
  message = "直す先の発話か話者がありません",
  cause = null,
)

private class SpeakerResetBusyException : CodedException(
  code = "speaker-reset-busy",
  message = "聞き取りや声の登録の途中はリセットできません",
  cause = null,
)

private class DiagnosticsEmptyException : CodedException(
  code = "diagnostics-empty",
  message = "書き出す記録がありません",
  cause = null,
)

private class TodoNotFoundCodedException : CodedException(
  code = "todo-not-found",
  message = "ToDo がありません",
  cause = null,
)

class EngineBridgeModule : Module() {
  private val context: Context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  private val engine: Engine
    get() = Engine.get(context)

  private var scope: CoroutineScope? = null
  private val publishLock = Any()

  // 書き込みが戻る前に呼ばれるので、完了の Promise より先にイベントが届く
  private val memoListener: (MemoChange) -> Unit = { change ->
    val type = when (change) {
      MemoChange.MEMOS -> "memos-changed"
      MemoChange.TODOS -> "todos-changed"
    }
    sendEvent(EVENT_ENGINE, mapOf("type" to type))
  }
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
      moduleScope.launch {
        engine.enrollmentProgress.collect {
          sendEvent(
            EVENT_ENGINE,
            mapOf(
              "type" to "enrollment-progress",
              "acceptedSamples" to it.acceptedSamples,
              "isRejected" to it.isRejected,
            ),
          )
        }
      }
      moduleScope.launch {
        engine.organizeChanges.collect { sendEvent(EVENT_ENGINE, mapOf("type" to "organize-changed")) }
      }
      engine.addMemoListener(memoListener)
      // 以前の版で増えた話者を、聞き取りを始める前でも一覧でまとまって見えるようにする
      moduleScope.launch { engine.consolidateSpeakers() }
    }

    OnDestroy {
      scope?.cancel()
      engine.removeMemoListener(memoListener)
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
      val speakers = engine.speakers()
      engine.recentUtterances(limit).map { it.toWire(speakers) }
    }

    AsyncFunction("listSpeakers") Coroutine { ->
      engine.speakerSummaries().map { it.toWire() }
    }

    AsyncFunction("setUtteranceSpeaker") Coroutine { utteranceId: String, target: String ->
      val id = utteranceId.toLongOrNull() ?: throw SpeakerNotFoundCodedException()
      val parsed = parseSpeakerTarget(target) ?: throw SpeakerNotFoundCodedException()
      if (!engine.reassignUtterance(id, parsed)) throw SpeakerNotFoundCodedException()
    }

    AsyncFunction("mergeSpeakers") Coroutine { fromId: String, intoId: String ->
      val from = fromId.toLongOrNull() ?: throw SpeakerNotFoundCodedException()
      val into = intoId.toLongOrNull() ?: throw SpeakerNotFoundCodedException()
      if (!engine.mergeSpeakers(from, into)) throw SpeakerNotFoundCodedException()
    }

    AsyncFunction("resetSpeakers") Coroutine { ->
      if (!engine.resetSpeakers()) throw SpeakerResetBusyException()
      sendEvent(EVENT_ENGINE, mapOf("type" to "self-enrollment-changed"))
    }

    AsyncFunction("renameSpeaker") Coroutine { speakerId: String, name: String ->
      val id = speakerId.toLongOrNull() ?: throw SpeakerNotFoundCodedException()
      if (!engine.renameSpeaker(id, name)) throw SpeakerNotFoundCodedException()
    }

    AsyncFunction("getSpeakerDiagnostics") Coroutine { ->
      engine.speakerDiagnostics().toWire()
    }

    AsyncFunction("getDiagnosticsStatus") {
      diagnosticsStatus()
    }

    // 利用者の操作でだけ呼ぶ。前の記録は消える
    AsyncFunction("startDiagnostics") {
      engine.startDiagnostics()
      diagnosticsStatus()
    }

    AsyncFunction("stopDiagnostics") {
      engine.stopDiagnostics()
      diagnosticsStatus()
    }

    AsyncFunction("clearDiagnostics") {
      engine.clearDiagnostics()
      diagnosticsStatus()
    }

    // 利用者の操作でだけ呼ぶ。OS の共有の画面を開き、送り先は利用者が選ぶ
    AsyncFunction("shareDiagnostics") Coroutine { ->
      if (!engine.shareDiagnostics(appContext.currentActivity)) throw DiagnosticsEmptyException()
    }

    AsyncFunction("listMemos") Coroutine { limit: Int ->
      engine.memoBridge.listMemos(limit)
    }

    AsyncFunction("getMemo") Coroutine { id: String ->
      engine.memoBridge.getMemo(id)
    }

    AsyncFunction("listTodos") Coroutine { limit: Int ->
      engine.memoBridge.listTodos(limit)
    }

    AsyncFunction("completeTodo") Coroutine { id: String ->
      try {
        engine.memoBridge.completeTodo(id)
      } catch (_: TodoNotFoundException) {
        throw TodoNotFoundCodedException()
      }
    }

    AsyncFunction("getOrganizeStatus") Coroutine { ->
      engine.memoBridge.organizeStatus()
    }

    // 利用者の操作でだけ呼ぶ。充電を待たずに LLM を動かす
    AsyncFunction("startOrganize") Coroutine { ->
      engine.memoBridge.startOrganize(::requestNotificationPermission)
    }

    AsyncFunction("stopOrganize") {
      engine.memoBridge.stopOrganize()
    }

    AsyncFunction("getSelfEnrollmentStatus") Coroutine { ->
      when (engine.selfEnrollmentStatus()) {
        SelfEnrollmentStatus.ENROLLED -> "enrolled"
        SelfEnrollmentStatus.NOT_ENROLLED -> "not-enrolled"
        SelfEnrollmentStatus.UNAVAILABLE -> "unavailable"
      }
    }

    AsyncFunction("enrollSelf") Coroutine { ->
      requestPermissions()
      publishPermissions()
      try {
        engine.enrollSelf()
      } catch (e: SelfEnrollmentException) {
        throw EnrollmentFailedException(e.failure)
      }
      sendEvent(EVENT_ENGINE, mapOf("type" to "self-enrollment-changed"))
    }

    AsyncFunction("cancelSelfEnrollment") {
      engine.cancelEnrollment()
    }
  }

  private fun diagnosticsStatus(): Map<String, Any?> {
    val status = engine.diagnosticsStatus()
    return mapOf(
      "isRecording" to status.isRecording,
      "startedAt" to status.startedAtMillis?.toDouble(),
      "endsAt" to status.endsAtMillis?.toDouble(),
      "sizeBytes" to status.sizeBytes.toDouble(),
      "isFull" to status.isFull,
    ).filterValues { it != null } // JS の型は項目なしで表す。null を渡すと記録があるように見える
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

  private fun organizerModelStatus(state: ModelDownloadState): Map<String, Any> =
    modelDownloadStatus(state, ModelGroup.ORGANIZER)

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
    val requested = buildList {
      add(Manifest.permission.RECORD_AUDIO)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    askForPermissions(requested)
  }

  /** 整理の進行と「止める」は通知に出す。許可がなくても整理は動き、画面からは止められる。 */
  private suspend fun requestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    askForPermissions(listOf(Manifest.permission.POST_NOTIFICATIONS))
    publishPermissions()
  }

  private suspend fun askForPermissions(requested: List<String>) {
    val manager = appContext.permissions ?: return
    suspendCancellableCoroutine { continuation ->
      val listener = PermissionsResponseListener { if (continuation.isActive) continuation.resume(Unit) }
      manager.askForPermissions(listener, *requested.toTypedArray())
    }
  }
}
