package dev.ambientmemo.engine.adapters

import dev.ambientmemo.engine.adapters.db.AmbientMemoDatabase
import dev.ambientmemo.engine.domain.CalendarDate
import dev.ambientmemo.engine.domain.Memo
import dev.ambientmemo.engine.domain.MemoRepository
import dev.ambientmemo.engine.domain.NewMemo
import dev.ambientmemo.engine.domain.Todo
import dev.ambientmemo.engine.domain.TodoExtraction
import dev.ambientmemo.engine.domain.TodoKind
import dev.ambientmemo.engine.domain.TodoStatus
import dev.ambientmemo.engine.domain.Utterance
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
                memo.todoExtraction.toColumn(),
                memo.modelVersion,
                memo.createdAtMillis,
            )
            val memoId = database.memoQueries.lastInsertedId().executeAsOne()
            memo.utteranceIds.forEach { utteranceId ->
                database.memoQueries.insertUtterance(memoId, utteranceId)
                database.utteranceQueries.markOrganized(memo.createdAtMillis, utteranceId)
            }
            memo.todos.forEach { todo ->
                database.todoQueries.insert(
                    memoId,
                    todo.task,
                    todo.kind.toColumn(),
                    todo.dueDate?.toString(),
                    todo.status.toColumn(),
                    memo.createdAtMillis,
                )
            }
            Memo(
                id = memoId,
                startedAtMillis = memo.startedAtMillis,
                endedAtMillis = memo.endedAtMillis,
                title = memo.title,
                summary = memo.summary,
                todoExtraction = memo.todoExtraction,
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
            .listAll { id, startedAt, endedAt, title, summary, todoExtraction, modelVersion, createdAt ->
                Memo(id, startedAt, endedAt, title, summary, todoExtraction.toTodoExtraction(), modelVersion, createdAt)
            }
            .executeAsList()
    }

    override suspend fun listUtteranceIds(memoId: Long): List<Long> = withContext(ioContext) {
        database.memoQueries.listUtteranceIds(memoId).executeAsList()
    }

    override suspend fun listTodos(memoId: Long): List<Todo> = withContext(ioContext) {
        database.todoQueries
            .listByMemo(memoId) { id, ownerId, task, kind, dueDate, status, createdAt ->
                Todo(id, ownerId, task, kind.toTodoKind(), dueDate?.toCalendarDate(), status.toTodoStatus(), createdAt)
            }
            .executeAsList()
    }
}

// enum の名前を変えても保存済みの値が読めるように、列の値は対応表で固定する
private fun TodoExtraction.toColumn(): String = when (this) {
    TodoExtraction.COMPLETE -> "complete"
    TodoExtraction.INCOMPLETE -> "incomplete"
}

private fun TodoKind.toColumn(): String = when (this) {
    TodoKind.TODO -> "todo"
    TodoKind.REMINDER -> "reminder"
}

private fun TodoStatus.toColumn(): String = when (this) {
    TodoStatus.CANDIDATE -> "candidate"
    TodoStatus.OPEN -> "open"
}

private fun String.toTodoExtraction(): TodoExtraction =
    TodoExtraction.entries.firstOrNull { it.toColumn() == this } ?: error("未知の抽出の状態です: $this")

private fun String.toTodoKind(): TodoKind =
    TodoKind.entries.firstOrNull { it.toColumn() == this } ?: error("未知の ToDo の種類です: $this")

private fun String.toTodoStatus(): TodoStatus =
    TodoStatus.entries.firstOrNull { it.toColumn() == this } ?: error("未知の ToDo の状態です: $this")

private fun String.toCalendarDate(): CalendarDate = CalendarDate.parse(this) ?: error("読めない日付です: $this")
