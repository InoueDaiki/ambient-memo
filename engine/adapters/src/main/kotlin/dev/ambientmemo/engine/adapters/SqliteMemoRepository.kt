package dev.ambientmemo.engine.adapters

import dev.ambientmemo.engine.adapters.db.AmbientMemoDatabase
import dev.ambientmemo.engine.domain.CalendarDate
import dev.ambientmemo.engine.domain.Memo
import dev.ambientmemo.engine.domain.MemoRepository
import dev.ambientmemo.engine.domain.NewMemo
import dev.ambientmemo.engine.domain.Todo
import dev.ambientmemo.engine.domain.TodoCompletion
import dev.ambientmemo.engine.domain.TodoExtraction
import dev.ambientmemo.engine.domain.TodoKind
import dev.ambientmemo.engine.domain.TodoStatus
import dev.ambientmemo.engine.domain.Utterance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

@Suppress("TooManyFunctions") // MemoRepository の項目を実装している
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

    override suspend fun listTodos(memoId: Long): List<Todo> = withContext(ioContext) {
        database.todoQueries
            .listByMemo(memoId, ::toTodo)
            .executeAsList()
    }

    override suspend fun listTodosByPriority(limit: Int): List<Todo> = withContext(ioContext) {
        database.todoQueries
            .listByPriority(limit.toLong(), ::toTodo)
            .executeAsList()
    }

    override suspend fun completeTodo(todoId: Long, completedAtMillis: Long): TodoCompletion =
        withContext(ioContext) {
            database.transactionWithResult {
                val status = database.todoQueries.statusOf(todoId).executeAsOneOrNull()?.toTodoStatus()
                when (status) {
                    null -> TodoCompletion.NOT_FOUND
                    TodoStatus.DONE -> TodoCompletion.ALREADY_DONE
                    else -> {
                        database.todoQueries.complete(completedAtMillis, todoId)
                        TodoCompletion.COMPLETED
                    }
                }
            }
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
    todoExtraction: String,
    modelVersion: String,
    createdAtMillis: Long,
) = Memo(
    id,
    startedAtMillis,
    endedAtMillis,
    title,
    summary,
    todoExtraction.toTodoExtraction(),
    modelVersion,
    createdAtMillis,
)

/** SQLDelight の行の順に受ける。[completedFromStatus] は取り消しのための値で、domain へは渡さない。 */
@Suppress("LongParameterList", "UnusedParameter") // 引数は todo の列
private fun toTodo(
    id: Long,
    memoId: Long,
    task: String,
    kind: String,
    dueDate: String?,
    status: String,
    createdAtMillis: Long,
    completedAtMillis: Long?,
    completedFromStatus: String?,
) = Todo(
    id,
    memoId,
    task,
    kind.toTodoKind(),
    dueDate?.toCalendarDate(),
    status.toTodoStatus(),
    createdAtMillis,
    completedAtMillis,
)

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
    TodoStatus.DONE -> "done"
}

private fun String.toTodoExtraction(): TodoExtraction =
    TodoExtraction.entries.firstOrNull { it.toColumn() == this } ?: error("未知の抽出の状態です: $this")

private fun String.toTodoKind(): TodoKind =
    TodoKind.entries.firstOrNull { it.toColumn() == this } ?: error("未知の ToDo の種類です: $this")

private fun String.toTodoStatus(): TodoStatus =
    TodoStatus.entries.firstOrNull { it.toColumn() == this } ?: error("未知の ToDo の状態です: $this")

private fun String.toCalendarDate(): CalendarDate = CalendarDate.parse(this) ?: error("読めない日付です: $this")
