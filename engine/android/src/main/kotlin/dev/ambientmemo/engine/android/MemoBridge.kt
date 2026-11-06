package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.Clock
import dev.ambientmemo.engine.domain.Memo
import dev.ambientmemo.engine.domain.MemoRepository
import dev.ambientmemo.engine.domain.Speaker
import dev.ambientmemo.engine.domain.SpeakerDiagnostics
import dev.ambientmemo.engine.domain.SpeakerNameSource
import dev.ambientmemo.engine.domain.SpeakerTarget
import dev.ambientmemo.engine.domain.Utterance
import dev.ambientmemo.engine.domain.organizeBacklog
import java.util.TimeZone

/** JS が読む形。engine-bridge はここの結果をそのまま渡す。 */
typealias WireObject = Map<String, Any?>

/**
 * メモと質問の JS への境界。形は `modules/engine-bridge/src/memo-client.ts` の Native の型と合わせる。
 *
 * Expo に依存しない部分をここに置き、JVM のテストで本物の SQLite を通す。
 * id は JS では文字列なので、数でない id は「ない」ものとして扱う。
 */
class MemoBridge internal constructor(
    private val memos: MemoRepository,
    private val speakers: suspend () -> Map<Long, Speaker>,
    private val clock: Clock,
    private val organize: OrganizeControl,
    private val asks: AskControl,
    private val timeZone: TimeZone = TimeZone.getDefault(),
) {
    suspend fun listMemos(limit: Int): List<WireObject> = memos.listRecentMemos(limit).map { it.toWire() }

    suspend fun getMemo(id: String): WireObject? {
        val memo = id.toLongOrNull()?.let { memos.findMemo(it) } ?: return null
        val speakersById = speakers()
        return memo.toWire() + mapOf(
            "utterances" to memos.listMemoUtterances(memo.id).map { it.toWire(speakersById) },
        )
    }

    /**
     * 過去のメモを探して答える。[onProgress] には JS の進み具合の名前を渡す。
     * 整理のモデルを使うので、整理が動いている間は動かさない。
     *
     * @throws IllegalArgumentException 質問が空のとき
     * @throws AskCancelledException [cancelAsk] で止めたとき
     */
    suspend fun askMemos(question: String, onProgress: (String) -> Unit): WireObject {
        require(question.isNotBlank()) { "質問が空です" }
        val reply = when {
            !organize.isModelReady() -> AskReply(AskEnding.MODEL_MISSING)
            organize.status.isRunning -> AskReply(AskEnding.BUSY)
            else -> asks.ask(question.trim()) { step ->
                onProgress(
                    when (step) {
                        AskStep.PREPARING -> "preparing"
                        AskStep.SEARCHING -> "searching"
                        AskStep.ANSWERING -> "answering"
                    },
                )
            }
        }
        val isAnswered = reply.ending == AskEnding.ANSWERED
        // 答えを作っている間に消えたメモは挙げない
        val sources = if (isAnswered) reply.sourceIds.mapNotNull { memos.findMemo(it) } else emptyList()
        return mapOf(
            "outcome" to when (reply.ending) {
                AskEnding.ANSWERED -> "answered"
                AskEnding.NOT_FOUND -> "not-found"
                AskEnding.MODEL_MISSING -> "model-unavailable"
                AskEnding.BUSY -> "busy"
                AskEnding.FAILED -> "failed"
            },
            "answer" to reply.text?.takeIf { isAnswered },
            "sources" to sources.map { it.toWire() },
        )
    }

    /** 動いていなければ何もしない。 */
    fun cancelAsk() = asks.cancel()

    suspend fun organizeStatus(): WireObject {
        val backlog = backlog()
        val status = organize.status
        return mapOf(
            "state" to when (status.phase) {
                OrganizePhase.IDLE -> "idle"
                OrganizePhase.PREPARING -> "preparing"
                OrganizePhase.ORGANIZING -> "organizing"
                OrganizePhase.STOPPING -> "stopping"
            },
            "completedChunks" to status.completedChunks,
            "totalChunks" to status.totalChunks,
            "pendingChunks" to backlog.readyChunks,
            "isWaitingForSilence" to backlog.hasOngoing,
            "lastResult" to when (status.ending) {
                null -> null
                OrganizeEnding.COMPLETED -> "completed"
                OrganizeEnding.STOPPED -> "stopped"
                OrganizeEnding.TOO_HOT -> "too-hot"
                OrganizeEnding.MODEL_MISSING -> "model-unavailable"
                OrganizeEnding.FAILED -> "failed"
                OrganizeEnding.TIME_LIMIT -> "time-limit"
            },
        )
    }

    /**
     * 利用者の操作でだけ呼ぶ。始めるものがないときはモデルを読み込まない。
     * [beforeStart] は、始めると決まってから OS の確認を出すために呼ぶ。
     */
    suspend fun startOrganize(beforeStart: suspend () -> Unit = {}): String = when {
        organize.status.isRunning && organize.status.isManual -> "already-running"
        !organize.isModelReady() -> "model-unavailable"
        !organize.status.isRunning && backlog().readyChunks == 0 -> "nothing-to-organize"
        else -> {
            beforeStart()
            if (organize.start()) "started" else "failed"
        }
    }

    /** 区切りで止まる。動いていなければ何もしない。 */
    fun stopOrganize() {
        if (organize.status.isRunning) organize.stop()
    }

    private suspend fun backlog() = organizeBacklog(memos, speakers().values.toList(), clock, timeZone)
}

/** 特徴量は JS へ渡さない。JS が知るのは表示に使う名前だけ。 */
fun Utterance.toWire(speakers: Map<Long, Speaker>): WireObject {
    val speaker = speakerId?.let(speakers::get)
    return mapOf(
        "id" to id.toString(),
        "text" to text,
        "speaker" to when {
            speaker == null -> "unknown"
            speaker.isSelf -> "self"
            else -> "other"
        },
        "speakerId" to speaker?.id?.toString(),
        "speakerName" to speaker?.displayName,
        "startedAt" to startedAtMillis.toDouble(),
    )
}

/** 話者と発話の数。 */
class SpeakerSummary(
    val speaker: Speaker,
    val utteranceCount: Int,
)

fun SpeakerSummary.toWire(): WireObject = mapOf(
    "id" to speaker.id.toString(),
    "name" to speaker.displayName,
    "kind" to when {
        speaker.isSelf -> "self"
        speaker.nameSource == SpeakerNameSource.PROVISIONAL -> "provisional"
        else -> "named"
    },
    "utteranceCount" to utteranceCount,
)

/** JS の指定を直す先にする。読めなければ null。 */
fun parseSpeakerTarget(target: String): SpeakerTarget? = when (target) {
    "self" -> SpeakerTarget.Self
    "unknown" -> SpeakerTarget.Unknown
    "new" -> SpeakerTarget.NewOther
    else -> target.toLongOrNull()?.let(SpeakerTarget::Existing)
}

fun SpeakerDiagnostics.toWire(): WireObject = mapOf(
    "days" to days,
    "selfRuns" to selfRuns.toDouble(),
    "otherRuns" to otherRuns.toDouble(),
    "unknownRuns" to unknownRuns.toDouble(),
    "selfSeconds" to (selfMillis / MILLIS_PER_SECOND).toDouble(),
    "otherSeconds" to (otherMillis / MILLIS_PER_SECOND).toDouble(),
    "unknownSeconds" to (unknownMillis / MILLIS_PER_SECOND).toDouble(),
    "histogram" to histogram.map {
        mapOf(
            "floor" to it.floor.toDouble(),
            "self" to it.selfRuns.toDouble(),
            "other" to it.otherRuns.toDouble(),
            "unknown" to it.unknownRuns.toDouble(),
        )
    },
    "provisionalSpeakers" to provisionalSpeakers,
    "hasAdaptedVoice" to hasAdaptedVoice,
    "selfThreshold" to config.selfThreshold.toDouble(),
    "unknownFloor" to config.unknownFloor.toDouble(),
)

private const val MILLIS_PER_SECOND = 1_000L

private fun Memo.toWire(): WireObject = mapOf(
    "id" to id.toString(),
    "title" to title,
    "summary" to summary,
    "startedAt" to startedAtMillis.toDouble(),
    "endedAt" to endedAtMillis.toDouble(),
)
