package dev.ambientmemo.engine.domain.testing

import dev.ambientmemo.engine.domain.AudioFrame
import dev.ambientmemo.engine.domain.AudioSource
import dev.ambientmemo.engine.domain.CaptureGap
import dev.ambientmemo.engine.domain.CaptureGapRepository
import dev.ambientmemo.engine.domain.Clock
import dev.ambientmemo.engine.domain.NewUtterance
import dev.ambientmemo.engine.domain.SpeakerEmbedder
import dev.ambientmemo.engine.domain.SpeakerEmbedding
import dev.ambientmemo.engine.domain.SpeechSegment
import dev.ambientmemo.engine.domain.Transcriber
import dev.ambientmemo.engine.domain.Transcription
import dev.ambientmemo.engine.domain.Utterance
import dev.ambientmemo.engine.domain.UtteranceRepository
import dev.ambientmemo.engine.domain.VoiceActivityDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
