package dev.ambientmemo.engine.android

import android.content.Context
import android.os.Build
import android.util.Log
import dev.ambientmemo.engine.domain.ModelDownloadFailure
import dev.ambientmemo.engine.domain.ModelDownloadPlan
import dev.ambientmemo.engine.domain.ModelDownloadState
import dev.ambientmemo.engine.sherpa.HttpModelTransport
import dev.ambientmemo.engine.sherpa.ModelDownloader
import dev.ambientmemo.engine.sherpa.ModelPackage
import dev.ambientmemo.engine.sherpa.ModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executors

private const val TAG = "AmbientMemoEngine"
private const val WORK_DIRECTORY = "model-downloads"

/**
 * モデルの取得の入口。通信を始めるのは [start] だけで、利用者の操作からだけ呼ぶ。
 *
 * 聞き取りとは別のスコープと、優先度の低い専用のスレッドで動かす。bzip2 の展開は CPU を使い続けるので、
 * 聞き取りの文字起こしを遅らせないようにする。
 */
class ModelDownloads internal constructor(
    context: Context,
    store: ModelStore,
    private val packages: List<ModelPackage>,
) {
    private val context = context.applicationContext
    private val downloader = ModelDownloader(
        store = store,
        workRoot = File(this.context.filesDir, WORK_DIRECTORY),
        transport = HttpModelTransport(),
    )
    private val runner = ModelDownloadRunner(
        scope = CoroutineScope(
            SupervisorJob() +
                Executors.newSingleThreadExecutor { task ->
                    Thread(task, "model-download").apply { priority = Thread.MIN_PRIORITY }
                }.asCoroutineDispatcher(),
        ),
        onFailure = { Log.e(TAG, "モデルを取得できません: ${it.javaClass.simpleName}") },
        download = { onProgress -> downloader.download(packages, onProgress) },
    )

    val state: StateFlow<ModelDownloadState> = runner.state

    /** 止まっているときの、取得するものの量。 */
    fun plan(): ModelDownloadPlan = downloader.plan(packages)

    /**
     * Foreground Service を開始するので、アプリが前面にある間に呼ぶ。
     * 動いている間と、モデルが揃っているときは何もしない。
     */
    fun start() {
        if (plan().isComplete || !runner.markStarting()) return
        val intent = ModelDownloadService.startIntent(context)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "取得の Service を開始できません: ${e.javaClass.simpleName}")
            abandonStart()
        }
    }

    /** 途中までのファイルは残すので、次は続きから読む。 */
    fun cancel() = runner.cancel()

    internal fun run() = runner.start()

    internal fun abandonStart() = runner.abandonStart(ModelDownloadFailure.UNKNOWN)
}
