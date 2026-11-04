import AmbientMemoEngine
import Foundation

/// JS への値の変換で、秒にするための値。
private let MILLIS_PER_SECOND = 1_000.0

/**
 * engine/ios の [AmbientEngine] で [BridgeEngine] を実装する。値の形は Android の EngineBridgeModule.kt と MemoBridge.kt に合わせる。
 *
 * id は JS では文字列なので、数でない id は「ない」ものとして扱う。特徴量は JS へ渡さない。
 */
final class NativeBridgeEngine: BridgeEngine, @unchecked Sendable {
  private let engine = AmbientEngine.shared
  private let lock = NSLock()
  private var publishedStatus: String?
  private var removeListener: (@Sendable () -> Void)?

  var onEvent: ((WireObject) -> Void)?

  init() {
    removeListener = engine.addListener { [weak self] change in self?.handle(change) }
    engine.ensureOrganizeScheduled()
    // 以前の版で増えた話者を、聞き取りを始める前でも一覧でまとまって見えるようにする
    Task.detached(priority: .utility) { [engine] in await engine.consolidateSpeakers() }
  }

  deinit {
    removeListener?()
  }

  private func send(_ event: WireObject) {
    onEvent?(event)
  }

  private func handle(_ change: EngineChange) {
    switch change {
    case let .capture(state):
      publish(state)
    case .modelDownload:
      send(["type": "model-download-changed", "status": modelDownloadStatus()])
      send(["type": "organizer-model-changed", "status": organizerModelStatus()])
    case .utterances:
      send(["type": "utterances-changed"])
    case let .memo(memo):
      send(["type": memo == .memos ? "memos-changed" : "todos-changed"])
    case let .enrollmentProgress(progress):
      send([
        "type": "enrollment-progress",
        "acceptedSamples": progress.acceptedSamples,
        "isRejected": progress.isRejected,
      ])
    case .organize:
      send(["type": "organize-changed"])
    }
  }

  private func statusOf(_ state: CaptureState) -> String {
    switch state {
    case .listening, .stopping: "listening"
    case .starting, .stopped: "stopped"
    }
  }

  /**
   * 状態が変わったときだけ JS へ知らせる。開始と停止の Promise を解決する前にも呼び、
   * 「状態が変わるときは listening-changed を届けてから解決する」という境界の約束を守る。
   */
  private func publish(_ state: CaptureState) {
    let status = statusOf(state)
    var interrupted = false
    if case let .stopped(value) = state { interrupted = value }
    let event: WireObject? = lock.withLock {
      if status == publishedStatus { return nil }
      let isFirst = publishedStatus == nil
      publishedStatus = status
      if isFirst && status == "stopped" { return nil }
      return ["type": "listening-changed", "status": status, "interrupted": interrupted]
    }
    if let event { send(event) }
  }

  // MARK: - 聞き取り

  var isTranscriptionReady: Bool { engine.isTranscriptionReady }

  func status() -> WireObject {
    let status = currentEngineStatusForBridge()
    return [
      "engineName": status.engineName,
      "apiVersion": status.apiVersion,
      "transcription": engine.isTranscriptionReady ? "ready" : "unavailable",
    ]
  }

  func listeningStatus() -> String { statusOf(engine.captureState) }

  func startListening() async throws {
    do {
      try await engine.startCapture()
    } catch let failure as StartFailure {
      switch failure {
      case .MICROPHONE_PERMISSION: throw BridgeEngineError.microphonePermission
      case .MICROPHONE_UNAVAILABLE: throw BridgeEngineError.microphoneUnavailable
      case .TRANSCRIPTION_UNAVAILABLE:
        // 確認で壊れたモデルが消えたときに、取得の案内を出す
        send(["type": "model-download-changed", "status": modelDownloadStatus()])
        throw BridgeEngineError.transcriptionUnavailable
      }
    }
    publish(engine.captureState)
  }

  func stopListening() async throws {
    await engine.stopCapture()
    publish(engine.captureState)
  }

  // MARK: - モデルの取得

  func modelDownloadStatus() -> WireObject { downloadStatus(.speech) }

  func startModelDownload() async throws { engine.startModelDownload(.speech) }

  func cancelModelDownload() async throws { engine.cancelModelDownload() }

  func organizerModelStatus() -> WireObject { downloadStatus(.organizer) }

  func startOrganizerModelDownload() async throws { engine.startModelDownload(.organizer) }

  /// 取得は組をまたいで1本なので、別の組の取得の状態は [group] の状態として見せない。
  private func downloadStatus(_ group: ModelGroup) -> WireObject {
    let state: ModelDownloadState = engine.activeDownloadGroup == group ? engine.modelDownloadState : .Idle
    switch state {
    case let .Running(progress):
      let phase: String = switch progress.phase {
      case .DOWNLOADING: "downloading"
      case .VERIFYING: "verifying"
      case .EXTRACTING: "extracting"
      }
      return [
        "state": "running",
        "phase": phase,
        "completedBytes": Double(progress.completedBytes),
        "totalBytes": Double(progress.totalBytes),
      ]
    case let .Failed(failure, shortageBytes):
      let name: String = switch failure {
      case .NETWORK: "network"
      case .CORRUPTED: "corrupted"
      case .INSUFFICIENT_STORAGE: "insufficient-storage"
      case .UNKNOWN: "unknown"
      }
      return planned("failed", group).merging(
        ["failure": name, "shortageBytes": Double(shortageBytes)], uniquingKeysWith: { $1 })
    case .Idle:
      return planned(engine.modelDownloadPlan(group).isComplete ? "installed" : "idle", group)
    }
  }

  private func planned(_ name: String, _ group: ModelGroup) -> WireObject {
    let plan = engine.modelDownloadPlan(group)
    return ["state": name, "downloadedBytes": Double(plan.downloadedBytes), "totalBytes": Double(plan.totalBytes)]
  }

  // MARK: - 発話と話者

  func listUtterances(limit: Int) async throws -> [WireObject] {
    let speakers = try await engine.speakers()
    return try await engine.recentUtterances(limit: limit).map { utteranceWire($0, speakers) }
  }

  func listSpeakers() async throws -> [WireObject] {
    try await engine.speakerSummaries().map { summary in
      let speaker = summary.speaker
      let kind = speaker.isSelf ? "self" : (speaker.nameSource == .PROVISIONAL ? "provisional" : "named")
      return [
        "id": String(speaker.id),
        "name": speaker.displayName,
        "kind": kind,
        "utteranceCount": summary.utteranceCount,
      ]
    }
  }

  func setUtteranceSpeaker(utteranceId: String, target: String) async throws {
    guard let id = Int64(utteranceId), let parsed = parseTarget(target),
      try await engine.reassignUtterance(id, parsed)
    else { throw BridgeEngineError.speakerNotFound }
  }

  func mergeSpeakers(fromId: String, intoId: String) async throws {
    guard let from = Int64(fromId), let into = Int64(intoId), try await engine.mergeSpeakers(from, into) else {
      throw BridgeEngineError.speakerNotFound
    }
  }

  func resetSpeakers() async throws {
    if try await !engine.resetSpeakers() { throw BridgeEngineError.speakerResetBusy }
  }

  func renameSpeaker(speakerId: String, name: String) async throws {
    guard let id = Int64(speakerId), try await engine.renameSpeaker(id, name) else {
      throw BridgeEngineError.speakerNotFound
    }
  }

  func speakerDiagnostics() async throws -> WireObject {
    let value = try await engine.speakerDiagnostics()
    return [
      "days": value.days,
      "selfRuns": Double(value.selfRuns),
      "otherRuns": Double(value.otherRuns),
      "unknownRuns": Double(value.unknownRuns),
      "selfSeconds": Double(value.selfMillis / 1_000),
      "otherSeconds": Double(value.otherMillis / 1_000),
      "unknownSeconds": Double(value.unknownMillis / 1_000),
      "histogram": value.histogram.map { band -> WireObject in
        [
          "floor": Double(band.floor),
          "self": Double(band.selfRuns),
          "other": Double(band.otherRuns),
          "unknown": Double(band.unknownRuns),
        ]
      },
      "provisionalSpeakers": value.provisionalSpeakers,
      "hasAdaptedVoice": value.hasAdaptedVoice,
      "selfThreshold": Double(value.config.selfThreshold),
      "unknownFloor": Double(value.config.unknownFloor),
    ]
  }

  private func parseTarget(_ target: String) -> SpeakerTarget? {
    switch target {
    case "self": return .Myself
    case "unknown": return .Unknown
    case "new": return .NewOther
    default: return Int64(target).map { .Existing(speakerId: $0) }
    }
  }

  // MARK: - 動作の記録

  /// startedAt と endsAt は、記録がないときは項目ごと省く(Android と同じ)。
  func diagnosticsStatus() -> WireObject {
    let status = engine.diagnosticsStatus()
    var wire: WireObject = [
      "isRecording": status.isRecording,
      "sizeBytes": Double(status.sizeBytes),
      "isFull": status.isFull,
    ]
    if let started = status.startedAtMillis { wire["startedAt"] = Double(started) }
    if let ends = status.endsAtMillis { wire["endsAt"] = Double(ends) }
    return wire
  }

  func startDiagnostics() throws -> WireObject {
    try engine.startDiagnostics()
    return diagnosticsStatus()
  }

  func stopDiagnostics() throws -> WireObject {
    try engine.stopDiagnostics()
    return diagnosticsStatus()
  }

  func clearDiagnostics() throws -> WireObject {
    engine.clearDiagnostics()
    return diagnosticsStatus()
  }

  func shareDiagnostics() async throws {
    if try await !engine.shareDiagnostics() { throw BridgeEngineError.diagnosticsEmpty }
  }

  func saveDiagnostics() async throws {
    if try await !engine.saveDiagnostics() { throw BridgeEngineError.diagnosticsEmpty }
  }

  // MARK: - 自分の声の登録

  func selfEnrollmentStatus() async throws -> String {
    switch try await engine.selfEnrollmentStatus() {
    case .enrolled: "enrolled"
    case .notEnrolled: "not-enrolled"
    case .unavailable: "unavailable"
    }
  }

  func enrollSelf() async throws {
    do {
      try await engine.enrollSelf()
    } catch let failure as EnrollmentFailure {
      switch failure {
      case .MICROPHONE_PERMISSION: throw BridgeEngineError.microphonePermission
      case .MICROPHONE_UNAVAILABLE: throw BridgeEngineError.microphoneUnavailable
      case .SPEAKER_MODEL_UNAVAILABLE: throw BridgeEngineError.speakerModelUnavailable
      case .LISTENING: throw BridgeEngineError.listening
      case .NOT_ENOUGH_SPEECH: throw BridgeEngineError.notEnoughSpeech
      case .CANCELLED: throw BridgeEngineError.cancelled
      }
    }
  }

  func cancelSelfEnrollment() { engine.cancelEnrollment() }

  // MARK: - メモと ToDo

  func listMemos(limit: Int) async throws -> [WireObject] {
    try await engine.memoRepository().listRecentMemos(limit: limit).map(memoWire)
  }

  func getMemo(id: String) async throws -> WireObject? {
    let memos = try engine.memoRepository()
    guard let memoId = Int64(id), let memo = try await memos.findMemo(memoId: memoId) else { return nil }
    let speakers = try await engine.speakers()
    var wire = memoWire(memo)
    wire["utterances"] = try await memos.listMemoUtterances(memoId: memo.id).map { utteranceWire($0, speakers) }
    wire["todos"] = try await memos.listTodos(memoId: memo.id).map(todoWire)
    return wire
  }

  func listTodos(limit: Int) async throws -> [WireObject] {
    try await engine.memoRepository().listTodosByPriority(limit: limit).map(todoWire)
  }

  /// 完了済みなら何もせず戻る。
  func completeTodo(id: String) async throws {
    guard let todoId = Int64(id) else { throw BridgeEngineError.todoNotFound }
    let now = Int64((Date().timeIntervalSince1970 * MILLIS_PER_SECOND).rounded(.down))
    if try await engine.memoRepository().completeTodo(todoId: todoId, completedAtMillis: now) == .NOT_FOUND {
      throw BridgeEngineError.todoNotFound
    }
  }

  func organizeStatus() async throws -> WireObject {
    let backlog = try await engine.organizeBacklog()
    let status = engine.organizeStatus
    let state: String = switch status.phase {
    case .idle: "idle"
    case .preparing: "preparing"
    case .organizing: "organizing"
    case .stopping: "stopping"
    }
    let lastResult: Any = switch status.ending {
    case nil: NSNull()
    case .completed: "completed"
    case .stopped: "stopped"
    case .tooHot: "too-hot"
    case .modelMissing: "model-unavailable"
    case .failed: "failed"
    case .timeLimit: "time-limit"
    }
    return [
      "state": state,
      "completedChunks": status.completedChunks,
      "totalChunks": status.totalChunks,
      "pendingChunks": backlog.readyChunks,
      "isWaitingForSilence": backlog.hasOngoing,
      "lastResult": lastResult,
    ]
  }

  func startOrganize() async throws -> String {
    switch try await engine.startOrganize() {
    case .started: "started"
    case .alreadyRunning: "already-running"
    case .modelUnavailable: "model-unavailable"
    case .nothingToOrganize: "nothing-to-organize"
    case .failed: "failed"
    }
  }

  func stopOrganize() { engine.stopOrganize() }

  // MARK: - 値の形

  private func utteranceWire(_ utterance: Utterance, _ speakers: [Int64: Speaker]) -> WireObject {
    let speaker = utterance.speakerId.flatMap { speakers[$0] }
    let kind = speaker == nil ? "unknown" : (speaker!.isSelf ? "self" : "other")
    return [
      "id": String(utterance.id),
      "text": utterance.text,
      "speaker": kind,
      "speakerId": speaker.map { String($0.id) } ?? NSNull(),
      "speakerName": speaker?.displayName ?? NSNull(),
      "startedAt": Double(utterance.startedAtMillis),
    ]
  }

  private func memoWire(_ memo: Memo) -> WireObject {
    [
      "id": String(memo.id),
      "title": memo.title ?? NSNull(),
      "summary": memo.summary ?? NSNull(),
      "todoExtraction": memo.todoExtraction == .COMPLETE ? "complete" : "incomplete",
      "startedAt": Double(memo.startedAtMillis),
      "endedAt": Double(memo.endedAtMillis),
    ]
  }

  private func todoWire(_ todo: Todo) -> WireObject {
    let status: String = switch todo.status {
    case .CANDIDATE: "candidate"
    case .OPEN: "open"
    case .DONE: "done"
    }
    return [
      "id": String(todo.id),
      "memoId": String(todo.memoId),
      "task": todo.task,
      "title": todo.title,
      "kind": todo.kind == .TODO ? "todo" : "reminder",
      "dueDate": todo.dueDate.map { $0.description } ?? NSNull(),
      "status": status,
      "createdAt": Double(todo.createdAtMillis),
    ]
  }
}

/// engine/domain の currentEngineStatus。
private func currentEngineStatusForBridge() -> EngineStatus { currentEngineStatus() }
