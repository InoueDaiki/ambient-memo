package dev.ambientmemo.engine.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dev.ambientmemo.engine.adapters.SqliteCaptureGapRepository
import dev.ambientmemo.engine.adapters.SqliteUtteranceRepository
import dev.ambientmemo.engine.adapters.openAndroidDatabase
import dev.ambientmemo.engine.domain.AudioSource
import dev.ambientmemo.engine.domain.CapturePipeline
import dev.ambientmemo.engine.domain.NoiseTextFilter
import dev.ambientmemo.engine.domain.SystemClock
import dev.ambientmemo.engine.domain.Utterance
import dev.ambientmemo.engine.sherpa.ModelCatalog
import dev.ambientmemo.engine.sherpa.ModelStore
import dev.ambientmemo.engine.sherpa.SherpaSettings
import dev.ambientmemo.engine.sherpa.android.androidTranscriber
import dev.ambientmemo.engine.sherpa.android.androidVoiceActivityDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

private const val TAG = "AmbientMemoEngine"
private const val START_TIMEOUT_MILLIS = 5_000L
private const val MODELS_DIRECTORY = "models"

/** 文字起こしの状態。モデルが内部ストレージになければ [UNAVAILABLE]。 */
enum class TranscriptionMode { READY, UNAVAILABLE }

/** 聞き取りを始められなかった理由。 */
enum class StartFailure { MICROPHONE_PERMISSION, TRANSCRIPTION_UNAVAILABLE, MICROPHONE_UNAVAILABLE }

class EngineStartException(val failure: StartFailure) : Exception(failure.name)

/**
 * プロセスに1つのエンジン。Service と engine-bridge はここを通して同じ状態を見る。
 *
 * 依存の組み立てもここで行う。モデルは [ModelCatalog.ACTIVE] で差し替える。
 */
class Engine private constructor(context: Context) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val models = ModelCatalog.ACTIVE
    private val modelStore = ModelStore(File(this.context.filesDir, MODELS_DIRECTORY))
    private val database by lazy { openAndroidDatabase(this.context) }
    private val utteranceRepository by lazy {
        ObservableUtteranceRepository(SqliteUtteranceRepository(database)) { changes.tryEmit(Unit) }
    }
    private val changes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val runner = CaptureRunner(
        scope = scope,
        onFailure = { Log.e(TAG, "聞き取りが止まりました: ${it.javaClass.simpleName}") },
        capture = ::capture,
        audioSource = { developmentAudioSource(this.context) ?: microphoneAudioSource() },
    )

    val captureState: StateFlow<CaptureState> = runner.state

    /** 発話が保存されるたびに流れる。 */
    val utterancesChanged: SharedFlow<Unit> = changes.asSharedFlow()

    val transcriptionMode: TranscriptionMode
        get() = if (modelStore.isInstalled(models)) TranscriptionMode.READY else TranscriptionMode.UNAVAILABLE

    /** ネイティブのメモリは聞き取りの間だけ持つ。パイプラインは文字起こしが終わってから戻るので、ここで解放できる。 */
    private suspend fun capture(audioSource: AudioSource) {
        androidVoiceActivityDetector(modelStore, models).use { voiceActivityDetector ->
            androidTranscriber(modelStore, models).use { transcriber ->
                CapturePipeline(
                    audioSource = audioSource,
                    voiceActivityDetector = voiceActivityDetector,
                    transcriber = transcriber,
                    utterances = utteranceRepository,
                    captureGaps = SqliteCaptureGapRepository(database),
                    clock = SystemClock,
                    config = SherpaSettings.captureConfig(),
                    processingContext = Dispatchers.Default,
                    transcriptionFilter = NoiseTextFilter(),
                ).run()
            }
        }
    }

    /**
     * Foreground Service を開始し、聞き取りが始まるまで待つ。すでに聞き取り中なら何もしない。
     *
     * マイクの Foreground Service は、アプリが前面にある間にしか開始できない。
     *
     * @throws EngineStartException 始められなかったとき
     */
    suspend fun startCapture() {
        precondition()?.let { throw EngineStartException(it) }
        if (runner.markStarting()) requestService()
        val settled = withTimeoutOrNull(START_TIMEOUT_MILLIS) {
            captureState.first { it is CaptureState.Listening || it is CaptureState.Stopped }
        }
        if (settled !is CaptureState.Listening) {
            stopCapture()
            runner.abandonStart()
            throw EngineStartException(StartFailure.MICROPHONE_UNAVAILABLE)
        }
    }

    private suspend fun precondition(): StartFailure? = when {
        !withContext(Dispatchers.IO) { modelStore.verify(models) } -> StartFailure.TRANSCRIPTION_UNAVAILABLE
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
            StartFailure.MICROPHONE_PERMISSION
        else -> null
    }

    private fun requestService() {
        val intent = CaptureService.startIntent(context)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: IllegalStateException) {
            // アプリが前面にないと、API 31 以降は ForegroundServiceStartNotAllowedException になる
            Log.e(TAG, "Service を開始できません: ${e.javaClass.simpleName}")
            runner.abandonStart()
        }
    }

    /** 止まるまで待つ。Service は状態を見て自分で終わる。 */
    suspend fun stopCapture() {
        runner.stop()
    }

    /** 新しい順に最大 [limit] 件を返す。 */
    suspend fun recentUtterances(limit: Int): List<Utterance> = utteranceRepository.listRecent(limit)

    /** OS に Service を止められたときに、開いたままのマイクを残さず、欠落を記録する。 */
    internal fun stopCaptureInBackground() {
        scope.launch { runner.stop() }
    }

    internal fun runService() = runner.start()

    internal fun abandonServiceStart() = runner.abandonStart(interrupted = true)

    companion object {
        @Volatile
        private var instance: Engine? = null

        fun get(context: Context): Engine = instance ?: synchronized(this) {
            instance ?: Engine(context).also { instance = it }
        }
    }
}
