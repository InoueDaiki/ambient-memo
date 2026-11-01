package dev.ambientmemo.engine.adapters

import dev.ambientmemo.engine.adapters.db.AmbientMemoDatabase
import dev.ambientmemo.engine.domain.SelfAdaptation
import dev.ambientmemo.engine.domain.Speaker
import dev.ambientmemo.engine.domain.SpeakerDecision
import dev.ambientmemo.engine.domain.SpeakerDecisionBucket
import dev.ambientmemo.engine.domain.SpeakerDecisionLog
import dev.ambientmemo.engine.domain.SpeakerDecisionSample
import dev.ambientmemo.engine.domain.SpeakerEmbedding
import dev.ambientmemo.engine.domain.SpeakerMerge
import dev.ambientmemo.engine.domain.SpeakerNameSource
import dev.ambientmemo.engine.domain.SpeakerRepository
import dev.ambientmemo.engine.domain.StoredSpeakerEmbedding
import dev.ambientmemo.engine.domain.scoreBucket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext

private const val SELF_NAME = "自分"
private const val PROVISIONAL_PREFIX = "話者"
private const val MILLIS_PER_DAY = 86_400_000L

@Suppress("TooManyFunctions") // 話者の表を読み書きする窓口をここに集めている
class SqliteSpeakerRepository(
    private val database: AmbientMemoDatabase,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : SpeakerRepository, SpeakerDecisionLog {
    private val queries = database.speakerQueries

    override suspend fun listSpeakers(): List<Speaker> = withContext(ioContext) {
        queries.listAll(::toSpeaker).executeAsList()
    }

    override suspend fun listEmbeddings(modelVersion: String, limit: Int): List<StoredSpeakerEmbedding> =
        withContext(ioContext) {
            queries.listEmbeddings(modelVersion, limit.toLong()) {
                    speakerId, isSelf, nameSource, vector, sampleCount, voicedMillis, updatedAtMillis ->
                StoredSpeakerEmbedding(
                    speakerId,
                    isSelf == 1L,
                    vector.toFloats(),
                    sampleCount.toInt(),
                    voicedMillis,
                    nameSource.toSpeakerNameSource(),
                    updatedAtMillis,
                )
            }.executeAsList()
        }

    override suspend fun saveSelfEmbedding(embedding: SpeakerEmbedding, sampleCount: Int, nowMillis: Long): Speaker =
        withContext(ioContext) {
            database.transactionWithResult {
                val self = queries.findSelf(::toSpeaker).executeAsOneOrNull()
                    ?: insertSpeaker(SELF_NAME, SpeakerNameSource.SELF, isSelf = true, nowMillis)
                save(self.id, embedding, sampleCount, nowMillis, voicedMillis = 0)
                queries.deleteAdaptations()
                self
            }
        }

    override suspend fun createProvisionalSpeaker(
        embedding: SpeakerEmbedding,
        nowMillis: Long,
        voicedMillis: Long,
    ): Speaker = withContext(ioContext) {
        database.transactionWithResult {
            val speaker = insertProvisional(nowMillis)
            save(speaker.id, embedding, sampleCount = 1, nowMillis, voicedMillis)
            speaker
        }
    }

    override suspend fun createProvisionalSpeakerWithoutVoice(nowMillis: Long): Speaker = withContext(ioContext) {
        database.transactionWithResult { insertProvisional(nowMillis) }
    }

    /** 統合で話者が消えるので、使われていない最小の番号を探す。番号が増え続けない。 */
    private fun insertProvisional(nowMillis: Long): Speaker {
        val used = queries.listDisplayNames().executeAsList().toSet()
        val number = generateSequence(1) { it + 1 }.first { "$PROVISIONAL_PREFIX$it" !in used }
        return insertSpeaker("$PROVISIONAL_PREFIX$number", SpeakerNameSource.PROVISIONAL, isSelf = false, nowMillis)
    }

    override suspend fun updateEmbedding(
        speakerId: Long,
        embedding: SpeakerEmbedding,
        sampleCount: Int,
        nowMillis: Long,
        voicedMillis: Long,
    ): Unit = withContext(ioContext) {
        save(speakerId, embedding, sampleCount, nowMillis, voicedMillis)
    }

    override suspend fun mergeSpeakers(
        merges: List<SpeakerMerge>,
        nowMillis: Long,
        renumberProvisional: Boolean,
    ): Unit = withContext(ioContext) {
        database.transaction {
            merges.forEach { apply(it, nowMillis) }
            if (renumberProvisional) renumber()
        }
    }

    private fun apply(merge: SpeakerMerge, nowMillis: Long) {
        val isOther = queries.listAll(::toSpeaker).executeAsList().any { it.id == merge.fromId && !it.isSelf }
        if (!isOther) return
        database.utteranceQueries.moveSpeaker(merge.intoId, merge.fromId)
        queries.deleteEmbeddings(merge.fromId)
        queries.deleteOther(merge.fromId)
        merge.merged?.let { save(merge.intoId, it.embedding, it.sampleCount, nowMillis, it.voicedMillis) }
    }

    private fun renumber() {
        val speakers = queries.listAll(::toSpeaker).executeAsList()
        val (provisional, others) = speakers.partition { it.nameSource == SpeakerNameSource.PROVISIONAL }
        val taken = others.map { it.displayName }.toSet()
        val numbers = generateSequence(1) { it + 1 }.map { "$PROVISIONAL_PREFIX$it" }.filter { it !in taken }
        provisional.zip(numbers.take(provisional.size).toList()).forEach { (speaker, name) ->
            queries.setProvisionalName(name, speaker.id, SpeakerNameSource.PROVISIONAL.toColumn())
        }
    }

    override suspend fun findSelfAdaptation(modelVersion: String): SelfAdaptation? = withContext(ioContext) {
        queries.findAdaptation(modelVersion) { vector, voicedMillis ->
            SelfAdaptation(vector.toFloats(), voicedMillis, modelVersion)
        }.executeAsOneOrNull()
    }

    override suspend fun saveSelfAdaptation(adaptation: SelfAdaptation, nowMillis: Long): Unit =
        withContext(ioContext) {
            queries.saveAdaptation(
                adaptation.modelVersion,
                adaptation.vector.toBytes(),
                adaptation.voicedMillis,
                nowMillis,
            )
        }

    override suspend fun record(samples: List<SpeakerDecisionSample>, nowMillis: Long): Unit =
        withContext(ioContext) {
            val dayMillis = nowMillis.floorToDay()
            database.transaction {
                samples.forEach { sample ->
                    val decision = sample.decision.toColumn()
                    val bucket = scoreBucket(sample.selfScore).toLong()
                    queries.ensureDecisionStat(dayMillis, decision, bucket)
                    queries.addDecisionStat(sample.voicedMillis, dayMillis, decision, bucket)
                }
            }
        }

    override suspend fun summarize(fromMillis: Long): List<SpeakerDecisionBucket> = withContext(ioContext) {
        queries.listDecisionStats(fromMillis.floorToDay()) { decision, bucket, runs, voicedMillis ->
            SpeakerDecisionBucket(decision.toSpeakerDecision(), bucket.toInt(), runs ?: 0, voicedMillis ?: 0)
        }.executeAsList()
    }

    override suspend fun prune(beforeMillis: Long): Unit = withContext(ioContext) {
        queries.deleteDecisionStatsBefore(beforeMillis.floorToDay())
    }

    override suspend fun setManualName(speakerId: Long, name: String): Boolean = withContext(ioContext) {
        database.transactionWithResult {
            queries.setManualName(name, SpeakerNameSource.MANUAL.toColumn(), speakerId)
            queries.changes().executeAsOne() > 0
        }
    }

    override suspend fun setInferredName(speakerId: Long, name: String): Boolean = withContext(ioContext) {
        database.transactionWithResult {
            queries.setInferredName(name, SpeakerNameSource.INFERRED.toColumn(), speakerId, REPLACEABLE_SOURCES)
            queries.changes().executeAsOne() > 0
        }
    }

    private fun insertSpeaker(name: String, source: SpeakerNameSource, isSelf: Boolean, nowMillis: Long): Speaker {
        queries.insert(name, source.toColumn(), if (isSelf) 1 else 0, nowMillis)
        return Speaker(queries.lastInsertedId().executeAsOne(), name, source, isSelf, nowMillis)
    }

    private fun save(
        speakerId: Long,
        embedding: SpeakerEmbedding,
        sampleCount: Int,
        nowMillis: Long,
        voicedMillis: Long,
    ) {
        queries.saveEmbedding(
            speakerId,
            embedding.modelVersion,
            embedding.vector.toBytes(),
            sampleCount.toLong(),
            nowMillis,
            voicedMillis,
        )
    }

    private companion object {
        /** 推定で置き換えてよい名前。自分と手動の名前は含めない。 */
        val REPLACEABLE_SOURCES = listOf(SpeakerNameSource.PROVISIONAL, SpeakerNameSource.INFERRED)
            .map { it.toColumn() }
    }
}

private fun Long.floorToDay(): Long = Math.floorDiv(this, MILLIS_PER_DAY) * MILLIS_PER_DAY

private fun toSpeaker(id: Long, displayName: String, nameSource: String, isSelf: Long, createdAtMillis: Long) =
    Speaker(id, displayName, nameSource.toSpeakerNameSource(), isSelf == 1L, createdAtMillis)

private fun FloatArray.toBytes(): ByteArray {
    val buffer = ByteBuffer.allocate(size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    forEach(buffer::putFloat)
    return buffer.array()
}

private fun ByteArray.toFloats(): FloatArray {
    val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    return FloatArray(buffer.remaining()).also { buffer.get(it) }
}

// enum の名前を変えても保存済みの値が読めるように、列の値は対応表で固定する
private fun SpeakerNameSource.toColumn(): String = when (this) {
    SpeakerNameSource.SELF -> "self"
    SpeakerNameSource.PROVISIONAL -> "provisional"
    SpeakerNameSource.INFERRED -> "inferred"
    SpeakerNameSource.MANUAL -> "manual"
}

private fun SpeakerDecision.toColumn(): String = when (this) {
    SpeakerDecision.SELF -> "self"
    SpeakerDecision.OTHER -> "other"
    SpeakerDecision.UNKNOWN -> "unknown"
}

private fun String.toSpeakerDecision(): SpeakerDecision =
    SpeakerDecision.entries.firstOrNull { it.toColumn() == this } ?: error("未知の判定です: $this")

private fun String.toSpeakerNameSource(): SpeakerNameSource =
    SpeakerNameSource.entries.firstOrNull { it.toColumn() == this } ?: error("未知の名前の由来です: $this")
