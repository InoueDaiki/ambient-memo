package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.Memo
import dev.ambientmemo.engine.domain.MemoRepository
import dev.ambientmemo.engine.domain.NewMemo

/** メモを記録したときに、戻る前に [onChanged] を呼ぶ。画面はメモの一覧を取り直す。 */
internal class ObservableMemoRepository(
    private val delegate: MemoRepository,
    private val onChanged: () -> Unit,
) : MemoRepository by delegate {
    override suspend fun recordMemo(memo: NewMemo): Memo? = delegate.recordMemo(memo)?.also { onChanged() }
}
