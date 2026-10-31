package dev.ambientmemo.engine.sherpa

import dev.ambientmemo.engine.domain.ModelDownloadException
import dev.ambientmemo.engine.domain.ModelDownloadFailure
import dev.ambientmemo.engine.domain.ModelDownloadPhase
import dev.ambientmemo.engine.domain.ModelDownloadPlan
import dev.ambientmemo.engine.domain.ModelDownloadProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import java.io.File
import java.io.IOException

private const val PART_FILE = "artifact.part"
private const val STAGING_DIRECTORY = "staging"

/** 取得の後にも DB の書き込みなどができるように残す空き容量。 */
private const val RESERVED_BYTES = 64L * 1024 * 1024

/** 進み具合の通知は全体の 0.5% ごとにする。画面と通知を更新しすぎないため。 */
private const val PROGRESS_STEPS = 200L

/**
 * カタログの配布物を取得して [store] に置く。
 *
 * 作業は [workRoot] の下のパッケージごとのディレクトリで行う。[workRoot] は [store] と同じファイルシステムに置く。
 * 配布物を SHA-256 で確かめてから展開し、取り出したファイルも確かめてから [store] へ移すので、
 * 途中で止まっても確かめていないファイルが [store] に入らない。
 *
 * - 再開: 途中までの配布物を残し、次の取得で続きから読む。中身を信じるかは最後の SHA-256 で決める
 * - 掃除: 配布物が期待と違うときは作業のディレクトリごと消す。展開の途中のファイルは失敗でも中断でも消す
 *
 * ファイルを読み書きし続けるので、Main では呼ばない。並行して呼ばない。
 */
class ModelDownloader(
    private val store: ModelStore,
    private val workRoot: File,
    transport: ModelTransport,
    private val usableBytes: () -> Long = {
        workRoot.mkdirs()
        workRoot.usableSpace
    },
) {
    private val fetcher = ArtifactFetcher(transport, usableBytes)

    /** 大きさだけで判断するので、すぐに返る。 */
    fun plan(packages: List<ModelPackage>): ModelDownloadPlan {
        val pending = packages.filterNot { store.isInstalled(it) }
        return ModelDownloadPlan(
            totalBytes = pending.sumOf { sourceOf(it).sizeBytes },
            downloadedBytes = pending.sumOf { partOf(it).length().coerceAtMost(sourceOf(it).sizeBytes) },
        )
    }

    /**
     * [packages] のうち、入っていないものを取得する。
     *
     * @throws ModelDownloadException 取得できなかったとき。キャンセルでは途中のファイルを残す
     */
    suspend fun download(packages: List<ModelPackage>, onProgress: (ModelDownloadProgress) -> Unit) {
        val job = currentCoroutineContext().job
        removeStaleWork(packages)
        val (ready, pending) = packages.partition { store.verify(it) }
        ready.forEach { workOf(it).deleteRecursively() }
        val reporter = ProgressReporter(onProgress)
        val totalBytes = pending.sumOf { sourceOf(it).sizeBytes }
        var finishedBytes = 0L
        pending.forEach { modelPackage ->
            val offsetBytes = finishedBytes
            install(modelPackage, job, reporter) { written ->
                reporter.report(ModelDownloadPhase.DOWNLOADING, offsetBytes + written, totalBytes)
            }
            finishedBytes += sourceOf(modelPackage).sizeBytes
        }
    }

    private fun install(
        modelPackage: ModelPackage,
        job: Job,
        reporter: ProgressReporter,
        onDownloaded: (Long) -> Unit,
    ) {
        val source = sourceOf(modelPackage)
        val work = workOf(modelPackage)
        val part = partOf(modelPackage)
        val staging = File(work, STAGING_DIRECTORY)
        try {
            staging.deleteRecursively()
            if (!staging.mkdirs()) throw storageFailure(usableBytes(), IOException("ディレクトリを作れません"))
            checkSpace(modelPackage, source, part)
            fetcher.fetch(source, part, job, onDownloaded)
            verifyArtifact(source, part, job, reporter)
            when (source) {
                is ModelSource.SingleFile -> check(part.renameTo(File(staging, source.file.name)))
                is ModelSource.TarBz2 -> TarBz2Extractor(job) { read ->
                    reporter.report(ModelDownloadPhase.EXTRACTING, read, source.sizeBytes)
                }.extract(modelPackage.files, source.directory, part, staging)
            }
            store.install(modelPackage, staging)
            work.deleteRecursively()
        } catch (e: ModelDownloadException) {
            if (e.failure == ModelDownloadFailure.CORRUPTED) work.deleteRecursively()
            throw e
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun checkSpace(modelPackage: ModelPackage, source: ModelSource, part: File) {
        val remaining = (source.sizeBytes - part.length()).coerceAtLeast(0)
        val extracted = if (source is ModelSource.TarBz2) modelPackage.files.sumOf { it.sizeBytes } else 0
        val required = remaining + extracted + RESERVED_BYTES
        val usable = usableBytes()
        if (usable < required) {
            throw ModelDownloadException(ModelDownloadFailure.INSUFFICIENT_STORAGE, required - usable)
        }
    }

    private fun verifyArtifact(source: ModelSource, part: File, job: Job, reporter: ProgressReporter) {
        val hash = sha256Of(part) { read ->
            job.ensureActive()
            reporter.report(ModelDownloadPhase.VERIFYING, read, source.sizeBytes)
        }
        if (hash != source.sha256) throw ModelDownloadException(ModelDownloadFailure.CORRUPTED)
    }

    /** カタログから外れたモデルの途中のファイルが、容量を使い続けないようにする。 */
    private fun removeStaleWork(packages: List<ModelPackage>) {
        val current = packages.mapTo(HashSet()) { it.id }
        workRoot.listFiles()?.filterNot { it.name in current }?.forEach { it.deleteRecursively() }
    }

    private fun sourceOf(modelPackage: ModelPackage): ModelSource =
        checkNotNull(modelPackage.source) { "取得先がありません: ${modelPackage.id}" }

    private fun workOf(modelPackage: ModelPackage) = File(workRoot, modelPackage.id)

    private fun partOf(modelPackage: ModelPackage) = File(workOf(modelPackage), PART_FILE)
}

/** 同じ段階の通知を間引く。段階が変わったときと、段階の最後は必ず届ける。 */
private class ProgressReporter(private val onProgress: (ModelDownloadProgress) -> Unit) {
    private var phase: ModelDownloadPhase? = null
    private var reportedBytes = 0L

    fun report(phase: ModelDownloadPhase, completedBytes: Long, totalBytes: Long) {
        val step = totalBytes / PROGRESS_STEPS
        val isDue = phase != this.phase || completedBytes == totalBytes || completedBytes - reportedBytes >= step
        if (!isDue) return
        this.phase = phase
        reportedBytes = completedBytes
        onProgress(ModelDownloadProgress(phase, completedBytes, totalBytes))
    }
}
