import BackgroundTasks
import EngineDomain
import EngineStore
import Foundation
import UIKit

private let START_TIMEOUT_MILLIS: Int64 = 5_000
private let STAT_RETENTION_MILLIS: Int64 = 30 * 24 * 60 * 60 * 1_000
private let DIAGNOSTICS_SAMPLE_NANOS: UInt64 = 5 * 60 * 1_000_000_000
private let DIAGNOSTICS_EXPORT_DIRECTORY = "diagnostics-export"
private let SPEECH_WORK_DIRECTORY = "model-downloads"

/// ModelDownloader は渡されなかったパッケージの途中のファイルを消すので、組ごとに作業の場所を分ける。
private let ORGANIZER_WORK_DIRECTORY = "model-downloads-organizer"

/// 聞き取りを始められなかった理由。Android の `StartFailure` と同じ。
public enum StartFailure: String, Error, Sendable {
    case MICROPHONE_PERMISSION, TRANSCRIPTION_UNAVAILABLE, MICROPHONE_UNAVAILABLE
}

/// 自分の声の登録の状態。
public enum SelfEnrollmentStatus: Sendable { case enrolled, notEnrolled, unavailable }

/// 一緒に取得するモデルの組。数 GB の organizer は、聞き取りに要る speech とは別に利用者が選ぶ。
public enum ModelGroup: Sendable { case speech, organizer }

/// エンジンの変化。engine-bridge が JS のイベントに変える。
public enum EngineChange: Sendable {
    case capture(CaptureState)
    case modelDownload(ModelDownloadState)
    case utterances
    case memo(MemoChange)
    case enrollmentProgress(EnrollmentProgress)
    case organize
}

/// 手動の整理を始めたときの結果。JS の OrganizeStartResult と同じ並び。
public enum OrganizeStartResult: Sendable { case started, alreadyRunning, modelUnavailable, nothingToOrganize, failed }

/// 質問の進み具合。JS の AskPhase と同じ並び。読み込みの段階は、モデルを持つエンジンだけが知っている。
public enum AskStep: Sendable { case preparing, searching, answering }

/// 質問の終わり方。JS の MemoAnswerOutcome と同じ並び。
public enum AskOutcome: Sendable {
    case answered, notFound, modelUnavailable

    /// 整理か別の質問が動いている。モデルを 2 つ読み込むとメモリが足りない。
    case busy

    /// 実行基盤が動かなかった。
    case failed
}

/// 質問の答え。[text] は [AskOutcome.answered] のときだけある。[sources] は答えが根拠にしたメモで、答えが挙げた順。
public struct AskReply: Sendable {
    public let outcome: AskOutcome
    public let text: String?
    public let sources: [Memo]

    init(_ outcome: AskOutcome, _ text: String? = nil, _ sources: [Memo] = []) {
        self.outcome = outcome
        self.text = text
        self.sources = sources
    }
}

/// 話者と発話の数。
public struct SpeakerSummary: Sendable {
    public let speaker: Speaker
    public let utteranceCount: Int
}

/// DB を開けなかった。
public struct EngineStoreUnavailable: Error, CustomStringConvertible {
    public let description = "DB を開けません"
}

/**
 * プロセスに1つのエンジン。Android の `Engine` に当たり、依存の組み立てもここで行う。モデルは [ModelCatalog.ACTIVE] で差し替える。
 *
 * - 聞き取りは画面が前面にある間だけ始める。音声のバックグラウンド モードで続ける。自動では再開しない
 * - 整理は BGProcessingTask(充電中)と画面の操作で動かす。LLM は同じプロセスで、整理の間だけ持つ
 * - メモへの質問も同じ LLM を、質問の間だけ持つ。整理と質問は同時に動かさない
 * - 音声はディスクに書かない。文字起こし、メモ、話者の特徴量を端末の外へ送らない
 */
public final class AmbientEngine: @unchecked Sendable {
    public static let shared = AmbientEngine()

    private let models = ModelCatalog.ACTIVE
    private let speakerModel = ModelCatalog.ACTIVE_SPEAKER
    private let llmModel = ModelCatalog.ACTIVE_LLM
    private let clock = SystemClock()
    private let modelStore: ModelStore
    private let storeResult: Result<Opened, any Error>
    private let journal: DiagnosticsJournal
    private let speakerLock = AsyncMutex()
    private let recentVoices = RecentVoices()
    private let audioLevels = AudioLevelCounter()
    private let listeners: Guarded<[UUID: @Sendable (EngineChange) -> Void]>
    private let diagnosticsSampler = Guarded<Task<Void, Never>?>(nil)
    private let downloadGroup = Guarded(ModelGroup.speech)
    private let speechDownloader: ModelDownloader
    private let organizerDownloader: ModelDownloader
    private var captureRunner: CaptureRunner!
    private var enrollmentRunner: EnrollmentRunner!
    private var downloadRunner: ModelDownloadRunner!
    private var organizeRunner: OrganizeRunner!
    private let deviceConditions: DeviceConditions
    private let backgroundTask = Guarded<BGProcessingTask?>(nil)
    /// 動いている質問。整理の開始と同じロックで確かめ、LLM を 2 つ読み込まない。
    private let asking = Guarded<AskJob?>(nil)

    /// 動いている質問の1回分。[preempted] は、手動の整理に場所を譲るために止めたこと。
    private final class AskJob: @unchecked Sendable {
        var task: Task<AskReply, any Error>?
        var preempted = false
    }

    /// 開いた DB と、変更を知らせる包み。
    private struct Opened: Sendable {
        let store: EngineStore
        let utterances: ObservableUtteranceRepository
        let memos: ObservableMemoRepository
        let attempts: FileOrganizeAttempts
    }

    private init() {
        let support = (try? StorageDirectories.applicationSupport()) ?? FileManager.default.temporaryDirectory
        let modelsRoot = (try? StorageDirectories.models()) ?? support.appendingPathComponent("models")
        modelStore = ModelStore(root: modelsRoot)
        journal = DiagnosticsJournal(
            directory: (try? StorageDirectories.diagnostics()) ?? support.appendingPathComponent("diagnostics")
        )
        speechDownloader = ModelDownloader(
            store: modelStore,
            workRoot: (try? StorageDirectories.modelDownloads(SPEECH_WORK_DIRECTORY)) ?? support
        )
        organizerDownloader = ModelDownloader(
            store: modelStore,
            workRoot: (try? StorageDirectories.modelDownloads(ORGANIZER_WORK_DIRECTORY)) ?? support
        )
        deviceConditions = DeviceConditions()
        let listeners = Guarded<[UUID: @Sendable (EngineChange) -> Void]>([:])
        self.listeners = listeners
        storeResult = Result {
            // DB 専用のディレクトリにして、ディレクトリごとバックアップから外す
            let directory = try StorageDirectories.subdirectory("EngineStore")
            let url = directory.appendingPathComponent("ambient-memo.db")
            try EngineStoreFiles.prepareDatabaseFile(at: url)
            let store = try EngineStore(databaseURL: url)
            return Opened(
                store: store,
                utterances: ObservableUtteranceRepository(store.utterances) { listeners.current.values.forEach { $0(.utterances) } },
                memos: ObservableMemoRepository(store.memos) { change in
                    listeners.current.values.forEach { $0(.memo(change)) }
                },
                attempts: FileOrganizeAttempts(url: directory.appendingPathComponent("organize-attempts.json"))
            )
        }
        captureRunner = CaptureRunner(
            onChange: { [weak self] state in self?.captureChanged(state) },
            onFailure: { error in NSLog("AmbientMemoEngine: 聞き取りが止まりました: %@", String(describing: type(of: error))) },
            capture: { [weak self] source in try await self?.capture(source) },
            audioSource: { developmentAudioSource() ?? MicrophoneAudioSource() }
        )
        enrollmentRunner = EnrollmentRunner(
            precondition: { [weak self] in await self?.enrollmentPrecondition() ?? .CANCELLED },
            enroll: { [weak self] in
                guard let self else { throw CancellationError() }
                return try await runEnrollment()
            }
        )
        downloadRunner = ModelDownloadRunner(
            onChange: { [weak self] state in self?.emit(.modelDownload(state)) },
            onFailure: { error in NSLog("AmbientMemoEngine: モデルを取得できません: %@", String(describing: type(of: error))) },
            download: { [weak self] onProgress in try await self?.runDownload(onProgress) }
        )
        organizeRunner = OrganizeRunner(
            session: { [weak self] shouldContinue, onProgress in
                await self?.organizeSession(shouldContinue, onProgress) ?? .failed
            },
            device: { [weak self] in (self?.deviceConditions.isCharging ?? false, self?.deviceConditions.isTooHot ?? false) },
            onStatus: { [weak self] _ in self?.emit(.organize) },
            onFinished: { [weak self] _ in self?.organizeFinished() }
        )
        ensureDiagnosticsSampler()
    }

    // MARK: - 変化の知らせ

    /// 変化を受け取る。書き込みのスレッドで呼ぶので、受け取る側は素早く返す。戻り値で解除する。
    public func addListener(_ listener: @escaping @Sendable (EngineChange) -> Void) -> @Sendable () -> Void {
        let id = UUID()
        listeners.withLock { $0[id] = listener }
        return { [listeners] in listeners.withLock { _ = $0.removeValue(forKey: id) } }
    }

    private func emit(_ change: EngineChange) {
        for listener in listeners.current.values { listener(change) }
    }

    private func opened() throws -> Opened {
        try storeResult.get()
    }

    // MARK: - 聞き取り

    public var isTranscriptionReady: Bool { modelStore.isInstalled(models) }

    public var captureState: CaptureState { captureRunner.current }

    private func captureChanged(_ state: CaptureState) {
        switch state {
        case .listening: journal.record(CaptureChanged(isListening: true, isInterrupted: false))
        case let .stopped(interrupted): journal.record(CaptureChanged(isListening: false, isInterrupted: interrupted))
        case .starting, .stopping: break
        }
        emit(.capture(state))
    }

    /**
     * 聞き取りを始め、最初の音声が届くまで待つ。すでに聞き取り中なら何もしない。
     * 画面が前面にある間だけ始める。バックグラウンドからはマイクを開けないので、Android と同じ制限にする。
     */
    public func startCapture() async throws {
        if let failure = await precondition() { throw failure }
        _ = captureRunner.start()
        let settled = await captureRunner.settled(timeoutMillis: START_TIMEOUT_MILLIS)
        if settled != .listening {
            await captureRunner.stop()
            throw StartFailure.MICROPHONE_UNAVAILABLE
        }
    }

    private func precondition() async -> StartFailure? {
        let store = modelStore
        let models = models
        let verified = await Task.detached(priority: .userInitiated) { store.verify(models) }.value
        if !verified { return .TRANSCRIPTION_UNAVAILABLE }
        let usesMicrophone = developmentAudioSource() == nil
        if usesMicrophone, !isMicrophoneGranted() { return .MICROPHONE_PERMISSION }
        // 登録の聞き取りとマイクを取り合わない
        if enrollmentRunner.isRunning { return .MICROPHONE_UNAVAILABLE }
        let isForeground = await MainActor.run { UIApplication.shared.applicationState == .active }
        if !isForeground { return .MICROPHONE_UNAVAILABLE }
        return nil
    }

    /// 止まるまで待つ。
    public func stopCapture() async {
        await captureRunner.stop()
    }

    /// ネイティブのメモリは聞き取りの間だけ持つ。話者のモデルがなくても文字起こしは動かす。
    private func capture(_ audioSource: any AudioSource) async throws {
        ensureDiagnosticsSampler()
        let opened = try opened()
        let canIdentifySpeakers = modelStore.verify(speakerModel)
        await consolidateSpeakers()
        let vad = try SherpaVoiceActivityDetector(modelPath: modelStore.pathOf(models.vad.modelPackage, models.vad.model))
        let transcriber = SherpaTranscriber(store: modelStore, models: models)
        let embedder = SherpaSpeakerEmbedder(store: modelStore, model: speakerModel)
        defer {
            vad.close()
            transcriber.close()
            embedder.close()
        }
        let speakers = opened.store.speakers
        let pipeline = CapturePipeline(
            audioSource: audioSource,
            voiceActivityDetector: QuietGatedVoiceActivityDetector(vad, levels: audioLevels),
            transcriber: transcriber,
            utterances: opened.utterances,
            captureGaps: RecordingCaptureGaps(opened.store.captureGaps, journal),
            clock: clock,
            config: SherpaSettings.captureConfig(),
            transcriptionFilter: NoiseTextFilter(),
            speakerIdentifier: canIdentifySpeakers
                ? SpeakerIdentifier(embedder: embedder, speakers: speakers, clock: clock, decisionLog: speakers)
                : nil,
            recentVoices: recentVoices,
            speakerLock: speakerLock,
            diagnostics: journal
        )
        try await pipeline.run()
    }

    public func recentUtterances(limit: Int) async throws -> [Utterance] {
        try await opened().utterances.listRecent(limit: limit)
    }

    // MARK: - 話者

    public func speakers() async throws -> [Int64: Speaker] {
        let list = try await opened().store.speakers.listSpeakers()
        return Dictionary(uniqueKeysWithValues: list.map { ($0.id, $0) })
    }

    /// 増えすぎた仮の話者をまとめ直す。失敗しても聞き取りは止めない。
    public func consolidateSpeakers() async {
        do {
            let speakers = try opened().store.speakers
            let merged = try await speakerLock.withLock {
                try await speakers.prune(beforeMillis: clock.nowMillis() - STAT_RETENTION_MILLIS)
                return try await SpeakerConsolidation(speakers: speakers, modelVersion: speakerModel.version, clock: clock).run()
            }
            if merged > 0 { notifySpeakersChanged() }
        } catch {
            NSLog("AmbientMemoEngine: 話者をまとめ直せませんでした: %@", String(describing: type(of: error)))
        }
    }

    private func notifySpeakersChanged() {
        emit(.utterances)
        emit(.memo(.memos))
    }

    private func corrections() throws -> SpeakerCorrections {
        let opened = try opened()
        return SpeakerCorrections(
            speakers: opened.store.speakers,
            utterances: opened.utterances,
            voices: recentVoices,
            modelVersion: speakerModel.version,
            clock: clock
        )
    }

    public func speakerSummaries() async throws -> [SpeakerSummary] {
        let opened = try opened()
        let counts = try await opened.utterances.countBySpeaker()
        return try await opened.store.speakers.listSpeakers().map { SpeakerSummary(speaker: $0, utteranceCount: counts[$0.id] ?? 0) }
    }

    public func reassignUtterance(_ utteranceId: Int64, _ target: SpeakerTarget) async throws -> Bool {
        let corrections = try corrections()
        let changed = try await speakerLock.withLock { try await corrections.reassign(utteranceId, target) }
        if changed { notifySpeakersChanged() }
        return changed
    }

    public func mergeSpeakers(_ fromId: Int64, _ intoId: Int64) async throws -> Bool {
        let corrections = try corrections()
        let changed = try await speakerLock.withLock { try await corrections.merge(fromId, intoId) }
        if changed { notifySpeakersChanged() }
        return changed
    }

    /// 聞き取りや登録の途中は false を返す。
    public func resetSpeakers() async throws -> Bool {
        guard case .stopped = captureState, !enrollmentRunner.isRunning else { return false }
        let speakers = try opened().store.speakers
        try await speakerLock.withLock {
            try await speakers.resetSpeakers()
            try await speakers.prune(beforeMillis: Int64.max)
            recentVoices.clear()
        }
        notifySpeakersChanged()
        return true
    }

    public func renameSpeaker(_ speakerId: Int64, _ name: String) async throws -> Bool {
        let corrections = try corrections()
        let changed = try await speakerLock.withLock { try await corrections.rename(speakerId, name) }
        if changed { notifySpeakersChanged() }
        return changed
    }

    public func speakerDiagnostics() async throws -> SpeakerDiagnostics {
        let speakers = try opened().store.speakers
        return try await SpeakerDiagnosticsReader(log: speakers, speakers: speakers, modelVersion: speakerModel.version, clock: clock).read()
    }

    // MARK: - 自分の声の登録

    public func selfEnrollmentStatus() async throws -> SelfEnrollmentStatus {
        if !modelStore.isInstalled(speakerModel) { return .unavailable }
        let embeddings = try await opened().store.speakers.listEmbeddings(modelVersion: speakerModel.version, limit: 1)
        return embeddings.contains { $0.isSelf } ? .enrolled : .notEnrolled
    }

    /// マイクから自分の声を聞き取り、登録する。聞き取りとは同時に動かさない。
    public func enrollSelf() async throws {
        try await enrollmentRunner.run()
        notifySpeakersChanged()
    }

    public func cancelEnrollment() {
        enrollmentRunner.cancel()
    }

    private func enrollmentPrecondition() async -> EnrollmentFailure? {
        guard case .stopped = captureState else { return .LISTENING }
        let store = modelStore
        let models = models
        let speakerModel = speakerModel
        let verified = await Task.detached { store.verify(models) && store.verify(speakerModel) }.value
        if !verified { return .SPEAKER_MODEL_UNAVAILABLE }
        if developmentAudioSource() == nil, !isMicrophoneGranted() { return .MICROPHONE_PERMISSION }
        return nil
    }

    private func runEnrollment() async throws -> EnrollmentOutcome {
        let vad = try SherpaVoiceActivityDetector(modelPath: modelStore.pathOf(models.vad.modelPackage, models.vad.model))
        let embedder = SherpaSpeakerEmbedder(store: modelStore, model: speakerModel)
        defer {
            vad.close()
            embedder.close()
        }
        return try await SelfEnrollment(
            audioSource: developmentAudioSource() ?? MicrophoneAudioSource(),
            voiceActivityDetector: vad,
            embedder: embedder,
            speakers: try opened().store.speakers,
            clock: clock,
            captureConfig: SherpaSettings.enrollmentCaptureConfig(),
            onProgress: { [weak self] progress in self?.emit(.enrollmentProgress(progress)) }
        ).run()
    }

    // MARK: - モデルの取得

    /// 動いている取得か、最後に動いた取得の組。
    public var activeDownloadGroup: ModelGroup { downloadGroup.current }

    public var modelDownloadState: ModelDownloadState { downloadRunner.state }

    public func modelDownloadPlan(_ group: ModelGroup) -> ModelDownloadPlan {
        switch group {
        case .speech: speechDownloader.plan(ModelCatalog.DOWNLOADS)
        case .organizer: organizerDownloader.plan(ModelCatalog.ORGANIZER_DOWNLOADS)
        }
    }

    /// 利用者の操作でだけ呼ぶ。通信はここからしか始まらない。動いている間と、揃っているときは何もしない。
    public func startModelDownload(_ group: ModelGroup) {
        if modelDownloadPlan(group).isComplete { return }
        if case .Running = downloadRunner.state { return }
        downloadGroup.withLock { $0 = group }
        _ = downloadRunner.start()
    }

    /// 途中までのファイルは残すので、次は続きから読む。
    public func cancelModelDownload() {
        downloadRunner.cancel()
    }

    /// アプリを離れても、OS が許す間は続ける。止められたら途中までを残し、次は続きから読む。
    private func runDownload(_ onProgress: @escaping @Sendable (ModelDownloadProgress) -> Void) async throws {
        let group = downloadGroup.current
        let identifier = await MainActor.run {
            UIApplication.shared.beginBackgroundTask(withName: "model-download") { [weak self] in
                self?.downloadRunner.cancel()
            }
        }
        defer {
            Task { @MainActor in UIApplication.shared.endBackgroundTask(identifier) }
        }
        switch group {
        case .speech: try await speechDownloader.download(ModelCatalog.DOWNLOADS, onProgress: onProgress)
        case .organizer: try await organizerDownloader.download(ModelCatalog.ORGANIZER_DOWNLOADS, onProgress: onProgress)
        }
    }

    // MARK: - 動作の記録

    public func diagnosticsStatus() -> DiagnosticsStatus { journal.status() }

    /// 前の記録を消して始める。
    public func startDiagnostics() throws {
        // 記録の前に数えた分を、最初の記録に混ぜない
        _ = audioLevels.drain()
        try journal.start()
        ensureDiagnosticsSampler()
        captureChanged(captureState)
    }

    public func stopDiagnostics() throws { try journal.stop() }

    public func clearDiagnostics() { journal.clear() }

    /// 書き出したファイルを共有の画面に渡す。記録がなければ false を返す。
    public func shareDiagnostics() async throws -> Bool {
        guard let file = try await exportDiagnostics() else { return false }
        await DiagnosticsSharing.share(file)
        return true
    }

    /// 書き出したファイルを、ファイル App の利用者が選んだ場所に写す。記録がなければ false を返す。
    public func saveDiagnostics() async throws -> Bool {
        guard let file = try await exportDiagnostics() else { return false }
        _ = await DiagnosticsSharing.export(file)
        return true
    }

    private func exportDiagnostics() async throws -> URL? {
        let summary = encodeSpeakerSummary(try await speakerDiagnostics())
        let header = await MainActor.run { diagnosticsHeader() }
        let output = FileManager.default.temporaryDirectory.appendingPathComponent(DIAGNOSTICS_EXPORT_DIRECTORY, isDirectory: true)
        return try journal.export(to: output, header: header, footer: summary)
    }

    /// 記録の間、端末の状態を定期的に残す。端末が眠っている間は止まり、起きると続く。
    private func ensureDiagnosticsSampler() {
        diagnosticsSampler.withLock { sampler in
            guard sampler == nil, journal.status().isRecording else { return }
            sampler = Task.detached(priority: .utility) { [weak self] in
                while let self, !Task.isCancelled, journal.status().isRecording {
                    let sample = await MainActor.run { deviceSample() }
                    journal.record(sample)
                    if let levels = audioLevels.drain() { journal.record(levels) }
                    try? await Task.sleep(nanoseconds: DIAGNOSTICS_SAMPLE_NANOS)
                }
                self?.diagnosticsSampler.withLock { $0 = nil }
            }
        }
    }

    // MARK: - メモと整理

    /// メモの読み書き。書くと [addListener] の相手に届く。
    public func memoRepository() throws -> any MemoRepository { try opened().memos }

    public var organizeStatus: OrganizeStatus { organizeRunner.status }

    /// 整理のモデルが揃っていて、この版で LLM を動かせるか。
    public var isOrganizerModelReady: Bool {
        LocalLlm.isSupported && modelStore.isInstalled(llmModel.modelPackage)
    }

    public var isLlmSupported: Bool { LocalLlm.isSupported }

    public func organizeBacklog() async throws -> OrganizeBacklog {
        let opened = try opened()
        return try await EngineDomain_organizeBacklog(
            memos: opened.memos,
            speakers: Array(try await speakers().values),
            clock: clock,
            timeZone: TimeZone.current
        )
    }

    /**
     * 利用者の操作でだけ呼ぶ。始めるものがないときはモデルを読み込まない。
     *
     * 質問が動いていれば、Android と同じく質問を止め、そのモデルを解放してから読み込む。止めた質問は busy で終わる。
     */
    public func startOrganize() async throws -> OrganizeStartResult {
        let status = organizeRunner.status
        if status.isRunning, status.isManual { return .alreadyRunning }
        if !isOrganizerModelReady { return .modelUnavailable }
        if !status.isRunning, try await organizeBacklog().readyChunks == 0 { return .nothingToOrganize }
        await preemptAsk()
        let started = asking.withLock { job -> OrganizeStart in
            // 譲るのを待つ間に、別の質問が始まった
            if job != nil { return .busy }
            return organizeRunner.start(.MANUAL)
        }
        switch started {
        case .started, .joined: return .started
        case .busy: return .failed
        }
    }

    /// 動いている質問を止め、終わるまで待つ。
    private func preemptAsk() async {
        let task = asking.withLock { job -> Task<AskReply, any Error>? in
            job?.preempted = true
            job?.task?.cancel()
            return job?.task
        }
        _ = await task?.result
    }

    /// 区切りで止まる。動いていなければ何もしない。
    public func stopOrganize() {
        organizeRunner.requestStop()
    }

    /// BGProcessingTask から呼ぶ。聞き取りの間は動かさず、次の機会に任せる。
    public func runScheduledOrganize(_ task: BGProcessingTask) {
        OrganizeSchedule.ensureScheduled()
        guard case .stopped = captureState, isOrganizerModelReady else {
            task.setTaskCompleted(success: true)
            return
        }
        // 質問の間は、モデルを 2 つ読み込まないように次の機会に任せる
        let started = asking.withLock { job in job == nil ? organizeRunner.start(.SCHEDULED) : .busy }
        guard started == .started else {
            task.setTaskCompleted(success: true)
            return
        }
        backgroundTask.withLock { $0 = task }
        task.expirationHandler = { [weak self] in
            // OS に止められるので、区切りを待たずに捨てる。未処理のまま残るので次の実行が続きから処理する
            self?.organizeRunner.abandon(.timeLimit)
            self?.completeBackgroundTask(success: false)
        }
    }

    /// 定期実行を予約する。アプリを開くたびに呼んでよい。
    public func ensureOrganizeScheduled() {
        OrganizeSchedule.ensureScheduled()
    }

    private func organizeFinished() {
        completeBackgroundTask(success: true)
    }

    private func completeBackgroundTask(success: Bool) {
        let task = backgroundTask.withLock { task -> BGProcessingTask? in
            defer { task = nil }
            return task
        }
        task?.setTaskCompleted(success: success)
    }

    /// 読み込みから解放までの1回分。Android の `OrganizeSession` と同じ。
    private func organizeSession(
        _ shouldContinue: @escaping @Sendable () -> Bool,
        _ onProgress: @escaping @Sendable (OrganizeProgress) -> Void
    ) async -> OrganizeRunResult {
        guard isOrganizerModelReady else { return .modelMissing }
        guard shouldContinue() else { return .stopped }
        let opened: Opened
        do { opened = try self.opened() } catch { return .failed }
        let organizeLog = journal.log(.ORGANIZE)
        let llm = LocalLlm(
            modelVersion: llmModel.version,
            modelPath: modelStore.pathOf(llmModel.modelPackage, llmModel.model),
            cacheDirectory: ((try? StorageDirectories.llmCache()) ?? FileManager.default.temporaryDirectory).path,
            onBackendChosen: { organizeLog.record(LlmBackendChosen($0)) }
        )
        defer { llm.close() }
        let sample = await MainActor.run { deviceSample() }
        organizeLog.record(sample)
        let loadStarted = clock.nowMillis()
        do {
            try await llm.load()
            organizeLog.record(LlmLoaded(loadMillis: clock.nowMillis() - loadStarted, isLoaded: true))
        } catch {
            organizeLog.record(LlmLoaded(loadMillis: clock.nowMillis() - loadStarted, isLoaded: false))
            NSLog("AmbientMemoEngine: LLM を読み込めません: %@", String(describing: type(of: error)))
            return .failed
        }
        let started = clock.nowMillis()
        do {
            let job = OrganizeJob(
                memos: opened.memos,
                speakers: opened.store.speakers,
                llm: TimedLlm(llm, organizeLog),
                clock: clock,
                timeZone: TimeZone.current,
                attempts: opened.attempts
            )
            let result = try await job.run(shouldContinue: shouldContinue, onProgress: onProgress)
            organizeLog.record(
                OrganizeFinished(
                    outcome: result.outcome,
                    memoCount: result.memoCount,
                    unreadableCount: result.unreadableCount,
                    totalMillis: clock.nowMillis() - started
                )
            )
            let after = await MainActor.run { deviceSample() }
            organizeLog.record(after)
            switch result.outcome {
            case .COMPLETED: return .completed
            case .STOPPED: return .stopped
            case .LLM_FAILED: return .failed
            }
        } catch {
            return .failed
        }
    }

    // MARK: - メモへの質問

    /**
     * 過去のメモを探して、質問に答える。整理のモデルを読み込むので、数十秒から数分かかる。[onStep] で進み具合を知らせる。
     *
     * 整理か別の質問が動いていれば [AskOutcome.busy] を返す。実行基盤の失敗は [AskOutcome.failed] で返す。
     * [cancelAsk] か呼び出し側のタスクの取り消しで止めたら [CancellationError] を投げる。空の質問は [IllegalArgumentException] を投げる。
     */
    public func askMemos(_ question: String, onStep: @escaping @Sendable (AskStep) -> Void) async throws -> AskReply {
        let question = question.trimmingCharacters(in: .whitespacesAndNewlines)
        if question.isEmpty { throw IllegalArgumentException("質問が空です") }
        if !isOrganizerModelReady { return AskReply(.modelUnavailable) }
        // 整理の開始と同じロックの中で確かめて、タスクまで作る。止める依頼がタスクのない間に来ないように
        let claimed = asking.withLock { current -> (AskJob, Task<AskReply, any Error>)? in
            if current != nil || organizeRunner.status.isRunning { return nil }
            let job = AskJob()
            let task = Task.detached(priority: .userInitiated) { [weak self] () async throws -> AskReply in
                guard let self else { throw CancellationError() }
                // 整理が待つのはこのタスクなので、終わる前に場所を空ける
                defer { asking.withLock { if $0 === job { $0 = nil } } }
                return try await askSession(question, onStep)
            }
            job.task = task
            current = job
            return (job, task)
        }
        guard let (job, task) = claimed else { return AskReply(.busy) }
        do {
            return try await withTaskCancellationHandler {
                try await task.value
            } onCancel: {
                task.cancel()
            }
        } catch is CancellationError {
            if asking.withLock({ _ in job.preempted }) { return AskReply(.busy) }
            throw CancellationError()
        }
    }

    /// 動いている質問を止める。[askMemos] は [CancellationError] を投げる。動いていなければ何もしない。
    public func cancelAsk() {
        asking.withLock { $0?.task?.cancel() }
    }

    /// 読み込みから解放までの1回分。Android の `AskSession` と同じく、記録には読み込みと答えの数だけを残す。
    private func askSession(_ question: String, _ onStep: @escaping @Sendable (AskStep) -> Void) async throws -> AskReply {
        let opened: Opened
        do { opened = try self.opened() } catch { return AskReply(.failed) }
        onStep(.preparing)
        let askLog = journal.log(.ORGANIZE)
        let llm = LocalLlm(
            modelVersion: llmModel.version,
            modelPath: modelStore.pathOf(llmModel.modelPackage, llmModel.model),
            cacheDirectory: ((try? StorageDirectories.llmCache()) ?? FileManager.default.temporaryDirectory).path,
            onBackendChosen: { askLog.record(LlmBackendChosen($0)) }
        )
        defer { llm.close() }
        let loadStarted = clock.nowMillis()
        do {
            try await llm.load()
            askLog.record(LlmLoaded(loadMillis: clock.nowMillis() - loadStarted, isLoaded: true))
        } catch {
            askLog.record(LlmLoaded(loadMillis: clock.nowMillis() - loadStarted, isLoaded: false))
            try Task.checkCancellation()
            NSLog("AmbientMemoEngine: LLM を読み込めません: %@", String(describing: type(of: error)))
            return AskReply(.failed)
        }
        // 読み込みはネイティブの中で止められないので、終わってから確かめる
        try Task.checkCancellation()
        let started = clock.nowMillis()
        let answerer = MemoQuestionAnswerer(
            memos: opened.memos,
            speakers: opened.store.speakers,
            llm: TimedLlm(llm, askLog),
            clock: clock,
            timeZone: TimeZone.current
        )
        let answer: MemoAnswer
        do {
            answer = try await answerer.answer(question) { phase in
                onStep(phase == .SEARCHING ? .searching : .answering)
            }
        } catch {
            // 取り消しかどうかは例外の型ではなく状態で判断する。実行基盤の失敗の中身は文字起こしを含みうるので、外へ渡さない
            try Task.checkCancellation()
            NSLog("AmbientMemoEngine: 質問に答えられません: %@", String(describing: type(of: error)))
            return AskReply(.failed)
        }
        askLog.record(
            AskFinished(
                outcome: answer.outcome,
                searchRounds: answer.searchRounds,
                sourceCount: answer.sources.count,
                totalMillis: clock.nowMillis() - started
            )
        )
        switch answer.outcome {
        case .ANSWERED: return AskReply(.answered, answer.text, answer.sources)
        case .NOT_FOUND: return AskReply(.notFound)
        }
    }
}

/// OrganizeJob.swift の `organizeBacklog` を、AmbientEngine の同じ名前のメソッドの中から呼ぶ。
private func EngineDomain_organizeBacklog(
    memos: any MemoRepository,
    speakers: [Speaker],
    clock: any Clock,
    timeZone: TimeZone
) async throws -> OrganizeBacklog {
    try await organizeBacklog(memos: memos, speakers: speakers, clock: clock, timeZone: timeZone)
}
