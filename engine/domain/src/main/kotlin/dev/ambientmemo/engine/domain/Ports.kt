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

/** プロンプトから文章を生成する。出力の形式は保証しない。解析と検証は `domain` で行う。 */
interface Llm {
    /** 出力を作るモデルの版。メモに残し、モデルを変えたときに比べられるようにする。 */
    val modelVersion: String

    /** 実行基盤が動かないときは例外を投げる。入力に問題がないのに出力が空のときは、空文字列を返す。 */
    suspend fun generate(request: LlmRequest): String
}

/** 整理の結果の記録。DB に書くのはエンジンだけなので、書き込みはこの境界に閉じる。 */
interface MemoRepository {
    /** 処理済みの印がない発話を、開始時刻、id の順に返す。 */
    suspend fun listUnorganizedUtterances(): List<Utterance>

    /**
     * メモ、ToDo、根拠の発話の処理済みの印を、1つのトランザクションで記録する。
     * 根拠の発話に、存在しないものか処理済みのものがあれば、何も書かずに null を返す。
     */
    suspend fun recordMemo(memo: NewMemo): Memo?

    /** 発話を、メモを作らずに処理済みにする。処理済みの発話はそのままにする。 */
    suspend fun markOrganized(utteranceIds: List<Long>, organizedAtMillis: Long)

    /** メモを開始時刻、id の順に返す。 */
    suspend fun listMemos(): List<Memo>

    /** [memoId] の根拠の発話の id を、開始時刻、id の順に返す。 */
    suspend fun listUtteranceIds(memoId: Long): List<Long>

    /** [memoId] の ToDo を、記録した順に返す。 */
    suspend fun listTodos(memoId: Long): List<Todo>
}
