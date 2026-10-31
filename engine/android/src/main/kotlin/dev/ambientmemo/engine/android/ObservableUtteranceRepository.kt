package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.NewUtterance
import dev.ambientmemo.engine.domain.Utterance
import dev.ambientmemo.engine.domain.UtteranceRepository

/** 保存のたびに [onChanged] を呼ぶ。画面に一覧の取り直しを知らせるため。 */
internal class ObservableUtteranceRepository(
    private val delegate: UtteranceRepository,
    private val onChanged: () -> Unit,
) : UtteranceRepository by delegate {
    override suspend fun insert(utterance: NewUtterance): Utterance =
        delegate.insert(utterance).also { onChanged() }

    override suspend fun insertAll(utterances: List<NewUtterance>): List<Utterance> =
        delegate.insertAll(utterances).also { onChanged() }
}
