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
  // Android にはない。iOS でモデルの取得を作るまでの間だけ使う。JS は取得の失敗(unknown)として扱う
  static let modelDownloadUnavailable = BridgeEngineError(
    code: "model-download-unavailable", message: "この版ではモデルを取得できません")
}

/// モジュールが呼ぶエンジンの窓口。engine/ios の Swift のエンジンができたら、これを実装したものに差し替える。
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

/// エンジンがまだない間の仮の実装。モデルがなく、聞き取りしておらず、記録も空の状態として振る舞う。
/// 何も保存せず、端末の外へも何も送らない。
final class UnavailableEngine: BridgeEngine {
  var onEvent: ((WireObject) -> Void)?

  let isTranscriptionReady = false

  func status() -> WireObject {
    // engineName と apiVersion は engine/domain の currentEngineStatus と同じ値
    ["engineName": "ambient-memo-engine", "apiVersion": 1, "transcription": "unavailable"]
  }

  func listeningStatus() -> String { "stopped" }

  func startListening() async throws { throw BridgeEngineError.transcriptionUnavailable }

  func stopListening() async throws {}

  func modelDownloadStatus() -> WireObject { Self.noModel }

  func startModelDownload() async throws { throw BridgeEngineError.modelDownloadUnavailable }

  func cancelModelDownload() async throws {}

  func organizerModelStatus() -> WireObject { Self.noModel }

  func startOrganizerModelDownload() async throws { throw BridgeEngineError.modelDownloadUnavailable }

  func listUtterances(limit: Int) async throws -> [WireObject] { [] }

  func listSpeakers() async throws -> [WireObject] { [] }

  func setUtteranceSpeaker(utteranceId: String, target: String) async throws {
    throw BridgeEngineError.speakerNotFound
  }

  func mergeSpeakers(fromId: String, intoId: String) async throws {
    throw BridgeEngineError.speakerNotFound
  }

  // 消すものがないので成功する
  func resetSpeakers() async throws {}

  func renameSpeaker(speakerId: String, name: String) async throws {
    throw BridgeEngineError.speakerNotFound
  }

  func speakerDiagnostics() async throws -> WireObject {
    // days と閾値は engine/domain の SpeakerDiagnostics.DEFAULT_DAYS と SpeakerConfig の既定値
    [
      "days": 7,
      "selfRuns": 0.0,
      "otherRuns": 0.0,
      "unknownRuns": 0.0,
      "selfSeconds": 0.0,
      "otherSeconds": 0.0,
      "unknownSeconds": 0.0,
      "histogram": [WireObject](),
      "provisionalSpeakers": 0,
      "hasAdaptedVoice": false,
      "selfThreshold": 0.5,
      "unknownFloor": 0.4,
    ]
  }

  // 記録はない。startedAt と endsAt は、記録がないときは項目ごと省く(Android と同じ)
  func diagnosticsStatus() -> WireObject {
    ["isRecording": false, "sizeBytes": 0.0, "isFull": false]
  }

  // 記録するものがないので、始めても状態は変わらない
  func startDiagnostics() throws -> WireObject { diagnosticsStatus() }

  func stopDiagnostics() throws -> WireObject { diagnosticsStatus() }

  func clearDiagnostics() throws -> WireObject { diagnosticsStatus() }

  func shareDiagnostics() async throws { throw BridgeEngineError.diagnosticsEmpty }

  func saveDiagnostics() async throws { throw BridgeEngineError.diagnosticsEmpty }

  func selfEnrollmentStatus() async throws -> String { "unavailable" }

  func enrollSelf() async throws { throw BridgeEngineError.speakerModelUnavailable }

  func cancelSelfEnrollment() {}

  func listMemos(limit: Int) async throws -> [WireObject] { [] }

  func getMemo(id: String) async throws -> WireObject? { nil }

  func listTodos(limit: Int) async throws -> [WireObject] { [] }

  func completeTodo(id: String) async throws { throw BridgeEngineError.todoNotFound }

  func organizeStatus() async throws -> WireObject {
    [
      "state": "idle",
      "completedChunks": 0,
      "totalChunks": 0,
      "pendingChunks": 0,
      "isWaitingForSilence": false,
      "lastResult": NSNull(),
    ]
  }

  func startOrganize() async throws -> String { "model-unavailable" }

  func stopOrganize() {}

  private static let noModel: WireObject = ["state": "idle", "downloadedBytes": 0.0, "totalBytes": 0.0]
}
