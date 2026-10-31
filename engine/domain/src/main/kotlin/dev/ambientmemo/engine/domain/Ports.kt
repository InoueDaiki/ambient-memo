package dev.ambientmemo.engine.domain

import kotlinx.coroutines.flow.Flow

interface Clock {
    fun nowMillis(): Long
}

object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** 16 ビットのモノラル PCM を流す。 */
interface AudioSource {
    val sampleRateHz: Int

    /**
     * collect するたびに読み取りを始める。空のフレームは流さない。
     *
     * 発行したフレームの配列を、collect の処理から戻った後に再利用してよい。
     * そのため `flowOn` や `buffer` を挟まない。処理が遅れたときの欠落は時刻の飛びとして表す。
     */
    fun frames(): Flow<AudioFrame>
}

/** フレームを発話か無音かに分ける。状態を持つ実装を想定し、フレームは時系列で渡す。 */
interface VoiceActivityDetector {
    fun isSpeech(frame: AudioFrame): Boolean

    /** 内部の状態を捨てる。読み取りの開始時と、音声が途切れたときに呼ぶ。 */
    fun reset()
}

/**
 * 発話区間を文字にする。
 *
 * [segment] は呼び出し側が解放するので、実装は解放せず、戻った後に参照を残さない。
 */
interface Transcriber {
    suspend fun transcribe(segment: SpeechSegment): Transcription
}

/** 発話区間から話者の特徴量を作る。[segment] の扱いは [Transcriber] と同じ。 */
interface SpeakerEmbedder {
    suspend fun embed(segment: SpeechSegment): SpeakerEmbedding
}

interface UtteranceRepository {
    suspend fun insert(utterance: NewUtterance): Utterance

    /** `[fromMillis, toMillis)` と重なる発話を、開始時刻、id の順に返す。 */
    suspend fun listOverlapping(fromMillis: Long, toMillis: Long): List<Utterance>

    /** 開始時刻の新しい順に最大 [limit] 件を返す。同時刻は後から保存したものを先にする。 */
    suspend fun listRecent(limit: Int): List<Utterance>
}

interface CaptureGapRepository {
    suspend fun insert(gap: CaptureGap)

    /** `[fromMillis, toMillis)` と重なる欠落を、開始時刻の順に返す。 */
    suspend fun listOverlapping(fromMillis: Long, toMillis: Long): List<CaptureGap>
}
