import Foundation

/// JS が読む形。値の形は `modules/engine-bridge/src/index.ts` と `src/memo-client.ts` の Native の型、
/// Android の `EngineBridgeModule.kt` と `MemoBridge.kt` に合わせる。
typealias WireObject = [String: Any]

/// エンジンが JS へ返す失敗。code は Android の CodedException と同じ文字列にする。
struct BridgeEngineError: Error {
  let code: String
  let message: String

  static let transcriptionUnavailable = BridgeEngineError(
    code: "transcription-unavailable", message: "文字起こしのモデルがありません")
  static let speakerModelUnavailable = BridgeEngineError(
    code: "speaker-model-unavailable", message: "話者のモデルがありません")
  static let speakerNotFound = BridgeEngineError(
    code: "speaker-not-found", message: "直す先の発話か話者がありません")
  static let diagnosticsEmpty = BridgeEngineError(
    code: "diagnostics-empty", message: "書き出す記録がありません")
  static let todoNotFound = BridgeEngineError(code: "todo-not-found", message: "ToDo がありません")
  static let speakerResetBusy = BridgeEngineError(
    code: "speaker-reset-busy", message: "聞き取りや声の登録の途中はリセットできません")
  static let microphonePermission = BridgeEngineError(
    code: "microphone-permission", message: "MICROPHONE_PERMISSION")
  static let microphoneUnavailable = BridgeEngineError(
    code: "microphone-unavailable", message: "MICROPHONE_UNAVAILABLE")
  static let listening = BridgeEngineError(code: "listening", message: "LISTENING")
  static let notEnoughSpeech = BridgeEngineError(code: "not-enough-speech", message: "NOT_ENOUGH_SPEECH")
  static let cancelled = BridgeEngineError(code: "cancelled", message: "CANCELLED")
}

/// モジュールが呼ぶエンジンの窓口。本物は NativeBridgeEngine.swift の NativeBridgeEngine。
///
/// 権限の確認と OS の確認の表示はモジュールが受け持つ(Android と同じ)。
/// イベントは [onEvent] で知らせる。`{"type": ...}` の形は EngineEvent と同じにする。
/// 開始と停止は、状態が変わるときは listening-changed を届けてから戻る。
protocol BridgeEngine: AnyObject {
  var onEvent: ((WireObject) -> Void)? { get set }

  /// 文字起こしのモデルがあるか。ないときは、マイクの許可を求める前に開始を断る
  var isTranscriptionReady: Bool { get }

  func status() -> WireObject
  func listeningStatus() -> String
  func startListening() async throws
  func stopListening() async throws

  func modelDownloadStatus() -> WireObject
  func startModelDownload() async throws
  func cancelModelDownload() async throws
  func organizerModelStatus() -> WireObject
  func startOrganizerModelDownload() async throws

  func listUtterances(limit: Int) async throws -> [WireObject]
  func listSpeakers() async throws -> [WireObject]
  func setUtteranceSpeaker(utteranceId: String, target: String) async throws
  func mergeSpeakers(fromId: String, intoId: String) async throws
  func resetSpeakers() async throws
  func renameSpeaker(speakerId: String, name: String) async throws
  func speakerDiagnostics() async throws -> WireObject

  func diagnosticsStatus() -> WireObject
  func startDiagnostics() throws -> WireObject
  func stopDiagnostics() throws -> WireObject
  func clearDiagnostics() throws -> WireObject
  func shareDiagnostics() async throws
  func saveDiagnostics() async throws

  func selfEnrollmentStatus() async throws -> String
  func enrollSelf() async throws
  func cancelSelfEnrollment()

  func listMemos(limit: Int) async throws -> [WireObject]
  func getMemo(id: String) async throws -> WireObject?
  func listTodos(limit: Int) async throws -> [WireObject]
  func completeTodo(id: String) async throws
  func organizeStatus() async throws -> WireObject
  func startOrganize() async throws -> String
  func stopOrganize()
}
