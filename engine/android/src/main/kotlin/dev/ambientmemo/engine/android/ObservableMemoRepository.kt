package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.Memo
import dev.ambientmemo.engine.domain.MemoRepository
import dev.ambientmemo.engine.domain.NewMemo
import dev.ambientmemo.engine.domain.TodoCompletion

/** 画面が取り直す対象。 */
enum class MemoChange { MEMOS, TODOS }

/** 書き込みが変更を生んだときに、戻る前に [onChanged] を呼ぶ。 */
internal class ObservableMemoRepository(
    private val delegate: MemoRepository,
    private val onChanged: (MemoChange) -> Unit,
) : MemoRepository by delegate {
    override suspend fun recordMemo(memo: NewMemo): Memo? =
        delegate.recordMemo(memo)?.also {
            onChanged(MemoChange.MEMOS)
            if (memo.todos.isNotEmpty()) onChanged(MemoChange.TODOS)
        }

    override suspend fun completeTodo(todoId: Long, completedAtMillis: Long): TodoCompletion =
        delegate.completeTodo(todoId, completedAtMillis).also {
            if (it == TodoCompletion.COMPLETED) onChanged(MemoChange.TODOS)
        }
}
