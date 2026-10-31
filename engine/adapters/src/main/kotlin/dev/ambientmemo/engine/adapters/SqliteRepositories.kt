package dev.ambientmemo.engine.adapters

import dev.ambientmemo.engine.adapters.db.AmbientMemoDatabase
import dev.ambientmemo.engine.domain.CaptureGap
import dev.ambientmemo.engine.domain.CaptureGapReason
import dev.ambientmemo.engine.domain.CaptureGapRepository
import dev.ambientmemo.engine.domain.NewUtterance
import dev.ambientmemo.engine.domain.Utterance
import dev.ambientmemo.engine.domain.UtteranceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

class SqliteUtteranceRepository(
    private val database: AmbientMemoDatabase,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : UtteranceRepository {
    override suspend fun insert(utterance: NewUtterance): Utterance = withContext(ioContext) {
        val id = database.transactionWithResult {
            database.utteranceQueries.insert(
                utterance.startedAtMillis,
                utterance.endedAtMillis,
                utterance.text,
                utterance.confidence,
                utterance.createdAtMillis,
                utterance.speakerId,
                utterance.speakerConfidence,
            )
            database.utteranceQueries.lastInsertedId().executeAsOne()
        }
        Utterance(
            id = id,
            startedAtMillis = utterance.startedAtMillis,
            endedAtMillis = utterance.endedAtMillis,
            text = utterance.text,
            confidence = utterance.confidence,
            createdAtMillis = utterance.createdAtMillis,
            speakerId = utterance.speakerId,
            speakerConfidence = utterance.speakerConfidence,
        )
    }

    override suspend fun listOverlapping(fromMillis: Long, toMillis: Long): List<Utterance> =
        withContext(ioContext) {
            database.utteranceQueries
                .listOverlapping(toMillis, fromMillis, ::toUtterance)
                .executeAsList()
        }

    override suspend fun listRecent(limit: Int): List<Utterance> = withContext(ioContext) {
        database.utteranceQueries
            .listRecent(limit.toLong(), ::toUtterance)
            .executeAsList()
    }
}

/** SQLDelight の行の順に受ける。読み出す場所はすべてここを通す。 */
@Suppress("LongParameterList", "UnusedParameter") // 引数は utterance の列
internal fun toUtterance(
    id: Long,
    startedAtMillis: Long,
    endedAtMillis: Long,
    body: String,
    confidence: Double?,
    createdAtMillis: Long,
    organizedAtMillis: Long?,
    speakerId: Long?,
    speakerConfidence: Double?,
) = Utterance(id, startedAtMillis, endedAtMillis, body, confidence, createdAtMillis, speakerId, speakerConfidence)

class SqliteCaptureGapRepository(
    private val database: AmbientMemoDatabase,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : CaptureGapRepository {
    override suspend fun insert(gap: CaptureGap): Unit = withContext(ioContext) {
        database.captureGapQueries.insert(gap.startedAtMillis, gap.endedAtMillis, gap.reason.toColumn())
    }

    override suspend fun listOverlapping(fromMillis: Long, toMillis: Long): List<CaptureGap> =
        withContext(ioContext) {
            database.captureGapQueries
                .listOverlapping(toMillis, fromMillis) { _, startedAt, endedAt, reason ->
                    CaptureGap(startedAt, endedAt, reason.toCaptureGapReason())
                }
                .executeAsList()
        }
}

// enum の名前を変えても保存済みの値が読めるように、列の値は対応表で固定する
private fun CaptureGapReason.toColumn(): String = when (this) {
    CaptureGapReason.PROCESSING_BACKLOG -> "processing_backlog"
    CaptureGapReason.PROCESSING_FAILED -> "processing_failed"
    CaptureGapReason.SOURCE_DISCONTINUITY -> "source_discontinuity"
    CaptureGapReason.PIPELINE_STOPPED -> "pipeline_stopped"
}

private fun String.toCaptureGapReason(): CaptureGapReason =
    CaptureGapReason.entries.firstOrNull { it.toColumn() == this } ?: error("未知の欠落の理由です: $this")
