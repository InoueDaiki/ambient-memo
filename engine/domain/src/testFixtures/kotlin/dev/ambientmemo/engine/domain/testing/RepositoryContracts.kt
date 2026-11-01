@file:Suppress("FunctionNaming", "MagicNumber") // テスト名は日本語の文にする

package dev.ambientmemo.engine.domain.testing

import dev.ambientmemo.engine.domain.CaptureGap
import dev.ambientmemo.engine.domain.CaptureGapReason
import dev.ambientmemo.engine.domain.CaptureGapRepository
import dev.ambientmemo.engine.domain.CalendarDate
import dev.ambientmemo.engine.domain.MemoRepository
import dev.ambientmemo.engine.domain.NewMemo
import dev.ambientmemo.engine.domain.NewTodo
import dev.ambientmemo.engine.domain.NewUtterance
import dev.ambientmemo.engine.domain.MergedEmbedding
import dev.ambientmemo.engine.domain.SelfAdaptation
import dev.ambientmemo.engine.domain.SpeakerDecision
import dev.ambientmemo.engine.domain.SpeakerDecisionBucket
import dev.ambientmemo.engine.domain.SpeakerDecisionLog
import dev.ambientmemo.engine.domain.SpeakerDecisionSample
import dev.ambientmemo.engine.domain.SpeakerEmbedding
import dev.ambientmemo.engine.domain.SpeakerMerge
import dev.ambientmemo.engine.domain.SpeakerNameSource
import dev.ambientmemo.engine.domain.SpeakerRepository
import dev.ambientmemo.engine.domain.TodoCompletion
import dev.ambientmemo.engine.domain.TodoExtraction
import dev.ambientmemo.engine.domain.TodoKind
import dev.ambientmemo.engine.domain.TodoStatus
import dev.ambientmemo.engine.domain.UtteranceRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [UtteranceRepository] の Fake と本物の両方に流す契約テスト。 */
@Suppress("TooManyFunctions") // 契約の項目を 1 つずつテストにしている
abstract class UtteranceRepositoryContract {
    protected abstract fun repository(): UtteranceRepository

    private fun utterance(startedAtMillis: Long, endedAtMillis: Long, text: String = "本文") =
        NewUtterance(startedAtMillis, endedAtMillis, text, confidence = null, createdAtMillis = endedAtMillis)

    @Test
    fun `保存した項目をそのまま読み出せる`() = runTest {
        val repository = repository()
        val new = NewUtterance(1_000, 2_500, "今日は晴れ 🌤 \"引用\" 'です'", 0.75, 3_000)

        val saved = repository.insert(new)

        assertEquals(new.startedAtMillis, saved.startedAtMillis)
        assertEquals(new.endedAtMillis, saved.endedAtMillis)
        assertEquals(new.text, saved.text)
        assertEquals(new.confidence, saved.confidence)
        assertEquals(new.createdAtMillis, saved.createdAtMillis)
        assertEquals(listOf(saved), repository.listOverlapping(0, 10_000))
    }

    @Test
    fun `発話の話者を直すと類似度は消え、ない発話は false を返す`() = runTest {
        val repository = repository()
        val saved = repository.insert(NewUtterance(0, 100, "本文", null, 100, speakerId = 7, speakerConfidence = 0.625))

        assertTrue(repository.setSpeaker(saved.id, 8))
        assertEquals(8L, repository.find(saved.id)?.speakerId)
        assertNull(repository.find(saved.id)?.speakerConfidence)

        assertTrue(repository.setSpeaker(saved.id, null))
        assertNull(repository.find(saved.id)?.speakerId)
        assertFalse(repository.setSpeaker(saved.id + 1, 8))
        assertNull(repository.find(saved.id + 1))
    }

    @Test
    fun `話者ごとの発話の数を返す`() = runTest {
        val repository = repository()
        listOf(7L, 7L, 8L, null).forEach {
            repository.insert(NewUtterance(0, 100, "本文", null, 100, speakerId = it, speakerConfidence = null))
        }

        assertEquals(mapOf(7L to 2, 8L to 1), repository.countBySpeaker())
    }

    @Test
    fun `話者と話者の信頼度を保存して読み出せる`() = runTest {
        val repository = repository()
        val new = NewUtterance(0, 100, "本文", null, 100, speakerId = 7, speakerConfidence = 0.625)

        val saved = repository.insert(new)

        assertEquals(7L, saved.speakerId)
        assertEquals(0.625, saved.speakerConfidence)
        assertEquals(listOf(saved), repository.listRecent(1))
    }

    @Test
    fun `まとめて保存すると渡した順に id が付き、開始時刻の順に読み出せる`() = runTest {
        val repository = repository()

        val saved = repository.insertAll(listOf(utterance(0, 100, "前"), utterance(100, 200, "後")))

        assertEquals(listOf("前", "後"), saved.map { it.text })
        assertTrue(saved[0].id < saved[1].id)
        assertEquals(saved, repository.listOverlapping(0, 1_000))
    }

    @Test
    fun `信頼度がなくても保存できる`() = runTest {
        val repository = repository()

        repository.insert(utterance(0, 100))

        assertEquals(null, repository.listOverlapping(0, 100).single().confidence)
    }

    @Test
    fun `保存のたびに別の id が付く`() = runTest {
        val repository = repository()

        val first = repository.insert(utterance(0, 100))
        val second = repository.insert(utterance(0, 100))

        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `開始時刻の順に返し、同時刻は保存した順に返す`() = runTest {
        val repository = repository()
        repository.insert(utterance(300, 400, "c"))
        repository.insert(utterance(100, 200, "a"))
        repository.insert(utterance(100, 150, "b"))

        val texts = repository.listOverlapping(0, 1_000).map { it.text }

        assertEquals(listOf("a", "b", "c"), texts)
    }

    @Test
    fun `範囲と少しでも重なる発話を返し、端が接するだけのものは返さない`() = runTest {
        val repository = repository()
        repository.insert(utterance(0, 100, "前に接する"))
        repository.insert(utterance(50, 101, "前から重なる"))
        repository.insert(utterance(120, 130, "内側"))
        repository.insert(utterance(199, 300, "後ろへ重なる"))
        repository.insert(utterance(200, 300, "後ろに接する"))
        repository.insert(utterance(0, 1_000, "範囲を含む"))

        val texts = repository.listOverlapping(100, 200).map { it.text }

        assertEquals(listOf("範囲を含む", "前から重なる", "内側", "後ろへ重なる"), texts)
    }

    @Test
    fun `該当がなければ空を返す`() = runTest {
        assertTrue(repository().listOverlapping(0, 1_000).isEmpty())
    }

    @Test
    fun `最近の発話を新しい順に上限まで返し、同時刻は後の保存を先にする`() = runTest {
        val repository = repository()
        repository.insert(utterance(100, 200, "古い"))
        repository.insert(utterance(300, 400, "新しい"))
        repository.insert(utterance(200, 300, "同時刻の先"))
        repository.insert(utterance(200, 300, "同時刻の後"))

        val texts = repository.listRecent(3).map { it.text }

        assertEquals(listOf("新しい", "同時刻の後", "同時刻の先"), texts)
    }

    @Test
    fun `最近の発話がなければ空を返す`() = runTest {
        assertTrue(repository().listRecent(10).isEmpty())
    }
}

/** [CaptureGapRepository] の Fake と本物の両方に流す契約テスト。 */
/** [SpeakerDecisionLog] の Fake と本物の両方に流す契約テスト。 */
abstract class SpeakerDecisionLogContract {
    protected abstract fun log(): SpeakerDecisionLog

    private val day = 86_400_000L

    @Test
    fun `判定と帯ごとに回数と長さを足す`() = runTest {
        val log = log()

        log.record(
            listOf(
                SpeakerDecisionSample(SpeakerDecision.SELF, 0.62f, 1_000),
                SpeakerDecisionSample(SpeakerDecision.SELF, 0.64f, 2_000),
                SpeakerDecisionSample(SpeakerDecision.OTHER, 0.1f, 500),
            ),
            10 * day,
        )
        log.record(listOf(SpeakerDecisionSample(SpeakerDecision.SELF, 0.61f, 3_000)), 11 * day)

        assertEquals(
            listOf(
                SpeakerDecisionBucket(SpeakerDecision.OTHER, 2, 1, 500),
                SpeakerDecisionBucket(SpeakerDecision.SELF, 12, 3, 6_000),
            ),
            log.summarize(10 * day + 5),
        )
    }

    @Test
    fun `範囲より前の日は数えず、消せる`() = runTest {
        val log = log()
        log.record(listOf(SpeakerDecisionSample(SpeakerDecision.UNKNOWN, 0.45f, 1_000)), 3 * day)
        log.record(listOf(SpeakerDecisionSample(SpeakerDecision.UNKNOWN, 0.45f, 2_000)), 9 * day)

        assertEquals(1L, log.summarize(5 * day).single().runs)

        log.prune(5 * day)

        assertEquals(1L, log.summarize(0).single().runs)
    }

    @Test
    fun `範囲の外の類似度も帯に収める`() = runTest {
        val log = log()

        log.record(
            listOf(
                SpeakerDecisionSample(SpeakerDecision.OTHER, -0.3f, 1),
                SpeakerDecisionSample(SpeakerDecision.SELF, 1f, 1),
            ),
            day,
        )

        assertEquals(listOf(0, 19), log.summarize(0).map { it.bucket })
    }
}

/** [SpeakerRepository] の Fake と本物の両方に流す契約テスト。 */
@Suppress("TooManyFunctions") // 契約の項目を 1 つずつテストにしている
abstract class SpeakerRepositoryContract {
    protected abstract fun repository(): SpeakerRepository

    private val model = "model-1"
    private val first = SpeakerEmbedding(floatArrayOf(0.6f, 0.8f), model)
    private val second = SpeakerEmbedding(floatArrayOf(1f, 0f), model)

    @Test
    fun `最初は誰もいない`() = runTest {
        val repository = repository()

        assertTrue(repository.listSpeakers().isEmpty())
        assertTrue(repository.listEmbeddings(model, 10).isEmpty())
    }

    @Test
    fun `自分の特徴量を読み出せる`() = runTest {
        val repository = repository()

        val self = repository.saveSelfEmbedding(first, 3, 100)

        assertTrue(self.isSelf)
        assertEquals("自分", self.displayName)
        assertEquals(SpeakerNameSource.SELF, self.nameSource)
        assertEquals(listOf(self), repository.listSpeakers())
        val stored = repository.listEmbeddings(model, 10).single()
        assertEquals(self.id, stored.speakerId)
        assertTrue(stored.isSelf)
        assertEquals(3, stored.sampleCount)
        assertArrayEquals(first.vector, stored.vector)
    }

    @Test
    fun `登録し直すと自分は増えず、特徴量を置き換える`() = runTest {
        val repository = repository()
        val before = repository.saveSelfEmbedding(first, 3, 100)

        val after = repository.saveSelfEmbedding(second, 4, 200)

        assertEquals(before.id, after.id)
        assertEquals(1, repository.listSpeakers().size)
        assertArrayEquals(second.vector, repository.listEmbeddings(model, 10).single().vector)
    }

    @Test
    fun `育てた声は版ごとに読めて、登録し直すと消える`() = runTest {
        val repository = repository()
        repository.saveSelfEmbedding(first, 3, 100)

        repository.saveSelfAdaptation(SelfAdaptation(second.vector, 5_000, model), 200)

        val saved = checkNotNull(repository.findSelfAdaptation(model))
        assertArrayEquals(second.vector, saved.vector)
        assertEquals(5_000L, saved.voicedMillis)
        assertNull(repository.findSelfAdaptation("model-2"))

        repository.saveSelfEmbedding(second, 3, 300)

        assertNull(repository.findSelfAdaptation(model))
    }

    @Test
    fun `仮の話者の名前は使われていない最小の番号にする`() = runTest {
        val repository = repository()
        val firstSpeaker = repository.createProvisionalSpeaker(first, 100)
        val secondSpeaker = repository.createProvisionalSpeaker(first, 100)
        repository.createProvisionalSpeaker(first, 100)

        repository.mergeSpeakers(listOf(SpeakerMerge(secondSpeaker.id, firstSpeaker.id, null)), 200)

        assertEquals("話者2", repository.createProvisionalSpeaker(first, 300).displayName)
    }

    @Test
    fun `特徴量のない仮の話者を作れる`() = runTest {
        val repository = repository()

        val speaker = repository.createProvisionalSpeakerWithoutVoice(100)

        assertEquals("話者1", speaker.displayName)
        assertTrue(repository.listEmbeddings(model, 10).isEmpty())
    }

    @Test
    fun `統合すると統合元と特徴量が消え、統合先の特徴量が変わる`() = runTest {
        val repository = repository()
        val into = repository.createProvisionalSpeaker(first, 100, 1_000)
        val from = repository.createProvisionalSpeaker(second, 100, 2_000)
        val merged = MergedEmbedding(SpeakerEmbedding(second.vector, model), 2, 3_000)

        repository.mergeSpeakers(listOf(SpeakerMerge(from.id, into.id, merged)), 200)

        assertEquals(listOf(into.id), repository.listSpeakers().map { it.id })
        val stored = repository.listEmbeddings(model, 10).single()
        assertEquals(into.id, stored.speakerId)
        assertEquals(2, stored.sampleCount)
        assertEquals(3_000L, stored.voicedMillis)
        assertEquals(200L, stored.updatedAtMillis)
    }

    @Test
    fun `自分は統合元にならず、いない統合元は飛ばす`() = runTest {
        val repository = repository()
        val self = repository.saveSelfEmbedding(first, 3, 100)
        val other = repository.createProvisionalSpeaker(second, 100)

        repository.mergeSpeakers(
            listOf(SpeakerMerge(self.id, other.id, null), SpeakerMerge(self.id + 99, other.id, null)),
            200,
        )

        assertEquals(2, repository.listSpeakers().size)
    }

    @Test
    fun `振り直すと仮の話者だけを id の順に番号を付ける`() = runTest {
        val repository = repository()
        val speakers = List(4) { repository.createProvisionalSpeaker(first, 100) }
        repository.setManualName(speakers[1].id, "話者1")

        repository.mergeSpeakers(listOf(SpeakerMerge(speakers[2].id, speakers[0].id, null)), 200, true)

        assertEquals(listOf("話者2", "話者1", "話者3"), repository.listSpeakers().map { it.displayName })
    }

    @Test
    fun `特徴量に長さと名前の由来と更新時刻が付く`() = runTest {
        val repository = repository()
        val speaker = repository.createProvisionalSpeaker(first, 100, 2_500)

        val stored = repository.listEmbeddings(model, 10).single()

        assertEquals(speaker.id, stored.speakerId)
        assertEquals(2_500L, stored.voicedMillis)
        assertEquals(SpeakerNameSource.PROVISIONAL, stored.nameSource)
        assertEquals(100L, stored.updatedAtMillis)
    }

    @Test
    fun `仮の話者に順番の名前を付ける`() = runTest {
        val repository = repository()
        repository.saveSelfEmbedding(first, 3, 100)

        val names = List(2) { repository.createProvisionalSpeaker(second, 200L + it) }

        assertEquals(listOf("話者1", "話者2"), names.map { it.displayName })
        assertTrue(names.none { it.isSelf })
        assertEquals(SpeakerNameSource.PROVISIONAL, names.first().nameSource)
    }

    @Test
    fun `別の版の特徴量は返さない`() = runTest {
        val repository = repository()
        repository.saveSelfEmbedding(first, 3, 100)

        assertTrue(repository.listEmbeddings("model-2", 10).isEmpty())
    }

    @Test
    fun `別の版で登録し直しても前の版は残る`() = runTest {
        val repository = repository()
        repository.saveSelfEmbedding(first, 3, 100)

        repository.saveSelfEmbedding(SpeakerEmbedding(second.vector, "model-2"), 3, 200)

        assertArrayEquals(first.vector, repository.listEmbeddings(model, 10).single().vector)
    }

    @Test
    fun `上限があっても自分を残し、更新の新しい話者を優先する`() = runTest {
        val repository = repository()
        val old = repository.createProvisionalSpeaker(first, 100)
        val recent = repository.createProvisionalSpeaker(first, 200)
        val self = repository.saveSelfEmbedding(first, 3, 50)

        repository.updateEmbedding(old.id, second, 2, 300)
        val ids = repository.listEmbeddings(model, 2).map { it.speakerId }
        repository.updateEmbedding(recent.id, second, 2, 400)
        val after = repository.listEmbeddings(model, 2).map { it.speakerId }

        assertEquals(listOf(self.id, old.id), ids)
        assertEquals(listOf(self.id, recent.id), after)
    }

    @Test
    fun `特徴量を更新すると標本の数も変わる`() = runTest {
        val repository = repository()
        val speaker = repository.createProvisionalSpeaker(first, 100)

        repository.updateEmbedding(speaker.id, second, 2, 200)

        val stored = repository.listEmbeddings(model, 10).single()
        assertEquals(2, stored.sampleCount)
        assertArrayEquals(second.vector, stored.vector)
    }

    @Test
    fun `推定の名前は仮の名前と推定の名前を置き換える`() = runTest {
        val repository = repository()
        val speaker = repository.createProvisionalSpeaker(first, 100)

        assertTrue(repository.setInferredName(speaker.id, "田中"))
        assertTrue(repository.setInferredName(speaker.id, "佐藤"))

        val renamed = repository.listSpeakers().single()
        assertEquals("佐藤", renamed.displayName)
        assertEquals(SpeakerNameSource.INFERRED, renamed.nameSource)
    }

    @Test
    fun `推定の名前で手動の名前を上書きしない`() = runTest {
        val repository = repository()
        val speaker = repository.createProvisionalSpeaker(first, 100)
        assertTrue(repository.setManualName(speaker.id, "母"))

        assertFalse(repository.setInferredName(speaker.id, "田中"))

        val kept = repository.listSpeakers().single()
        assertEquals("母", kept.displayName)
        assertEquals(SpeakerNameSource.MANUAL, kept.nameSource)
    }

    @Test
    fun `推定の名前で自分を変えない`() = runTest {
        val repository = repository()
        val self = repository.saveSelfEmbedding(first, 3, 100)

        assertFalse(repository.setInferredName(self.id, "田中"))

        assertEquals("自分", repository.listSpeakers().single().displayName)
    }

    @Test
    fun `手動の名前は推定の名前を置き換え、自分にも付けられる`() = runTest {
        val repository = repository()
        val self = repository.saveSelfEmbedding(first, 3, 100)
        val speaker = repository.createProvisionalSpeaker(first, 100)
        repository.setInferredName(speaker.id, "田中")

        assertTrue(repository.setManualName(speaker.id, "母"))
        assertTrue(repository.setManualName(self.id, "陽菜"))

        assertEquals(listOf("陽菜", "母"), repository.listSpeakers().map { it.displayName })
        assertTrue(repository.listSpeakers().first().isSelf)
    }

    @Test
    fun `いない話者の名前は変えられない`() = runTest {
        val repository = repository()

        assertFalse(repository.setManualName(404, "母"))
        assertFalse(repository.setInferredName(404, "田中"))
    }
}

abstract class CaptureGapRepositoryContract {
    protected abstract fun repository(): CaptureGapRepository

    @Test
    fun `すべての理由を保存して読み出せる`() = runTest {
        val repository = repository()
        val gaps = CaptureGapReason.entries.mapIndexed { index, reason ->
            CaptureGap(index * 100L, index * 100L + 50, reason)
        }

        gaps.forEach { repository.insert(it) }

        assertEquals(gaps, repository.listOverlapping(0, 10_000))
    }

    @Test
    fun `開始時刻の順に返す`() = runTest {
        val repository = repository()
        val later = CaptureGap(300, 400, CaptureGapReason.PROCESSING_FAILED)
        val earlier = CaptureGap(100, 200, CaptureGapReason.PROCESSING_BACKLOG)
        repository.insert(later)
        repository.insert(earlier)

        assertEquals(listOf(earlier, later), repository.listOverlapping(0, 1_000))
    }

    @Test
    fun `範囲と少しでも重なる欠落を返し、端が接するだけのものは返さない`() = runTest {
        val repository = repository()
        val reason = CaptureGapReason.PROCESSING_BACKLOG
        repository.insert(CaptureGap(0, 100, reason))
        val overlapping = CaptureGap(99, 200, reason)
        repository.insert(overlapping)
        repository.insert(CaptureGap(200, 300, reason))

        assertEquals(listOf(overlapping), repository.listOverlapping(100, 200))
    }

    @Test
    fun `同じ内容の欠落を重ねて保存できる`() = runTest {
        val repository = repository()
        val gap = CaptureGap(0, 100, CaptureGapReason.PIPELINE_STOPPED)

        repository.insert(gap)
        repository.insert(gap)

        assertEquals(listOf(gap, gap), repository.listOverlapping(0, 100))
    }
}

/** [MemoRepository] の Fake と本物の両方に流す契約テスト。発話は同じ保存先の [UtteranceRepository] から入れる。 */
@Suppress("TooManyFunctions") // テストの数なので分けない
abstract class MemoRepositoryContract {
    protected class Stores(val utterances: UtteranceRepository, val memos: MemoRepository)

    protected abstract fun stores(): Stores

    private suspend fun Stores.utterance(startedAtMillis: Long) =
        utterances.insert(NewUtterance(startedAtMillis, startedAtMillis + 10, "本文", null, startedAtMillis + 10)).id

    private fun memo(vararg utteranceIds: Long, todos: List<NewTodo> = emptyList()) = NewMemo(
        startedAtMillis = 100,
        endedAtMillis = 200,
        title = "題名",
        summary = "- 要約",
        todoExtraction = TodoExtraction.COMPLETE,
        modelVersion = "fake-1",
        createdAtMillis = 1_000,
        utteranceIds = utteranceIds.toList(),
        todos = todos,
    )

    @Test
    fun `未処理の発話を開始時刻の順に返す`() = runTest {
        val stores = stores()
        val later = stores.utterance(200)
        val earlier = stores.utterance(100)

        assertEquals(listOf(earlier, later), stores.memos.listUnorganizedUtterances().map { it.id })
    }

    @Test
    fun `記録したメモと ToDo と根拠をそのまま読み出せる`() = runTest {
        val stores = stores()
        val first = stores.utterance(100)
        val second = stores.utterance(200)
        val todos = listOf(
            NewTodo("期限のある作業", TodoKind.REMINDER, CalendarDate(2026, 11, 5), TodoStatus.OPEN),
            NewTodo("期限のない作業", TodoKind.TODO, null, TodoStatus.CANDIDATE),
        )

        val saved = stores.memos.recordMemo(memo(second, first, todos = todos))!!

        assertEquals(listOf(saved), stores.memos.listMemos())
        assertEquals(listOf(first, second), stores.memos.listUtteranceIds(saved.id))
        val read = stores.memos.listTodos(saved.id)
        assertEquals(todos.map { it.task }, read.map { it.task })
        assertEquals(todos.map { it.kind }, read.map { it.kind })
        assertEquals(todos.map { it.dueDate }, read.map { it.dueDate })
        assertEquals(todos.map { it.status }, read.map { it.status })
        assertTrue(read.all { it.memoId == saved.id })
    }

    @Test
    fun `要約に失敗したメモも読み出せる`() = runTest {
        val stores = stores()
        val failed = memo(stores.utterance(100)).copy(
            title = null,
            summary = null,
            todoExtraction = TodoExtraction.INCOMPLETE,
        )

        stores.memos.recordMemo(failed)

        val read = stores.memos.listMemos().single()
        assertNull(read.title)
        assertNull(read.summary)
        assertEquals(TodoExtraction.INCOMPLETE, read.todoExtraction)
    }

    @Test
    fun `記録した発話は未処理でなくなる`() = runTest {
        val stores = stores()
        val recorded = stores.utterance(100)
        val rest = stores.utterance(200)

        stores.memos.recordMemo(memo(recorded))

        assertEquals(listOf(rest), stores.memos.listUnorganizedUtterances().map { it.id })
    }

    @Test
    fun `処理済みの発話を含むメモは何も書かない`() = runTest {
        val stores = stores()
        val recorded = stores.utterance(100)
        val rest = stores.utterance(200)
        stores.memos.recordMemo(memo(recorded))

        val result = stores.memos.recordMemo(memo(rest, recorded))

        assertNull(result)
        assertEquals(1, stores.memos.listMemos().size)
        assertEquals(listOf(rest), stores.memos.listUnorganizedUtterances().map { it.id })
    }

    @Test
    fun `存在しない発話を含むメモは何も書かない`() = runTest {
        val stores = stores()
        val utterance = stores.utterance(100)

        assertNull(stores.memos.recordMemo(memo(utterance, utterance + 1)))

        assertTrue(stores.memos.listMemos().isEmpty())
        assertEquals(listOf(utterance), stores.memos.listUnorganizedUtterances().map { it.id })
    }

    @Test
    fun `メモを作らずに処理済みにできて、繰り返しても変わらない`() = runTest {
        val stores = stores()
        val skipped = stores.utterance(100)
        val rest = stores.utterance(200)

        stores.memos.markOrganized(listOf(skipped), 1_000)
        stores.memos.markOrganized(listOf(skipped), 2_000)

        assertEquals(listOf(rest), stores.memos.listUnorganizedUtterances().map { it.id })
        assertTrue(stores.memos.listMemos().isEmpty())
    }

    @Test
    fun `メモを開始時刻の順に返す`() = runTest {
        val stores = stores()
        stores.memos.recordMemo(memo(stores.utterance(300)).copy(startedAtMillis = 300, endedAtMillis = 400))
        stores.memos.recordMemo(memo(stores.utterance(100)).copy(startedAtMillis = 100, endedAtMillis = 200))

        assertEquals(listOf(100L, 300L), stores.memos.listMemos().map { it.startedAtMillis })
    }

    @Test
    fun `他のメモの ToDo と根拠は混ざらない`() = runTest {
        val stores = stores()
        val first = stores.utterance(100)
        val second = stores.utterance(200)
        val todo = NewTodo("作業", TodoKind.TODO, null, TodoStatus.OPEN)
        val memoWithTodo = stores.memos.recordMemo(memo(first, todos = listOf(todo)))!!
        val other = stores.memos.recordMemo(memo(second))!!

        assertTrue(stores.memos.listTodos(other.id).isEmpty())
        assertEquals(listOf(second), stores.memos.listUtteranceIds(other.id))
        assertEquals(1, stores.memos.listTodos(memoWithTodo.id).size)
    }

    private fun startingAt(memo: NewMemo, startedAtMillis: Long) =
        memo.copy(startedAtMillis = startedAtMillis, endedAtMillis = startedAtMillis + 10)

    @Test
    fun `最近のメモは新しい順に limit 件まで返し、同時刻は後から記録したものを先にする`() = runTest {
        val stores = stores()
        val oldest = stores.memos.recordMemo(startingAt(memo(stores.utterance(1)), 100))!!
        val first = stores.memos.recordMemo(startingAt(memo(stores.utterance(2)), 200))!!
        val second = stores.memos.recordMemo(startingAt(memo(stores.utterance(3)), 200))!!

        assertEquals(listOf(second, first), stores.memos.listRecentMemos(2))
        assertEquals(listOf(second, first, oldest), stores.memos.listRecentMemos(10))
    }

    @Test
    fun `メモを id で引ける`() = runTest {
        val stores = stores()
        val saved = stores.memos.recordMemo(memo(stores.utterance(100)))!!

        assertEquals(saved, stores.memos.findMemo(saved.id))
        assertNull(stores.memos.findMemo(saved.id + 1))
    }

    @Test
    fun `メモの根拠の発話を開始時刻の順に返す`() = runTest {
        val stores = stores()
        val later = stores.utterance(200)
        val earlier = stores.utterance(100)
        val other = stores.utterance(300)
        val saved = stores.memos.recordMemo(memo(later, earlier))!!
        stores.memos.recordMemo(memo(other))

        assertEquals(listOf(earlier, later), stores.memos.listMemoUtterances(saved.id).map { it.id })
    }

    @Test
    fun `ToDo は未完了、期限の早い順、新しい順、記録した順に並べ、期限のないものは後ろにする`() = runTest {
        val stores = stores()
        fun todo(task: String, due: CalendarDate?, status: TodoStatus = TodoStatus.OPEN) =
            NewTodo(task, TodoKind.TODO, due, status)
        val soon = CalendarDate(2026, 11, 1)
        val older = memo(
            stores.utterance(1),
            todos = listOf(todo("完了", CalendarDate(2026, 1, 1)), todo("期限なし", null), todo("古い", soon)),
        ).copy(createdAtMillis = 1_000)
        val newer = memo(
            stores.utterance(2),
            todos = listOf(
                todo("新しい1", soon, TodoStatus.CANDIDATE),
                todo("新しい2", soon),
                todo("遅い", CalendarDate(2026, 12, 1)),
            ),
        ).copy(createdAtMillis = 2_000)
        val saved = stores.memos.recordMemo(older)!!
        stores.memos.recordMemo(newer)
        val done = stores.memos.listTodos(saved.id).first { it.task == "完了" }
        stores.memos.completeTodo(done.id, 3_000)

        assertEquals(
            listOf("新しい1", "新しい2", "古い", "遅い", "期限なし", "完了"),
            stores.memos.listTodosByPriority(10).map { it.task },
        )
        assertEquals(2, stores.memos.listTodosByPriority(2).size)
    }

    @Test
    fun `完了すると状態と時刻が残り、他の ToDo は変わらない`() = runTest {
        val stores = stores()
        val todos = listOf(
            NewTodo("完了する", TodoKind.TODO, null, TodoStatus.CANDIDATE),
            NewTodo("そのまま", TodoKind.TODO, null, TodoStatus.OPEN),
        )
        val saved = stores.memos.recordMemo(memo(stores.utterance(100), todos = todos))!!
        val (target, other) = stores.memos.listTodos(saved.id)

        assertEquals(TodoCompletion.COMPLETED, stores.memos.completeTodo(target.id, 5_000))

        val (completed, untouched) = stores.memos.listTodos(saved.id)
        assertEquals(TodoStatus.DONE, completed.status)
        assertEquals(5_000L, completed.completedAtMillis)
        assertEquals(other, untouched)
        assertNull(target.completedAtMillis)
    }

    @Test
    fun `完了済みの ToDo の完了は最初の時刻を変えない`() = runTest {
        val stores = stores()
        val todos = listOf(NewTodo("作業", TodoKind.TODO, null, TodoStatus.OPEN))
        val saved = stores.memos.recordMemo(memo(stores.utterance(100), todos = todos))!!
        val id = stores.memos.listTodos(saved.id).single().id
        stores.memos.completeTodo(id, 5_000)

        assertEquals(TodoCompletion.ALREADY_DONE, stores.memos.completeTodo(id, 6_000))
        assertEquals(5_000L, stores.memos.listTodos(saved.id).single().completedAtMillis)
    }

    @Test
    fun `ない ToDo の完了は NOT_FOUND を返す`() = runTest {
        assertEquals(TodoCompletion.NOT_FOUND, stores().memos.completeTodo(1, 5_000))
    }
}
