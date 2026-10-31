package dev.ambientmemo.engine.adapters

import dev.ambientmemo.engine.adapters.db.AmbientMemoDatabase
import dev.ambientmemo.engine.domain.Speaker
import dev.ambientmemo.engine.domain.SpeakerEmbedding
import dev.ambientmemo.engine.domain.SpeakerNameSource
import dev.ambientmemo.engine.domain.SpeakerRepository
import dev.ambientmemo.engine.domain.StoredSpeakerEmbedding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext

private const val SELF_NAME = "自分"
private const val PROVISIONAL_PREFIX = "話者"

class SqliteSpeakerRepository(
    private val database: AmbientMemoDatabase,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : SpeakerRepository {
    private val queries = database.speakerQueries

    override suspend fun listSpeakers(): List<Speaker> = withContext(ioContext) {
        queries.listAll(::toSpeaker).executeAsList()
    }

    override suspend fun listEmbeddings(modelVersion: String, limit: Int): List<StoredSpeakerEmbedding> =
        withContext(ioContext) {
            queries.listEmbeddings(modelVersion, limit.toLong()) { speakerId, isSelf, vector, sampleCount ->
                StoredSpeakerEmbedding(speakerId, isSelf == 1L, vector.toFloats(), sampleCount.toInt())
            }.executeAsList()
        }

    override suspend fun saveSelfEmbedding(embedding: SpeakerEmbedding, sampleCount: Int, nowMillis: Long): Speaker =
        withContext(ioContext) {
            database.transactionWithResult {
                val self = queries.findSelf(::toSpeaker).executeAsOneOrNull()
                    ?: insertSpeaker(SELF_NAME, SpeakerNameSource.SELF, isSelf = true, nowMillis)
                save(self.id, embedding, sampleCount, nowMillis)
                self
            }
        }

    override suspend fun createProvisionalSpeaker(embedding: SpeakerEmbedding, nowMillis: Long): Speaker =
        withContext(ioContext) {
            database.transactionWithResult {
                // 話者は消さないので、番号は重ならない
                val number = queries.countOthers().executeAsOne() + 1
                val speaker = insertSpeaker(
                    "$PROVISIONAL_PREFIX$number",
                    SpeakerNameSource.PROVISIONAL,
                    isSelf = false,
                    nowMillis,
                )
                save(speaker.id, embedding, sampleCount = 1, nowMillis)
                speaker
            }
        }

    override suspend fun updateEmbedding(
        speakerId: Long,
        embedding: SpeakerEmbedding,
        sampleCount: Int,
        nowMillis: Long,
    ): Unit = withContext(ioContext) {
        save(speakerId, embedding, sampleCount, nowMillis)
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

    private fun save(speakerId: Long, embedding: SpeakerEmbedding, sampleCount: Int, nowMillis: Long) {
        queries.saveEmbedding(
            speakerId,
            embedding.modelVersion,
            embedding.vector.toBytes(),
            sampleCount.toLong(),
            nowMillis,
        )
    }

    private companion object {
        /** 推定で置き換えてよい名前。自分と手動の名前は含めない。 */
        val REPLACEABLE_SOURCES = listOf(SpeakerNameSource.PROVISIONAL, SpeakerNameSource.INFERRED)
            .map { it.toColumn() }
    }
}

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

private fun String.toSpeakerNameSource(): SpeakerNameSource =
    SpeakerNameSource.entries.firstOrNull { it.toColumn() == this } ?: error("未知の名前の由来です: $this")
