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
    suspend fun transcribe(segment: SpeechSegment): TimedTranscription
}

/** 発話区間から話者の特徴量を作る。[segment] の扱いは [Transcriber] と同じ。 */
interface SpeakerEmbedder {
    /** 作る特徴量のモデルの版。違う版の特徴量は比べられない。 */
    val modelVersion: String

    /** 音声が短すぎて特徴量を作れないときは null を返す。 */
    suspend fun embed(segment: SpeechSegment): SpeakerEmbedding?
}

interface UtteranceRepository {
    suspend fun insert(utterance: NewUtterance): Utterance

    /** 1 つの発話区間から分けた行を、渡した順に、すべて保存するか何も保存しないかのどちらかで記録する。 */
    suspend fun insertAll(utterances: List<NewUtterance>): List<Utterance>

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

/** 話者と特徴量の記録。特徴量は端末の中だけで使い、この境界の外へ渡さない。 */
interface SpeakerRepository {
    /** 話者を id の順に返す。 */
    suspend fun listSpeakers(): List<Speaker>

    /**
     * [modelVersion] の特徴量を、自分、更新の新しい順に最大 [limit] 件返す。
     * 違う版の特徴量は比べられないので返さない。
     */
    suspend fun listEmbeddings(modelVersion: String, limit: Int): List<StoredSpeakerEmbedding>

    /** 自分の特徴量を置き換える。自分の話者がなければ作る。過去の発話の話者は変えない。 */
    suspend fun saveSelfEmbedding(embedding: SpeakerEmbedding, sampleCount: Int, nowMillis: Long): Speaker

    /** 仮の話者を「話者N」の名前で作り、最初の特徴量を記録する。 */
    suspend fun createProvisionalSpeaker(embedding: SpeakerEmbedding, nowMillis: Long): Speaker

    /** 既存の話者の特徴量を置き換える。 */
    suspend fun updateEmbedding(speakerId: Long, embedding: SpeakerEmbedding, sampleCount: Int, nowMillis: Long)

    /** 手で付けた名前を記録する。推定で上書きされなくなる。話者がいなければ false を返す。 */
    suspend fun setManualName(speakerId: Long, name: String): Boolean

    /** 推定した名前を記録する。自分と、手で付けた名前は変えずに false を返す。 */
    suspend fun setInferredName(speakerId: Long, name: String): Boolean
}

/** プロンプトから文章を生成する。出力の形式は保証しない。解析と検証は `domain` で行う。 */
interface Llm {
    /** 出力を作るモデルの版。メモに残し、モデルを変えたときに比べられるようにする。 */
    val modelVersion: String

    /** 実行基盤が動かないときは例外を投げる。入力に問題がないのに出力が空のときは、空文字列を返す。 */
    suspend fun generate(request: LlmRequest): String
}

/** 整理の結果の記録。DB に書くのはエンジンだけなので、書き込みはこの境界に閉じる。 */
@Suppress("TooManyFunctions") // メモ、ToDo、根拠の発話を1つのトランザクションで扱うので分けない
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

    /** メモを開始時刻の新しい順に最大 [limit] 件返す。同時刻は後から記録したものを先にする。 */
    suspend fun listRecentMemos(limit: Int): List<Memo>

    /** メモがなければ null を返す。 */
    suspend fun findMemo(memoId: Long): Memo?

    /** [memoId] の根拠の発話を、開始時刻、id の順に返す。 */
    suspend fun listMemoUtterances(memoId: Long): List<Utterance>

    /**
     * 画面に出す順で ToDo を最大 [limit] 件返す。
     * 未完了を先に、期限の早い順。期限がなければ後ろ、同じなら新しい順、それも同じなら記録した順。
     */
    suspend fun listTodosByPriority(limit: Int): List<Todo>

    /** ToDo を完了にする。完了の前の状態は取り消しのために残す。すでに完了していれば何も変えない。 */
    suspend fun completeTodo(todoId: Long, completedAtMillis: Long): TodoCompletion
}
