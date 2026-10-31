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

/** ModelDownloader は渡されなかったパッケージの途中のファイルを消すので、組ごとに作業の場所を分ける。 */
private const val ORGANIZER_WORK_DIRECTORY = "model-downloads-organizer"

/** 一緒に取得するモデルの組。数 GB の [ORGANIZER] は、聞き取りに要る [SPEECH] とは別に利用者が選ぶ。 */
enum class ModelGroup { SPEECH, ORGANIZER }

/**
 * モデルの取得の入口。通信を始めるのは [start] だけで、利用者の操作からだけ呼ぶ。
 *
 * 取得は組をまたいで同時に1本だけ。どの組の状態かは [activeGroup] で見る。
 *
 * 聞き取りとは別のスコープと、優先度の低い専用のスレッドで動かす。bzip2 の展開は CPU を使い続けるので、
 * 聞き取りの文字起こしを遅らせないようにする。
 */
class ModelDownloads internal constructor(
    context: Context,
    store: ModelStore,
    speech: List<ModelPackage>,
    organizer: List<ModelPackage>,
) {
    private class Group(val packages: List<ModelPackage>, val downloader: ModelDownloader)

    private val context = context.applicationContext
    private val groups = mapOf(
        ModelGroup.SPEECH to group(store, speech, WORK_DIRECTORY),
        ModelGroup.ORGANIZER to group(store, organizer, ORGANIZER_WORK_DIRECTORY),
    )

    /** 動いている取得か、最後に動いた取得の組。 */
    @Volatile
    var activeGroup: ModelGroup = ModelGroup.SPEECH
        private set

    private val runner = ModelDownloadRunner(
        scope = CoroutineScope(
            SupervisorJob() +
                Executors.newSingleThreadExecutor { task ->
                    Thread(task, "model-download").apply { priority = Thread.MIN_PRIORITY }
                }.asCoroutineDispatcher(),
        ),
        onFailure = { Log.e(TAG, "モデルを取得できません: ${it.javaClass.simpleName}") },
        download = { onProgress ->
            val group = groupOf(activeGroup)
            group.downloader.download(group.packages, onProgress)
        },
    )

    val state: StateFlow<ModelDownloadState> = runner.state

    private fun group(store: ModelStore, packages: List<ModelPackage>, directory: String) = Group(
        packages,
        ModelDownloader(store, File(context.filesDir, directory), HttpModelTransport()),
    )

    private fun groupOf(group: ModelGroup): Group = groups.getValue(group)

    /** 止まっているときの、取得するものの量。 */
    fun plan(group: ModelGroup = ModelGroup.SPEECH): ModelDownloadPlan = groupOf(group).let {
        it.downloader.plan(it.packages)
    }


    /**
     * Foreground Service を開始するので、アプリが前面にある間に呼ぶ。
     * 動いている間と、モデルが揃っているときは何もしない。
     */
    fun start(group: ModelGroup = ModelGroup.SPEECH) {
        if (plan(group).isComplete || !runner.markStarting()) return
        activeGroup = group
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
