package dev.ambientmemo.engine.adapters

import dev.ambientmemo.engine.adapters.db.AmbientMemoDatabase
import dev.ambientmemo.engine.domain.Memo
import dev.ambientmemo.engine.domain.MemoRepository
import dev.ambientmemo.engine.domain.NewMemo
import dev.ambientmemo.engine.domain.Utterance
import dev.ambientmemo.engine.domain.foldAscii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

class SqliteMemoRepository(
    private val database: AmbientMemoDatabase,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : MemoRepository {
    override suspend fun listUnorganizedUtterances(): List<Utterance> = withContext(ioContext) {
        database.utteranceQueries
            .listUnorganized(::toUtterance)
            .executeAsList()
    }

    override suspend fun recordMemo(memo: NewMemo): Memo? = withContext(ioContext) {
        database.transactionWithResult {
            val allUnorganized = memo.utteranceIds.all { id ->
                database.utteranceQueries.countUnorganized(id).executeAsOne() == 1L
            }
            if (!allUnorganized) rollback(null)
            database.memoQueries.insert(
                memo.startedAtMillis,
                memo.endedAtMillis,
                memo.title,
                memo.summary,
                memo.modelVersion,
                memo.createdAtMillis,
            )
            val memoId = database.memoQueries.lastInsertedId().executeAsOne()
            memo.utteranceIds.forEach { utteranceId ->
                database.memoQueries.insertUtterance(memoId, utteranceId)
                database.utteranceQueries.markOrganized(memo.createdAtMillis, utteranceId)
            }
            Memo(
                id = memoId,
                startedAtMillis = memo.startedAtMillis,
                endedAtMillis = memo.endedAtMillis,
                title = memo.title,
                summary = memo.summary,
                modelVersion = memo.modelVersion,
                createdAtMillis = memo.createdAtMillis,
            )
        }
    }

    override suspend fun markOrganized(utteranceIds: List<Long>, organizedAtMillis: Long): Unit =
        withContext(ioContext) {
            database.transaction {
                utteranceIds.forEach { database.utteranceQueries.markOrganized(organizedAtMillis, it) }
            }
        }

    override suspend fun listMemos(): List<Memo> = withContext(ioContext) {
        database.memoQueries
            .listAll(::toMemo)
            .executeAsList()
    }

    override suspend fun listRecentMemos(limit: Int): List<Memo> = withContext(ioContext) {
        database.memoQueries
            .listRecent(limit.toLong(), ::toMemo)
            .executeAsList()
    }

    override suspend fun findMemo(memoId: Long): Memo? = withContext(ioContext) {
        database.memoQueries
            .findById(memoId, ::toMemo)
            .executeAsOneOrNull()
    }

    override suspend fun listMemoUtterances(memoId: Long): List<Utterance> = withContext(ioContext) {
        database.utteranceQueries
            .listByMemo(memoId, ::toUtterance)
            .executeAsList()
    }

    override suspend fun listUtteranceIds(memoId: Long): List<Long> = withContext(ioContext) {
        database.memoQueries.listUtteranceIds(memoId).executeAsList()
    }

    override suspend fun searchMemos(term: String, fromMillis: Long, toMillis: Long, limit: Int): List<Memo> =
        withContext(ioContext) {
            database.memoQueries
                .search(toMillis, fromMillis, foldAscii(term), limit.toLong(), ::toMemo)
                .executeAsList()
        }
}

/** SQLDelight の行の順に受ける。 */
@Suppress("LongParameterList") // 引数は memo の列
private fun toMemo(
    id: Long,
    startedAtMillis: Long,
    endedAtMillis: Long,
    title: String?,
    summary: String?,
    modelVersion: String,
    createdAtMillis: Long,
) = Memo(id, startedAtMillis, endedAtMillis, title, summary, modelVersion, createdAtMillis)
