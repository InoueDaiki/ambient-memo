package dev.ambientmemo.engine.domain.testing

import dev.ambientmemo.engine.domain.AudioFrame
import dev.ambientmemo.engine.domain.AudioSource
import dev.ambientmemo.engine.domain.CaptureGap
import dev.ambientmemo.engine.domain.CaptureGapRepository
import dev.ambientmemo.engine.domain.Clock
import dev.ambientmemo.engine.domain.Llm
import dev.ambientmemo.engine.domain.LlmRequest
import dev.ambientmemo.engine.domain.Memo
import dev.ambientmemo.engine.domain.MemoRepository
import dev.ambientmemo.engine.domain.NewMemo
import dev.ambientmemo.engine.domain.NewUtterance
import dev.ambientmemo.engine.domain.SelfAdaptation
import dev.ambientmemo.engine.domain.Speaker
import dev.ambientmemo.engine.domain.SpeakerDecision
import dev.ambientmemo.engine.domain.SpeakerDecisionBucket
import dev.ambientmemo.engine.domain.SpeakerDecisionLog
import dev.ambientmemo.engine.domain.SpeakerDecisionSample
import dev.ambientmemo.engine.domain.SpeakerEmbedder
import dev.ambientmemo.engine.domain.SpeakerEmbedding
import dev.ambientmemo.engine.domain.SpeakerMerge
import dev.ambientmemo.engine.domain.SpeakerNameSource
import dev.ambientmemo.engine.domain.SpeakerRepository
import dev.ambientmemo.engine.domain.scoreBucket
import dev.ambientmemo.engine.domain.SpeechSegment
import dev.ambientmemo.engine.domain.StoredSpeakerEmbedding
import dev.ambientmemo.engine.domain.TimedTranscription
import dev.ambientmemo.engine.domain.Transcriber
import dev.ambientmemo.engine.domain.Todo
import dev.ambientmemo.engine.domain.TodoCompletion
import dev.ambientmemo.engine.domain.TodoStatus
import dev.ambientmemo.engine.domain.TranscriptToken
import dev.ambientmemo.engine.domain.Transcription
import dev.ambientmemo.engine.domain.Utterance
import dev.ambientmemo.engine.domain.UtteranceRepository
import dev.ambientmemo.engine.domain.VoiceActivityDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

const val TEST_SAMPLE_RATE_HZ = 16_000
const val TEST_FRAME_SAMPLES = 160
private const val MILLIS_PER_SECOND = 1_000L

class FakeClock(startMillis: Long = 0L) : Clock {
    private val now = AtomicLong(startMillis)

    override fun nowMillis(): Long = now.get()

    fun advanceBy(millis: Long) {
        now.addAndGet(millis)
    }
}

/**
 * 渡したサンプルを流す。本物と同じ条件になるように、配列は1つを再利用する。
 * フレームの開始時刻を変えたいときは [frameStartMillis] で指定する。
 */
class FakeAudioSource(
    private val samples: ShortArray,
    override val sampleRateHz: Int = TEST_SAMPLE_RATE_HZ,
    private val frameSamples: Int = TEST_FRAME_SAMPLES,
    private val startMillis: Long = 0L,
    private val frameStartMillis: (position: Int, defaultMillis: Long) -> Long = { _, default -> default },
) : AudioSource {
    override fun frames(): Flow<AudioFrame> = flow {
        val buffer = ShortArray(frameSamples)
        var position = 0
        while (position < samples.size) {
            val count = minOf(frameSamples, samples.size - position)
            samples.copyInto(buffer, 0, position, position + count)
            val defaultMillis = startMillis + position * MILLIS_PER_SECOND / sampleRateHz
            val frameSamples = if (count == buffer.size) buffer else buffer.copyOf(count)
            emit(AudioFrame(frameStartMillis(position, defaultMillis), frameSamples))
            position += count
        }
    }
}

/** 指定した時間帯だけ発話と判定する。フレームの中央の時刻で決める。 */
class ScriptedVoiceActivityDetector(
    private val speech: List<LongRange>,
    private val sampleRateHz: Int = TEST_SAMPLE_RATE_HZ,
) : VoiceActivityDetector {
    private val resets = AtomicInteger()

    val resetCount: Int
        get() = resets.get()

    override fun isSpeech(frame: AudioFrame): Boolean {
        val middleMillis = frame.startedAtMillis + frame.samples.size * MILLIS_PER_SECOND / sampleRateHz / 2
        return speech.any { middleMillis in it }
    }

    override fun reset() {
        resets.incrementAndGet()
    }
}

/**
 * [respond] の結果を返す。受け取った発話は解放の確認用に [received] に残す。
 * トークンは [tokens] で指定する。既定は本文の文字を区間に均等に並べる。
 */
class FakeTranscriber(
    private val tokens: (text: String, durationMillis: Long) -> List<TranscriptToken> = ::evenTokens,
    private val respond: suspend (index: Int, segment: SpeechSegment) -> Transcription = { index, _ ->
        Transcription("発話${index + 1}")
    },
) : Transcriber {
    val received: MutableList<SpeechSegment> = CopyOnWriteArrayList()

    override suspend fun transcribe(segment: SpeechSegment): TimedTranscription {
        val index = received.size
        received += segment
        val transcription = respond(index, segment)
        val durationMillis = segment.endedAtMillis - segment.startedAtMillis
        return TimedTranscription(transcription, tokens(transcription.text, durationMillis))
    }
}

private fun evenTokens(text: String, durationMillis: Long): List<TranscriptToken> =
    text.mapIndexed { index, character ->
        TranscriptToken(character.toString(), index * durationMillis / text.length)
    }

/** [respond] の特徴量を返す。null は短くて作れない音声。受け取った発話は解放の確認用に [received] に残す。 */
class FakeSpeakerEmbedder(
    override val modelVersion: String = "fake-1",
    private val respond: suspend (index: Int, segment: SpeechSegment) -> FloatArray? = { _, _ ->
        floatArrayOf(1f, 0f, 0f)
    },
) : SpeakerEmbedder {
    val received: MutableList<SpeechSegment> = CopyOnWriteArrayList()

    override suspend fun embed(segment: SpeechSegment): SpeakerEmbedding? {
        val index = received.size
        received += segment
        return respond(index, segment)?.let { SpeakerEmbedding(it, modelVersion) }
    }
}

class InMemoryUtteranceRepository : UtteranceRepository {
    private val rows = CopyOnWriteArrayList<Utterance>()
    private val nextId = AtomicLong(1)

    override suspend fun insert(utterance: NewUtterance): Utterance {
        val row = Utterance(
            id = nextId.getAndIncrement(),
            startedAtMillis = utterance.startedAtMillis,
            endedAtMillis = utterance.endedAtMillis,
            text = utterance.text,
            confidence = utterance.confidence,
            createdAtMillis = utterance.createdAtMillis,
            speakerId = utterance.speakerId,
            speakerConfidence = utterance.speakerConfidence,
        )
        rows += row
        return row
    }

    override suspend fun insertAll(utterances: List<NewUtterance>): List<Utterance> = utterances.map { insert(it) }

    override suspend fun listOverlapping(fromMillis: Long, toMillis: Long): List<Utterance> = rows
        .filter { it.startedAtMillis < toMillis && it.endedAtMillis > fromMillis }
        .sortedWith(compareBy(Utterance::startedAtMillis, Utterance::id))

    override suspend fun listRecent(limit: Int): List<Utterance> = rows
        .sortedWith(compareByDescending<Utterance> { it.startedAtMillis }.thenByDescending { it.id })
        .take(limit)

    override suspend fun find(id: Long): Utterance? = rows.firstOrNull { it.id == id }

    override suspend fun setSpeaker(id: Long, speakerId: Long?): Boolean {
        val exists = rows.any { it.id == id }
        rows.replaceAll { if (it.id == id) it.copy(speakerId = speakerId, speakerConfidence = null) else it }
        return exists
    }

    override suspend fun countBySpeaker(): Map<Long, Int> =
        rows.mapNotNull { it.speakerId }.groupingBy { it }.eachCount()

    /** 話者の統合で発話を付け替える。本物は 1 つのトランザクションで行う。 */
    fun moveSpeaker(fromId: Long, intoId: Long) {
        rows.replaceAll {
            if (it.speakerId == fromId) it.copy(speakerId = intoId, speakerConfidence = null) else it
        }
    }
}

class InMemorySpeakerDecisionLog : SpeakerDecisionLog {
    private class Key(val day: Long, val decision: SpeakerDecision, val bucket: Int)

    private val lock = Mutex()
    private val rows = mutableListOf<Pair<Key, SpeakerDecisionSample>>()

    override suspend fun record(samples: List<SpeakerDecisionSample>, nowMillis: Long) = lock.withLock {
        samples.forEach { rows += Key(day(nowMillis), it.decision, scoreBucket(it.selfScore)) to it }
    }

    override suspend fun summarize(fromMillis: Long): List<SpeakerDecisionBucket> = lock.withLock {
        rows.filter { (key, _) -> key.day >= day(fromMillis) }
            .groupBy { (key, _) -> key.decision to key.bucket }
            .map { (group, items) ->
                SpeakerDecisionBucket(
                    group.first,
                    group.second,
                    items.size.toLong(),
                    items.sumOf { (_, sample) -> sample.voicedMillis },
                )
            }
            .sortedWith(compareBy({ it.decision.name.lowercase() }, { it.bucket }))
    }

    override suspend fun prune(beforeMillis: Long) {
        lock.withLock { rows.removeAll { (key, _) -> key.day < day(beforeMillis) } }
    }

    private fun day(millis: Long) = Math.floorDiv(millis, MILLIS_PER_DAY)

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}

@Suppress("TooManyFunctions") // 本物と同じ窓口を持つ
class InMemorySpeakerRepository(
    private val utterances: InMemoryUtteranceRepository = InMemoryUtteranceRepository(),
) : SpeakerRepository {
    private class Saved(
        val embedding: SpeakerEmbedding,
        val sampleCount: Int,
        val updatedAtMillis: Long,
        val voicedMillis: Long,
    )

    private val lock = Mutex()
    private val speakers = mutableListOf<Speaker>()
    private val embeddings = mutableMapOf<Pair<Long, String>, Saved>()
    private val adaptations = mutableMapOf<String, SelfAdaptation>()
    private var nextId = 1L

    private fun add(name: String, source: SpeakerNameSource, isSelf: Boolean, nowMillis: Long): Speaker {
        val speaker = Speaker(nextId++, name, source, isSelf, nowMillis)
        speakers += speaker
        return speaker
    }

    private fun addProvisional(nowMillis: Long): Speaker {
        val used = speakers.map { it.displayName }.toSet()
        val number = generateSequence(1) { it + 1 }.first { "話者$it" !in used }
        return add("話者$number", SpeakerNameSource.PROVISIONAL, false, nowMillis)
    }

    private fun save(
        speakerId: Long,
        embedding: SpeakerEmbedding,
        sampleCount: Int,
        nowMillis: Long,
        voicedMillis: Long,
    ) {
        embeddings[speakerId to embedding.modelVersion] = Saved(embedding, sampleCount, nowMillis, voicedMillis)
    }

    private fun rename(speakerId: Long, name: String, source: SpeakerNameSource) {
        speakers.replaceAll { if (it.id == speakerId) it.copy(displayName = name, nameSource = source) else it }
    }

    override suspend fun listSpeakers(): List<Speaker> = lock.withLock { speakers.toList() }

    override suspend fun listEmbeddings(modelVersion: String, limit: Int): List<StoredSpeakerEmbedding> =
        lock.withLock {
            val isSelf = speakers.filter { it.isSelf }.map { it.id }.toSet()
            embeddings.entries
                .filter { (key, _) -> key.second == modelVersion }
                .sortedWith(
                    compareByDescending<Map.Entry<Pair<Long, String>, Saved>> { it.key.first in isSelf }
                        .thenByDescending { it.value.updatedAtMillis }
                        .thenByDescending { it.key.first },
                )
                .take(limit)
                .map { (key, saved) ->
                    StoredSpeakerEmbedding(
                        key.first,
                        key.first in isSelf,
                        saved.embedding.vector.copyOf(),
                        saved.sampleCount,
                        saved.voicedMillis,
                        speakers.first { it.id == key.first }.nameSource,
                        saved.updatedAtMillis,
                    )
                }
        }

    override suspend fun saveSelfEmbedding(embedding: SpeakerEmbedding, sampleCount: Int, nowMillis: Long): Speaker =
        lock.withLock {
            val self = speakers.firstOrNull { it.isSelf } ?: add("自分", SpeakerNameSource.SELF, true, nowMillis)
            save(self.id, embedding, sampleCount, nowMillis, 0)
            adaptations.clear()
            self
        }

    override suspend fun createProvisionalSpeaker(
        embedding: SpeakerEmbedding,
        nowMillis: Long,
        voicedMillis: Long,
    ): Speaker = lock.withLock {
        val speaker = addProvisional(nowMillis)
        save(speaker.id, embedding, 1, nowMillis, voicedMillis)
        speaker
    }

    override suspend fun createProvisionalSpeakerWithoutVoice(nowMillis: Long): Speaker =
        lock.withLock { addProvisional(nowMillis) }

    override suspend fun updateEmbedding(
        speakerId: Long,
        embedding: SpeakerEmbedding,
        sampleCount: Int,
        nowMillis: Long,
        voicedMillis: Long,
    ) {
        lock.withLock { save(speakerId, embedding, sampleCount, nowMillis, voicedMillis) }
    }

    override suspend fun mergeSpeakers(merges: List<SpeakerMerge>, nowMillis: Long, renumberProvisional: Boolean) {
        lock.withLock {
            merges.filter { merge -> speakers.any { it.id == merge.fromId && !it.isSelf } }.forEach { merge ->
                utterances.moveSpeaker(merge.fromId, merge.intoId)
                embeddings.keys.removeAll { it.first == merge.fromId }
                speakers.removeAll { it.id == merge.fromId }
                merge.merged?.let {
                    save(merge.intoId, it.embedding, it.sampleCount, nowMillis, it.voicedMillis)
                }
            }
            if (renumberProvisional) renumber()
        }
    }

    private fun renumber() {
        val taken = speakers.filter { it.nameSource != SpeakerNameSource.PROVISIONAL }.map { it.displayName }.toSet()
        val names = generateSequence(1) { it + 1 }.map { "話者$it" }.filter { it !in taken }.iterator()
        speakers.replaceAll {
            if (it.nameSource == SpeakerNameSource.PROVISIONAL) it.copy(displayName = names.next()) else it
        }
    }

    override suspend fun findSelfAdaptation(modelVersion: String): SelfAdaptation? =
        lock.withLock { adaptations[modelVersion] }

    override suspend fun saveSelfAdaptation(adaptation: SelfAdaptation, nowMillis: Long) {
        lock.withLock { adaptations[adaptation.modelVersion] = adaptation }
    }

    override suspend fun setManualName(speakerId: Long, name: String): Boolean = lock.withLock {
        val exists = speakers.any { it.id == speakerId }
        if (exists) rename(speakerId, name, SpeakerNameSource.MANUAL)
        exists
    }

    override suspend fun setInferredName(speakerId: Long, name: String): Boolean = lock.withLock {
        val speaker = speakers.firstOrNull { it.id == speakerId }
        val isReplaceable = speaker != null &&
            !speaker.isSelf &&
            speaker.nameSource in setOf(SpeakerNameSource.PROVISIONAL, SpeakerNameSource.INFERRED)
        if (isReplaceable) rename(speakerId, name, SpeakerNameSource.INFERRED)
        isReplaceable
    }
}

class InMemoryCaptureGapRepository : CaptureGapRepository {
    private val rows = CopyOnWriteArrayList<CaptureGap>()

    override suspend fun insert(gap: CaptureGap) {
        rows += gap
    }

    override suspend fun listOverlapping(fromMillis: Long, toMillis: Long): List<CaptureGap> = rows
        .filter { it.startedAtMillis < toMillis && it.endedAtMillis > fromMillis }
        .sortedBy(CaptureGap::startedAtMillis)
}

/** [respond] の結果を返す。受け取った依頼は [received] に残す。 */
class FakeLlm(
    override val modelVersion: String = "fake-1",
    private val respond: suspend (index: Int, request: LlmRequest) -> String = { _, _ -> "" },
) : Llm {
    val received: MutableList<LlmRequest> = CopyOnWriteArrayList()

    override suspend fun generate(request: LlmRequest): String {
        val index = received.size
        received += request
        return respond(index, request)
    }

    companion object {
        /** 要約と ToDo の依頼を見分けて、固定の出力を返す。 */
        fun organizing(summary: String = "- 要約", todos: String = """{"items": []}""") = FakeLlm { _, request ->
            if (request.isTodoRequest) todos else summary
        }
    }
}

val LlmRequest.isTodoRequest: Boolean
    get() = "JSON だけを出力" in user

/** [utterances] の発話を整理の対象として読む。本物と同じ 1 件ずつの記録だけを行う。 */
@Suppress("TooManyFunctions") // MemoRepository の項目を実装している
class InMemoryMemoRepository(
    private val utterances: UtteranceRepository,
) : MemoRepository {
    private class Record(val memo: Memo, val utteranceIds: List<Long>, var todos: List<Todo>)

    private val lock = Mutex()
    private val records = mutableListOf<Record>()
    private val organized = mutableSetOf<Long>()
    private val nextMemoId = AtomicLong(1)
    private val nextTodoId = AtomicLong(1)

    private suspend fun all() = utterances.listOverlapping(Long.MIN_VALUE, Long.MAX_VALUE)

    override suspend fun listUnorganizedUtterances(): List<Utterance> = lock.withLock {
        all().filterNot { it.id in organized }
    }

    override suspend fun recordMemo(memo: NewMemo): Memo? = lock.withLock {
        val unorganized = all().map { it.id }.toSet() - organized
        if (!unorganized.containsAll(memo.utteranceIds)) return@withLock null
        val saved = Memo(
            id = nextMemoId.getAndIncrement(),
            startedAtMillis = memo.startedAtMillis,
            endedAtMillis = memo.endedAtMillis,
            title = memo.title,
            summary = memo.summary,
            todoExtraction = memo.todoExtraction,
            modelVersion = memo.modelVersion,
            createdAtMillis = memo.createdAtMillis,
        )
        val todos = memo.todos.map {
            Todo(nextTodoId.getAndIncrement(), saved.id, it.task, it.kind, it.dueDate, it.status, memo.createdAtMillis)
        }
        records += Record(saved, memo.utteranceIds, todos)
        organized += memo.utteranceIds
        saved
    }

    override suspend fun markOrganized(utteranceIds: List<Long>, organizedAtMillis: Long) {
        lock.withLock { organized += utteranceIds }
    }

    override suspend fun listMemos(): List<Memo> = lock.withLock {
        records.map { it.memo }.sortedWith(compareBy(Memo::startedAtMillis, Memo::id))
    }

    override suspend fun listUtteranceIds(memoId: Long): List<Long> = lock.withLock {
        val ids = records.firstOrNull { it.memo.id == memoId }?.utteranceIds.orEmpty().toSet()
        all().filter { it.id in ids }.map { it.id }
    }

    override suspend fun listTodos(memoId: Long): List<Todo> = lock.withLock {
        records.firstOrNull { it.memo.id == memoId }?.todos.orEmpty()
    }

    override suspend fun listRecentMemos(limit: Int): List<Memo> = lock.withLock {
        records.map { it.memo }
            .sortedWith(compareByDescending(Memo::startedAtMillis).thenByDescending(Memo::id))
            .take(limit)
    }

    override suspend fun findMemo(memoId: Long): Memo? = lock.withLock {
        records.firstOrNull { it.memo.id == memoId }?.memo
    }

    override suspend fun listMemoUtterances(memoId: Long): List<Utterance> = lock.withLock {
        val ids = records.firstOrNull { it.memo.id == memoId }?.utteranceIds.orEmpty().toSet()
        all().filter { it.id in ids }
    }

    override suspend fun listTodosByPriority(limit: Int): List<Todo> = lock.withLock {
        records.flatMap { it.todos }
            .sortedWith(
                compareBy<Todo> { it.status == TodoStatus.DONE }
                    .thenBy { it.dueDate == null }
                    .thenBy { it.dueDate?.toString() }
                    .thenByDescending { it.createdAtMillis }
                    .thenBy { it.id },
            )
            .take(limit)
    }

    override suspend fun completeTodo(todoId: Long, completedAtMillis: Long): TodoCompletion = lock.withLock {
        val record = records.firstOrNull { record -> record.todos.any { it.id == todoId } }
            ?: return@withLock TodoCompletion.NOT_FOUND
        if (record.todos.first { it.id == todoId }.status == TodoStatus.DONE) {
            return@withLock TodoCompletion.ALREADY_DONE
        }
        record.todos = record.todos.map {
            if (it.id == todoId) it.copy(status = TodoStatus.DONE, completedAtMillis = completedAtMillis) else it
        }
        TodoCompletion.COMPLETED
    }
}
