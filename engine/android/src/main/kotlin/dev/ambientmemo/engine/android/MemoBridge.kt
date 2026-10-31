package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.Clock
import dev.ambientmemo.engine.domain.Memo
import dev.ambientmemo.engine.domain.MemoRepository
import dev.ambientmemo.engine.domain.Speaker
import dev.ambientmemo.engine.domain.Todo
import dev.ambientmemo.engine.domain.TodoCompletion
import dev.ambientmemo.engine.domain.TodoExtraction
import dev.ambientmemo.engine.domain.TodoKind
import dev.ambientmemo.engine.domain.TodoStatus
import dev.ambientmemo.engine.domain.Utterance

class TodoNotFoundException : Exception()

/** JS が読む形。engine-bridge はここの結果をそのまま渡す。 */
typealias WireObject = Map<String, Any?>

/**
 * メモと ToDo の JS への境界。形は `modules/engine-bridge/src/memo-client.ts` の Native の型と合わせる。
 *
 * Expo に依存しない部分をここに置き、JVM のテストで本物の SQLite を通す。
 * id は JS では文字列なので、数でない id は「ない」ものとして扱う。
 */
class MemoBridge internal constructor(
    private val memos: MemoRepository,
    private val speakers: suspend () -> Map<Long, Speaker>,
    private val clock: Clock,
) {
    suspend fun listMemos(limit: Int): List<WireObject> = memos.listRecentMemos(limit).map { it.toWire() }

    suspend fun listTodos(limit: Int): List<WireObject> = memos.listTodosByPriority(limit).map { it.toWire() }

    suspend fun getMemo(id: String): WireObject? {
        val memo = id.toLongOrNull()?.let { memos.findMemo(it) } ?: return null
        val speakersById = speakers()
        return memo.toWire() + mapOf(
            "utterances" to memos.listMemoUtterances(memo.id).map { it.toWire(speakersById) },
            "todos" to memos.listTodos(memo.id).map { it.toWire() },
        )
    }

    /** @throws TodoNotFoundException ToDo がないとき。完了済みなら何もせず戻る */
    suspend fun completeTodo(id: String) {
        val todoId = id.toLongOrNull() ?: throw TodoNotFoundException()
        if (memos.completeTodo(todoId, clock.nowMillis()) == TodoCompletion.NOT_FOUND) throw TodoNotFoundException()
    }
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
        "speakerName" to speaker?.displayName,
        "startedAt" to startedAtMillis.toDouble(),
    )
}

private fun Memo.toWire(): WireObject = mapOf(
    "id" to id.toString(),
    "title" to title,
    "summary" to summary,
    "todoExtraction" to when (todoExtraction) {
        TodoExtraction.COMPLETE -> "complete"
        TodoExtraction.INCOMPLETE -> "incomplete"
    },
    "startedAt" to startedAtMillis.toDouble(),
    "endedAt" to endedAtMillis.toDouble(),
)

private fun Todo.toWire(): WireObject = mapOf(
    "id" to id.toString(),
    "memoId" to memoId.toString(),
    "task" to task,
    "kind" to when (kind) {
        TodoKind.TODO -> "todo"
        TodoKind.REMINDER -> "reminder"
    },
    "dueDate" to dueDate?.toString(),
    "status" to when (status) {
        TodoStatus.CANDIDATE -> "candidate"
        TodoStatus.OPEN -> "open"
        TodoStatus.DONE -> "done"
    },
    "createdAt" to createdAtMillis.toDouble(),
)
