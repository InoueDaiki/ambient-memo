import EngineDomain
import Foundation

private let PART_FILE = "artifact.part"
private let STAGING_DIRECTORY = "staging"

/// 取得の後にも DB の書き込みなどができるように残す空き容量。
private let RESERVED_BYTES: Int64 = 64 * 1024 * 1024

/// 進み具合の通知は全体の 0.5% ごとにする。画面を更新しすぎないため。
private let PROGRESS_STEPS: Int64 = 200

/**
 * カタログの配布物を取得して [store] に置く。Android の `ModelDownloader` と同じ流れにする(docs/エンジン設計.md の「モデルの取得」)。
 *
 * - 再開: 途中までの配布物を残し、次の取得で続きから読む。中身を信じるかは最後の SHA-256 で決める
 * - 掃除: 配布物が期待と違うときは作業のディレクトリごと消す。展開の途中のファイルは失敗でも中断でも消す
 *
 * 並行して呼ばない。
 */
final class ModelDownloader: Sendable {
    private let store: ModelStore
    private let workRoot: URL
    private let transport: any ModelTransport

    init(store: ModelStore, workRoot: URL, transport: any ModelTransport = HttpModelTransport()) {
        self.store = store
        self.workRoot = workRoot
        self.transport = transport
    }

    /// 大きさだけで判断するので、すぐに返る。
    func plan(_ packages: [ModelPackage]) -> ModelDownloadPlan {
        let pending = packages.filter { !store.isInstalled($0) }
        return ModelDownloadPlan(
            totalBytes: pending.reduce(0) { $0 + sourceOf($1).sizeBytes },
            downloadedBytes: pending.reduce(0) { $0 + min(max(fileSize(partOf($1).path), 0), sourceOf($1).sizeBytes) }
        )
    }

    /// [packages] のうち、入っていないものを取得する。取り消しでは途中のファイルを残す。
    func download(_ packages: [ModelPackage], onProgress: @escaping @Sendable (ModelDownloadProgress) -> Void) async throws {
        removeStaleWork(packages)
        var pending: [ModelPackage] = []
        for modelPackage in packages {
            try Task.checkCancellation()
            if store.verify(modelPackage) {
                try? FileManager.default.removeItem(at: workOf(modelPackage))
            } else {
                pending.append(modelPackage)
            }
        }
        let reporter = ProgressReporter(onProgress)
        let totalBytes = pending.reduce(0) { $0 + sourceOf($1).sizeBytes }
        var finishedBytes: Int64 = 0
        for modelPackage in pending {
            let offsetBytes = finishedBytes
            try await install(modelPackage, reporter) { written in
                reporter.report(.DOWNLOADING, offsetBytes + written, totalBytes)
            }
            finishedBytes += sourceOf(modelPackage).sizeBytes
        }
    }

    private func install(
        _ modelPackage: ModelPackage,
        _ reporter: ProgressReporter,
        onDownloaded: @escaping @Sendable (Int64) -> Void
    ) async throws {
        let source = sourceOf(modelPackage)
        let work = workOf(modelPackage)
        let part = partOf(modelPackage)
        let staging = work.appendingPathComponent(STAGING_DIRECTORY, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: staging) }
        do {
            try? FileManager.default.removeItem(at: staging)
            do {
                try StorageDirectories.prepare(staging)
            } catch {
                throw storageFailure(usableBytes(workRoot), error)
            }
            try checkSpace(modelPackage, source, part)
            try await ArtifactFetcher(transport: transport, workRoot: workRoot).fetch(source, part, onDownloaded)
            try verifyArtifact(source, part, reporter)
            switch source {
            case let .singleFile(_, file):
                try FileManager.default.moveItem(at: part, to: staging.appendingPathComponent(file.name))
            case let .tarBz2(_, sizeBytes, _, directory):
                try TarBz2Extractor { read in reporter.report(.EXTRACTING, read, sizeBytes) }
                    .extract(modelPackage.files, directory: directory, archive: part, target: staging)
            }
            try store.install(modelPackage, staged: staging)
            try? FileManager.default.removeItem(at: work)
        } catch let error as ModelDownloadException {
            if error.failure == .CORRUPTED { try? FileManager.default.removeItem(at: work) }
            throw error
        }
    }

    private func checkSpace(_ modelPackage: ModelPackage, _ source: ModelSource, _ part: URL) throws {
        let remaining = max(source.sizeBytes - max(fileSize(part.path), 0), 0)
        let extracted: Int64 = if case .tarBz2 = source { modelPackage.files.reduce(0) { $0 + $1.sizeBytes } } else { 0 }
        let required = remaining + extracted + RESERVED_BYTES
        let usable = usableBytes(workRoot)
        if usable < required { throw ModelDownloadException(.INSUFFICIENT_STORAGE, shortageBytes: required - usable) }
    }

    private func verifyArtifact(_ source: ModelSource, _ part: URL, _ reporter: ProgressReporter) throws {
        let hash = try sha256Of(part) { read in
            try Task.checkCancellation()
            reporter.report(.VERIFYING, read, source.sizeBytes)
        }
        if hash != source.sha256 { throw ModelDownloadException(.CORRUPTED) }
    }

    /// カタログから外れたモデルの途中のファイルが、容量を使い続けないようにする。
    private func removeStaleWork(_ packages: [ModelPackage]) {
        let current = Set(packages.map(\.id))
        let entries = (try? FileManager.default.contentsOfDirectory(atPath: workRoot.path)) ?? []
        for entry in entries where !current.contains(entry) {
            try? FileManager.default.removeItem(at: workRoot.appendingPathComponent(entry))
        }
    }

    private func sourceOf(_ modelPackage: ModelPackage) -> ModelSource {
        guard let source = modelPackage.source else { preconditionFailure("取得先がありません: \(modelPackage.id)") }
        return source
    }

    private func workOf(_ modelPackage: ModelPackage) -> URL {
        workRoot.appendingPathComponent(modelPackage.id, isDirectory: true)
    }

    private func partOf(_ modelPackage: ModelPackage) -> URL {
        workOf(modelPackage).appendingPathComponent(PART_FILE)
    }
}

/// 配布物を [part] へ読む。[part] があれば続きから読み、取得先が先頭から返したら書き直す。
private struct ArtifactFetcher {
    let transport: any ModelTransport
    let workRoot: URL

    func fetch(_ source: ModelSource, _ part: URL, _ onDownloaded: @escaping @Sendable (Int64) -> Void) async throws {
        if fileSize(part.path) > source.sizeBytes { try? FileManager.default.removeItem(at: part) }
        if fileSize(part.path) == source.sizeBytes { return }
        if !FileManager.default.fileExists(atPath: part.path) {
            FileManager.default.createFile(atPath: part.path, contents: nil)
        }
        let writer = try PartWriter(part, sizeBytes: source.sizeBytes, workRoot: workRoot, onDownloaded: onDownloaded)
        defer { writer.close() }
        do {
            do {
                try await open(source, writer, offsetBytes: max(fileSize(part.path), 0))
            } catch is ModelRangeError {
                // 残っているファイルが配布物と合っていない
                try writer.truncate(0)
                try await open(source, writer, offsetBytes: 0)
            }
        } catch let error as ModelDownloadException {
            throw error
        } catch {
            if Task.isCancelled { throw CancellationError() }
            throw ModelDownloadException(.NETWORK, cause: error)
        }
        if writer.written != source.sizeBytes { throw ModelDownloadException(.NETWORK) }
    }

    private func open(_ source: ModelSource, _ writer: PartWriter, offsetBytes: Int64) async throws {
        try await transport.fetch(
            source.url,
            offsetBytes: offsetBytes,
            onStart: { start in try writer.truncate(start) },
            onData: { data in try writer.append(data) }
        )
    }
}

/// 途中の配布物への書き込み。通信のキューから呼ぶ。
private final class PartWriter: @unchecked Sendable {
    private let handle: FileHandle
    private let sizeBytes: Int64
    private let workRoot: URL
    private let onDownloaded: @Sendable (Int64) -> Void
    private(set) var written: Int64 = 0

    init(_ part: URL, sizeBytes: Int64, workRoot: URL, onDownloaded: @escaping @Sendable (Int64) -> Void) throws {
        handle = try FileHandle(forWritingTo: part)
        self.sizeBytes = sizeBytes
        self.workRoot = workRoot
        self.onDownloaded = onDownloaded
    }

    /// 本文の先頭の位置から書く。取得先が先頭から返したら書き直す。
    func truncate(_ offset: Int64) throws {
        do {
            try handle.truncate(atOffset: UInt64(offset))
            try handle.seekToEnd()
        } catch {
            throw storageFailure(usableBytes(workRoot), error)
        }
        written = offset
    }

    func append(_ data: Data) throws {
        written += Int64(data.count)
        if written > sizeBytes { throw ModelDownloadException(.CORRUPTED) }
        do {
            try handle.write(contentsOf: data)
        } catch {
            throw storageFailure(usableBytes(workRoot), error)
        }
        onDownloaded(written)
    }

    func close() {
        try? handle.close()
    }
}

/// 同じ段階の通知を間引く。段階が変わったときと、段階の最後は必ず届ける。
private final class ProgressReporter: @unchecked Sendable {
    private let onProgress: @Sendable (ModelDownloadProgress) -> Void
    private let lock = NSLock()
    private var phase: ModelDownloadPhase?
    private var reportedBytes: Int64 = 0

    init(_ onProgress: @escaping @Sendable (ModelDownloadProgress) -> Void) {
        self.onProgress = onProgress
    }

    func report(_ phase: ModelDownloadPhase, _ completedBytes: Int64, _ totalBytes: Int64) {
        let isDue: Bool = lock.withLock {
            let step = totalBytes / PROGRESS_STEPS
            let due = phase != self.phase || completedBytes == totalBytes || completedBytes - reportedBytes >= step
            if due {
                self.phase = phase
                reportedBytes = completedBytes
            }
            return due
        }
        if isDue { onProgress(ModelDownloadProgress(phase, completedBytes, totalBytes)) }
    }
}

/**
 * モデルの取得を1本だけ動かし、状態を公開する。Android の `ModelDownloadRunner` と同じ。
 *
 * 聞き取りとは状態もタスクも共有しない。どちらかが止まっても、もう一方に影響しない。
 */
final class ModelDownloadRunner: @unchecked Sendable {
    private static let PREPARING = ModelDownloadState.Running(progress: ModelDownloadProgress(.DOWNLOADING, 0, 0))

    private let download: @Sendable (@escaping @Sendable (ModelDownloadProgress) -> Void) async throws -> Void
    private let onChange: @Sendable (ModelDownloadState) -> Void
    private let onFailure: @Sendable (any Error) -> Void
    private let lock = NSLock()
    private var task: Task<Void, Never>?
    private var generation = 0
    private var current: ModelDownloadState = .Idle

    init(
        onChange: @escaping @Sendable (ModelDownloadState) -> Void,
        onFailure: @escaping @Sendable (any Error) -> Void = { _ in },
        download: @escaping @Sendable (@escaping @Sendable (ModelDownloadProgress) -> Void) async throws -> Void
    ) {
        self.onChange = onChange
        self.onFailure = onFailure
        self.download = download
    }

    var state: ModelDownloadState { lock.withLock { current } }

    /// 動いていなければ始める。止めた直後なら、前の取得が終わるのを待ってファイルを奪い合わない。
    func start() -> Bool {
        let started: Bool = lock.withLock {
            if case .Running = current { return false }
            generation += 1
            let mine = generation
            let previous = task
            current = Self.PREPARING
            task = Task.detached(priority: .utility) { [self] in
                await previous?.value
                await run(mine)
            }
            return true
        }
        if started { onChange(Self.PREPARING) }
        return started
    }

    /// すぐに Idle にする。途中までのファイルは残すので、次は続きから読む。
    func cancel() {
        lock.withLock {
            task?.cancel()
            generation += 1
            current = .Idle
        }
        onChange(.Idle)
    }

    private func run(_ mine: Int) async {
        do {
            try await download { [self] progress in update(mine, .Running(progress: progress)) }
            update(mine, .Idle)
        } catch let error as ModelDownloadException {
            onFailure(error)
            update(mine, .Failed(failure: error.failure, shortageBytes: error.shortageBytes))
        } catch {
            if Task.isCancelled { return }
            onFailure(error)
            update(mine, .Failed(failure: .UNKNOWN))
        }
    }

    /// 止めた後の取得の知らせで、状態を上書きしない。
    private func update(_ mine: Int, _ state: ModelDownloadState) {
        let changed: Bool = lock.withLock {
            guard generation == mine else { return false }
            current = state
            return true
        }
        if changed { onChange(state) }
    }
}
