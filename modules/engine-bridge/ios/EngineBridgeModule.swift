import AVFoundation
import ExpoModulesCore
import UIKit
import UserNotifications

private let eventEngine = "engine"

/// BridgeEngineError を、JS が code で見分けられる例外にする。
private final class EngineCodedException: Exception, @unchecked Sendable {
  init(_ error: BridgeEngineError) {
    super.init(name: "EngineCodedException", description: error.message, code: error.code)
  }
}

/// React Native とエンジンの境界。関数とイベントは Android の EngineBridgeModule.kt と同じにする。
/// エンジンを呼ぶのは [engine] だけ。
public final class EngineBridgeModule: Module {
  private let engine: BridgeEngine = NativeBridgeEngine()

  public func definition() -> ModuleDefinition {
    Name("EngineBridge")

    Events(eventEngine)

    OnCreate { [weak self] in
      // エンジンはモジュールより長く生きることがあるので、モジュールを強く持たせない
      self?.engine.onEvent = { [weak self] event in
        self?.sendEvent(eventEngine, event.mapValues { Optional($0) })
      }
    }

    OnDestroy {
      engine.onEvent = nil
    }

    // 設定の画面で権限を変えて戻ってきたときに、案内を更新する
    OnAppEntersForeground {
      publishPermissions()
    }

    AsyncFunction("getStatus") { () -> WireObject in
      engine.status()
    }

    AsyncFunction("getListeningStatus") { () -> String in
      engine.listeningStatus()
    }

    AsyncFunction("getPermissions") { () async -> [String: Bool] in
      await permissions()
    }

    AsyncFunction("openPermissionSettings") {
      guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
      UIApplication.shared.open(url)
    }.runOnQueue(.main)

    AsyncFunction("startListening") { () async throws in
      // モデルがないのにマイクの許可を求めない
      guard engine.isTranscriptionReady else {
        throw EngineCodedException(.transcriptionUnavailable)
      }
      await requestPermissions()
      publishPermissions()
      try await call { try await engine.startListening() }
    }

    AsyncFunction("stopListening") { () async throws in
      try await call { try await engine.stopListening() }
    }

    AsyncFunction("getModelDownloadStatus") { () -> WireObject in
      engine.modelDownloadStatus()
    }

    // 利用者の操作でだけ呼ぶ。通信はここからしか始まらない
    AsyncFunction("startModelDownload") { () async throws in
      try await call { try await engine.startModelDownload() }
    }

    AsyncFunction("cancelModelDownload") { () async throws in
      try await call { try await engine.cancelModelDownload() }
    }

    AsyncFunction("getOrganizerModelStatus") { () -> WireObject in
      engine.organizerModelStatus()
    }

    // 利用者の操作でだけ呼ぶ。数 GB あるので、聞き取りのモデルとは別に選んでもらう
    AsyncFunction("startOrganizerModelDownload") { () async throws in
      try await call { try await engine.startOrganizerModelDownload() }
    }

    AsyncFunction("listUtterances") { (limit: Int) async throws -> [WireObject] in
      try await call { try await engine.listUtterances(limit: limit) }
    }

    AsyncFunction("listSpeakers") { () async throws -> [WireObject] in
      try await call { try await engine.listSpeakers() }
    }

    AsyncFunction("setUtteranceSpeaker") { (utteranceId: String, target: String) async throws in
      try await call { try await engine.setUtteranceSpeaker(utteranceId: utteranceId, target: target) }
    }

    AsyncFunction("mergeSpeakers") { (fromId: String, intoId: String) async throws in
      try await call { try await engine.mergeSpeakers(fromId: fromId, intoId: intoId) }
    }

    AsyncFunction("resetSpeakers") { () async throws in
      try await call { try await engine.resetSpeakers() }
      sendEvent(eventEngine, ["type": "self-enrollment-changed"])
    }

    AsyncFunction("renameSpeaker") { (speakerId: String, name: String) async throws in
      try await call { try await engine.renameSpeaker(speakerId: speakerId, name: name) }
    }

    AsyncFunction("getSpeakerDiagnostics") { () async throws -> WireObject in
      try await call { try await engine.speakerDiagnostics() }
    }

    AsyncFunction("getDiagnosticsStatus") { () -> WireObject in
      engine.diagnosticsStatus()
    }

    // 利用者の操作でだけ呼ぶ。前の記録は消える
    AsyncFunction("startDiagnostics") { () throws -> WireObject in
      try callSync { try engine.startDiagnostics() }
    }

    AsyncFunction("stopDiagnostics") { () throws -> WireObject in
      try callSync { try engine.stopDiagnostics() }
    }

    AsyncFunction("clearDiagnostics") { () throws -> WireObject in
      try callSync { try engine.clearDiagnostics() }
    }

    // 利用者の操作でだけ呼ぶ。OS の共有の画面を開き、送り先は利用者が選ぶ
    AsyncFunction("shareDiagnostics") { () async throws in
      try await call { try await engine.shareDiagnostics() }
    }

    // 利用者の操作でだけ呼ぶ。端末の中に保存し、端末の外へは出さない
    AsyncFunction("saveDiagnostics") { () async throws in
      try await call { try await engine.saveDiagnostics() }
    }

    AsyncFunction("listMemos") { (limit: Int) async throws -> [WireObject] in
      try await call { try await engine.listMemos(limit: limit) }
    }

    AsyncFunction("getMemo") { (id: String) async throws -> WireObject? in
      try await call { try await engine.getMemo(id: id) }
    }

    // 整理のモデルを読み込むので、数十秒から数分かかる
    AsyncFunction("askMemos") { (question: String) async throws -> WireObject in
      try await call {
        try await engine.askMemos(question: question) { phase in
          self.sendEvent(eventEngine, ["type": "ask-progress", "phase": phase])
        }
      }
    }

    AsyncFunction("cancelAsk") {
      engine.cancelAsk()
    }

    AsyncFunction("getOrganizeStatus") { () async throws -> WireObject in
      try await call { try await engine.organizeStatus() }
    }

    // 利用者の操作でだけ呼ぶ。充電を待たずに LLM を動かす
    AsyncFunction("startOrganize") { () async throws -> String in
      try await call { try await engine.startOrganize() }
    }

    AsyncFunction("stopOrganize") {
      engine.stopOrganize()
    }

    AsyncFunction("getSelfEnrollmentStatus") { () async throws -> String in
      try await call { try await engine.selfEnrollmentStatus() }
    }

    AsyncFunction("enrollSelf") { () async throws in
      await requestPermissions()
      publishPermissions()
      try await call { try await engine.enrollSelf() }
      sendEvent(eventEngine, ["type": "self-enrollment-changed"])
    }

    AsyncFunction("cancelSelfEnrollment") {
      engine.cancelSelfEnrollment()
    }
  }

  /// エンジンの失敗を、Android と同じ code の例外にする。
  private func call<T>(_ body: () async throws -> T) async throws -> T {
    do {
      return try await body()
    } catch let error as BridgeEngineError {
      throw EngineCodedException(error)
    }
  }

  private func callSync<T>(_ body: () throws -> T) throws -> T {
    do {
      return try body()
    } catch let error as BridgeEngineError {
      throw EngineCodedException(error)
    }
  }

  private func publishPermissions() {
    sendEvent(eventEngine, ["type": "permissions-changed"])
  }

  private func permissions() async -> [String: Bool] {
    let settings = await UNUserNotificationCenter.current().notificationSettings()
    let notifications: Bool
    switch settings.authorizationStatus {
    case .authorized, .provisional, .ephemeral:
      notifications = true
    default:
      notifications = false
    }
    return ["microphone": isMicrophoneGranted(), "notifications": notifications]
  }

  private func isMicrophoneGranted() -> Bool {
    if #available(iOS 17.0, *) {
      return AVAudioApplication.shared.recordPermission == .granted
    }
    return AVAudioSession.sharedInstance().recordPermission == .granted
  }

  /// 開始の操作のときに OS の確認を出す。決めたことのある権限では、確認は出ない。
  private func requestPermissions() async {
    if #available(iOS 17.0, *) {
      _ = await AVAudioApplication.requestRecordPermission()
    } else {
      _ = await withCheckedContinuation { continuation in
        AVAudioSession.sharedInstance().requestRecordPermission { continuation.resume(returning: $0) }
      }
    }
    _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
  }
}
