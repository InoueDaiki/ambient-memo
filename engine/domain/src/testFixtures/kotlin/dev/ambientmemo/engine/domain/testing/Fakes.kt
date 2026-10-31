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
import dev.ambientmemo.engine.domain.SpeakerEmbedder
import dev.ambientmemo.engine.domain.SpeakerEmbedding
import dev.ambientmemo.engine.domain.SpeechSegment
import dev.ambientmemo.engine.domain.Transcriber
import dev.ambientmemo.engine.domain.Todo
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

/** [respond] の結果を返す。受け取った発話は解放の確認用に [received] に残す。 */
class FakeTranscriber(
    private val respond: suspend (index: Int, segment: SpeechSegment) -> Transcription = { index, _ ->
        Transcription("発話${index + 1}")
    },
) : Transcriber {
    val received: MutableList<SpeechSegment> = CopyOnWriteArrayList()

    override suspend fun transcribe(segment: SpeechSegment): Transcription {
        val index = received.size
        received += segment
        return respond(index, segment)
    }
}

class FakeSpeakerEmbedder(
    private val vector: FloatArray = floatArrayOf(1f, 0f, 0f),
    private val modelVersion: String = "fake-1",
) : SpeakerEmbedder {
    override suspend fun embed(segment: SpeechSegment): SpeakerEmbedding =
        SpeakerEmbedding(vector.copyOf(), modelVersion)
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
        )
        rows += row
        return row
    }

    override suspend fun listOverlapping(fromMillis: Long, toMillis: Long): List<Utterance> = rows
        .filter { it.startedAtMillis < toMillis && it.endedAtMillis > fromMillis }
        .sortedWith(compareBy(Utterance::startedAtMillis, Utterance::id))

    override suspend fun listRecent(limit: Int): List<Utterance> = rows
        .sortedWith(compareByDescending<Utterance> { it.startedAtMillis }.thenByDescending { it.id })
        .take(limit)
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
class InMemoryMemoRepository(
    private val utterances: UtteranceRepository,
) : MemoRepository {
    private class Record(val memo: Memo, val utteranceIds: List<Long>, val todos: List<Todo>)

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
}
