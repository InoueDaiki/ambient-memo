package dev.ambientmemo.engine.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dev.ambientmemo.engine.adapters.SqliteCaptureGapRepository
import dev.ambientmemo.engine.adapters.SqliteMemoRepository
import dev.ambientmemo.engine.adapters.SqliteSpeakerRepository
import dev.ambientmemo.engine.adapters.SqliteUtteranceRepository
import dev.ambientmemo.engine.adapters.openAndroidDatabase
import dev.ambientmemo.engine.domain.AudioLevelCounter
import dev.ambientmemo.engine.domain.AudioSource
import dev.ambientmemo.engine.domain.CaptureChanged
import dev.ambientmemo.engine.domain.CapturePipeline
import dev.ambientmemo.engine.domain.DiagnosticProcess
import dev.ambientmemo.engine.domain.EnrollmentOutcome
import dev.ambientmemo.engine.domain.MemoRepository
import dev.ambientmemo.engine.domain.NoiseTextFilter
import dev.ambientmemo.engine.domain.QuietGatedVoiceActivityDetector
import dev.ambientmemo.engine.domain.EnrollmentProgress
import dev.ambientmemo.engine.domain.RecentVoices
import dev.ambientmemo.engine.domain.RecordingCaptureGaps
import dev.ambientmemo.engine.domain.SelfEnrollment
import dev.ambientmemo.engine.domain.Speaker
import dev.ambientmemo.engine.domain.SpeakerConsolidation
import dev.ambientmemo.engine.domain.SpeakerCorrections
import dev.ambientmemo.engine.domain.SpeakerDiagnostics
import dev.ambientmemo.engine.domain.SpeakerDiagnosticsReader
import dev.ambientmemo.engine.domain.SpeakerIdentifier
import dev.ambientmemo.engine.domain.SpeakerTarget
import dev.ambientmemo.engine.domain.SystemClock
import dev.ambientmemo.engine.domain.Utterance
import dev.ambientmemo.engine.sherpa.ModelCatalog
import dev.ambientmemo.engine.sherpa.ModelStore
import dev.ambientmemo.engine.sherpa.SherpaSettings
import dev.ambientmemo.engine.sherpa.android.androidSpeakerEmbedder
import dev.ambientmemo.engine.sherpa.android.androidTranscriber
import dev.ambientmemo.engine.sherpa.android.androidVoiceActivityDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

private const val TAG = "AmbientMemoEngine"
private const val START_TIMEOUT_MILLIS = 5_000L
private const val MODELS_DIRECTORY = "models"
private const val ENROLLMENT_PROGRESS_BUFFER = 8
private const val STAT_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1_000
private const val DIAGNOSTICS_SAMPLE_MILLIS = 5L * 60 * 1_000

/** 文字起こしの状態。モデルが内部ストレージになければ [UNAVAILABLE]。 */
enum class TranscriptionMode { READY, UNAVAILABLE }

/** 聞き取りを始められなかった理由。 */
enum class StartFailure { MICROPHONE_PERMISSION, TRANSCRIPTION_UNAVAILABLE, MICROPHONE_UNAVAILABLE }

class EngineStartException(val failure: StartFailure) : Exception(failure.name)

/** 自分の声の登録の状態。話者のモデルが内部ストレージになければ [UNAVAILABLE]。 */
enum class SelfEnrollmentStatus { ENROLLED, NOT_ENROLLED, UNAVAILABLE }

/**
 * プロセスに1つのエンジン。Service と engine-bridge はここを通して同じ状態を見る。
 *
 * 依存の組み立てもここで行う。モデルは [ModelCatalog.ACTIVE] で差し替える。
 */
@Suppress("TooManyFunctions") // Service と engine-bridge の入口をここに集めている
class Engine private constructor(context: Context) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val models = ModelCatalog.ACTIVE
    private val speakerModel = ModelCatalog.ACTIVE_SPEAKER
    private val modelStore = ModelStore(File(this.context.filesDir, MODELS_DIRECTORY))
    private val database by lazy { openAndroidDatabase(this.context) }
    internal val utteranceRepository by lazy {
        ObservableUtteranceRepository(SqliteUtteranceRepository(database)) { changes.tryEmit(Unit) }
    }
    private val speakerRepository by lazy { SqliteSpeakerRepository(database) }

    /** 判定から保存までと、手の操作やまとめ直しが、話者を同時に書き換えないようにする。 */
    private val speakerLock = Mutex()
    private val recentVoices = RecentVoices()

    /** 聞き取りの音の大きさの分布。記録の間だけ、端末の状態と一緒に残す。 */
    private val audioLevels = AudioLevelCounter()
    private val speakerCorrections by lazy {
        SpeakerCorrections(speakerRepository, utteranceRepository, recentVoices, speakerModel.version, SystemClock)
    }
    private val memoListeners = CopyOnWriteArraySet<(MemoChange) -> Unit>()
    private val diagnostics = DiagnosticsJournal(this.context.filesDir, DiagnosticProcess.MAIN, SystemClock)
    private var diagnosticsSampler: Job? = null

    /** メモと ToDo の読み書きはすべてここを通す。整理ジョブも使い、書くと [addMemoListener] の相手に届く。 */
    internal val memoRepository: MemoRepository by lazy {
        ObservableMemoRepository(SqliteMemoRepository(database)) { change ->
            memoListeners.forEach { it(change) }
        }
    }

    /** JS が見るメモと ToDo。 */
    val memoBridge by lazy { MemoBridge(memoRepository, ::speakers, SystemClock, organizeMonitor) }

    /** 手動の整理の入口と、`:organize` の見守り。LLM はこのプロセスに持たない。 */
    private val organizeMonitor by lazy {
        OrganizeMonitor(this.context, { modelDownloads.plan(ModelGroup.ORGANIZER).isComplete }, ::notifyMemoChanged)
    }

    private val enrollmentRunner = EnrollmentRunner(scope, ::enrollmentPrecondition, ::runEnrollment)
    private val enrollmentChanges = MutableSharedFlow<EnrollmentProgress>(
        extraBufferCapacity = ENROLLMENT_PROGRESS_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
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

    /** モデルの取得。聞き取りとは別に動く。 */
    val modelDownloads =
        ModelDownloads(this.context, modelStore, ModelCatalog.DOWNLOADS, ModelCatalog.ORGANIZER_DOWNLOADS)

    /**
     * メモか ToDo が変わると、書いたスレッドで呼ぶ。書き込みが戻る前に届くので、
     * 完了の Promise を解決するより先に、画面へ知らせられる。
     */
    fun addMemoListener(listener: (MemoChange) -> Unit) {
        memoListeners += listener
    }

    fun removeMemoListener(listener: (MemoChange) -> Unit) {
        memoListeners -= listener
    }

    /** 別のプロセスの整理が書いた変更を、画面へ伝える。 */
    internal fun notifyMemoChanged(change: MemoChange) {
        memoListeners.forEach { it(change) }
    }

    val captureState: StateFlow<CaptureState> = runner.state

    /** 整理の状態が変わるたびに流れる。中身は [memoBridge] の organizeStatus で読む。 */
    val organizeChanges: Flow<Unit> get() = organizeMonitor.statuses.map { }

    /** 発話が保存されるたびに流れる。 */
    val utterancesChanged: SharedFlow<Unit> = changes.asSharedFlow()

    /** 声の登録の進み具合。 */
    val enrollmentProgress: SharedFlow<EnrollmentProgress> = enrollmentChanges.asSharedFlow()

    val transcriptionMode: TranscriptionMode
        get() = if (modelStore.isInstalled(models)) TranscriptionMode.READY else TranscriptionMode.UNAVAILABLE

    init {
        OrganizeSchedule.ensureScheduled(this.context)
        // 動いている整理に、アプリを開いた時点で繋ぐ
        organizeMonitor
        scope.launch { captureState.collect(::recordCaptureState) }
        ensureDiagnosticsSampler()
    }

    private fun recordCaptureState(state: CaptureState) {
        when (state) {
            CaptureState.Listening -> diagnostics.record(CaptureChanged(isListening = true, isInterrupted = false))
            is CaptureState.Stopped -> diagnostics.record(CaptureChanged(false, state.interrupted))
            CaptureState.Starting, CaptureState.Stopping -> Unit
        }
    }

    /**
     * 記録の間、端末の状態を定期的に残す。端末が眠っている間は止まり、起きると続く。
     * 期限で記録が止まると終わる。プロセスが作り直されたときは、アプリか聞き取りが動いた時点で再開する。
     */
    @Synchronized
    private fun ensureDiagnosticsSampler() {
        if (diagnosticsSampler?.isActive == true || !diagnostics.status().isRecording) return
        diagnosticsSampler = scope.launch {
            while (isActive && diagnostics.status().isRecording) {
                diagnostics.record(deviceSample(context))
                audioLevels.drain()?.let(diagnostics::record)
                delay(DIAGNOSTICS_SAMPLE_MILLIS)
            }
        }
    }

    fun diagnosticsStatus(): DiagnosticsStatus = diagnostics.status()

    /** 前の記録を消して始める。 */
    fun startDiagnostics() {
        // 記録の前に数えた分を、最初の記録に混ぜない
        audioLevels.drain()
        diagnostics.start()
        ensureDiagnosticsSampler()
        recordCaptureState(captureState.value)
    }

    fun stopDiagnostics() = diagnostics.stop()

    fun clearDiagnostics() = diagnostics.clear()

    /** 書き出したファイルを共有の画面に渡す。共有の画面は [activity] の上に開く。記録がなければ false を返す。 */
    suspend fun shareDiagnostics(activity: Activity?): Boolean {
        val file = exportDiagnostics() ?: return false
        withContext(Dispatchers.Main) { shareDiagnosticsFile(context, activity, file) }
        return true
    }

    /** 書き出したファイルを端末のダウンロードに保存する。記録がなければ false を返す。Android 10 より前は保存できない。 */
    suspend fun saveDiagnosticsToDownloads(): Boolean {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "Android 10 より前はダウンロードに保存できない" }
        val file = exportDiagnostics() ?: return false
        withContext(Dispatchers.IO) { saveDiagnosticsFileToDownloads(context, file) }
        return true
    }

    private suspend fun exportDiagnostics(): File? {
        val summary = encodeSpeakerSummary(speakerDiagnostics())
        return withContext(Dispatchers.IO) {
            diagnostics.export(
                File(context.cacheDir, DIAGNOSTICS_EXPORT_DIRECTORY),
                diagnosticsHeader(context),
                summary,
            )
        }
    }

    /**
     * ネイティブのメモリは聞き取りの間だけ持つ。パイプラインは文字起こしが終わってから戻るので、ここで解放できる。
     *
     * 話者のモデルがなくても文字起こしは動かす。そのときの発話の話者は不明になる。
     */
    private suspend fun capture(audioSource: AudioSource) {
        ensureDiagnosticsSampler()
        val canIdentifySpeakers = withContext(Dispatchers.IO) { modelStore.verify(speakerModel) }
        consolidateSpeakers()
        androidVoiceActivityDetector(modelStore, models).use { voiceActivityDetector ->
            androidTranscriber(modelStore, models).use { transcriber ->
                androidSpeakerEmbedder(modelStore, speakerModel).use { embedder ->
                    CapturePipeline(
                        audioSource = audioSource,
                        voiceActivityDetector = QuietGatedVoiceActivityDetector(
                            voiceActivityDetector,
                            levels = audioLevels,
                        ),
                        transcriber = transcriber,
                        utterances = utteranceRepository,
                        captureGaps = RecordingCaptureGaps(SqliteCaptureGapRepository(database), diagnostics),
                        clock = SystemClock,
                        config = SherpaSettings.captureConfig(),
                        processingContext = Dispatchers.Default,
                        transcriptionFilter = NoiseTextFilter(),
                        speakerIdentifier = if (canIdentifySpeakers) {
                            SpeakerIdentifier(embedder, speakerRepository, SystemClock, decisionLog = speakerRepository)
                        } else {
                            null
                        },
                        recentVoices = recentVoices,
                        speakerLock = speakerLock,
                        diagnostics = diagnostics,
                    ).run()
                }
            }
        }
    }

    /**
     * 増えすぎた仮の話者をまとめ直す。上限以下なら何もしないので、何度呼んでもよい。
     * 失敗しても聞き取りは止めない。次の機会にやり直す。
     */
    @Suppress("TooGenericExceptionCaught") // まとめ直しの失敗で文字起こしを止めない
    suspend fun consolidateSpeakers() {
        try {
            val merged = speakerLock.withLock {
                speakerRepository.prune(SystemClock.nowMillis() - STAT_RETENTION_MILLIS)
                SpeakerConsolidation(speakerRepository, speakerModel.version, SystemClock).run()
            }
            if (merged > 0) notifySpeakersChanged()
        } catch (e: Exception) {
            Log.e(TAG, "話者をまとめ直せませんでした: ${e.javaClass.simpleName}")
        }
    }

    private fun notifySpeakersChanged() {
        changes.tryEmit(Unit)
        notifyMemoChanged(MemoChange.MEMOS)
    }

    /** 話者を発話の数と一緒に返す。 */
    suspend fun speakerSummaries(): List<SpeakerSummary> {
        val counts = utteranceRepository.countBySpeaker()
        return speakerRepository.listSpeakers().map { SpeakerSummary(it, counts[it.id] ?: 0) }
    }

    /** 発話の話者を手で直す。発話や話者がなければ false を返す。 */
    suspend fun reassignUtterance(utteranceId: Long, target: SpeakerTarget): Boolean =
        speakerLock.withLock { speakerCorrections.reassign(utteranceId, target) }
            .also { if (it) notifySpeakersChanged() }

    suspend fun mergeSpeakers(fromId: Long, intoId: Long): Boolean =
        speakerLock.withLock { speakerCorrections.merge(fromId, intoId) }
            .also { if (it) notifySpeakersChanged() }

    /** 自分の声も消すので、登録し直すまで「自分」の判定はしない。聞き取りや登録の途中は false を返す。 */
    suspend fun resetSpeakers(): Boolean {
        if (captureState.value !is CaptureState.Stopped || enrollmentRunner.isRunning) return false
        speakerLock.withLock {
            speakerRepository.resetSpeakers()
            speakerRepository.prune(Long.MAX_VALUE)
            recentVoices.clear()
        }
        notifySpeakersChanged()
        return true
    }

    suspend fun renameSpeaker(speakerId: Long, name: String): Boolean =
        speakerLock.withLock { speakerCorrections.rename(speakerId, name) }
            .also { if (it) notifySpeakersChanged() }

    /** 端末の中で話者の判定を確かめる値。音声も特徴量も含まない。 */
    suspend fun speakerDiagnostics(): SpeakerDiagnostics =
        SpeakerDiagnosticsReader(speakerRepository, speakerRepository, speakerModel.version, SystemClock).read()

    suspend fun selfEnrollmentStatus(): SelfEnrollmentStatus = when {
        !modelStore.isInstalled(speakerModel) -> SelfEnrollmentStatus.UNAVAILABLE
        speakerRepository.listEmbeddings(speakerModel.version, limit = 1).any { it.isSelf } ->
            SelfEnrollmentStatus.ENROLLED
        else -> SelfEnrollmentStatus.NOT_ENROLLED
    }

    /**
     * マイクから自分の声を聞き取り、登録する。音声はメモリの中だけで扱い、特徴量を作ったら捨てる。
     * 画面の操作で呼ぶので、マイクは前面のアプリとして開く。聞き取りとは同時に動かさない。
     *
     * @throws SelfEnrollmentException 登録できなかったとき
     */
    suspend fun enrollSelf() {
        enrollmentRunner.run()
        notifySpeakersChanged()
    }

    /** 登録の聞き取りを止める。何も記録しない。動いていなければ何もしない。 */
    fun cancelEnrollment() = enrollmentRunner.cancel()

    private suspend fun enrollmentPrecondition(): EnrollmentFailure? = when {
        captureState.value !is CaptureState.Stopped -> EnrollmentFailure.LISTENING
        !withContext(Dispatchers.IO) { modelStore.verify(models) && modelStore.verify(speakerModel) } ->
            EnrollmentFailure.SPEAKER_MODEL_UNAVAILABLE
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
            EnrollmentFailure.MICROPHONE_PERMISSION
        else -> null
    }

    private suspend fun runEnrollment(): EnrollmentOutcome =
        androidVoiceActivityDetector(modelStore, models).use { voiceActivityDetector ->
            androidSpeakerEmbedder(modelStore, speakerModel).use { embedder ->
                SelfEnrollment(
                    audioSource = developmentAudioSource(context) ?: microphoneAudioSource(),
                    voiceActivityDetector = voiceActivityDetector,
                    embedder = embedder,
                    speakers = speakerRepository,
                    clock = SystemClock,
                    captureConfig = SherpaSettings.enrollmentCaptureConfig(),
                    onProgress = { enrollmentChanges.tryEmit(it) },
                ).run()
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
        // 登録の聞き取りとマイクを取り合わない
        enrollmentRunner.isRunning -> StartFailure.MICROPHONE_UNAVAILABLE
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

    /** 話者を id から引けるように返す。 */
    suspend fun speakers(): Map<Long, Speaker> = speakerRepository.listSpeakers().associateBy { it.id }

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
