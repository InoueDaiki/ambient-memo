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

    suspend fun find(id: Long): Utterance?

    /** 発話の話者を手で直す。[speakerId] が null なら不明にする。類似度は消す。発話がなければ false を返す。 */
    suspend fun setSpeaker(id: Long, speakerId: Long?): Boolean

    /** 話者ごとの発話の数を返す。発話のない話者は含まない。 */
    suspend fun countBySpeaker(): Map<Long, Int>
}

interface CaptureGapRepository {
    suspend fun insert(gap: CaptureGap)

    /** `[fromMillis, toMillis)` と重なる欠落を、開始時刻の順に返す。 */
    suspend fun listOverlapping(fromMillis: Long, toMillis: Long): List<CaptureGap>
}

/** 話者と特徴量の記録。特徴量は端末の中だけで使い、この境界の外へ渡さない。 */
@Suppress("TooManyFunctions") // 話者の表の読み書きを 1 つの境界にまとめる
interface SpeakerRepository {
    /** 話者を id の順に返す。 */
    suspend fun listSpeakers(): List<Speaker>

    /**
     * [modelVersion] の特徴量を、自分、更新の新しい順に最大 [limit] 件返す。
     * 違う版の特徴量は比べられないので返さない。
     */
    suspend fun listEmbeddings(modelVersion: String, limit: Int): List<StoredSpeakerEmbedding>

    /**
     * 自分の特徴量を置き換える。自分の話者がなければ作る。過去の発話の話者は変えない。
     * 育てた自分の声はすべての版で消す。登録し直すのは、それが合わなくなったときなので。
     */
    suspend fun saveSelfEmbedding(embedding: SpeakerEmbedding, sampleCount: Int, nowMillis: Long): Speaker

    /**
     * 仮の話者を作り、最初の特徴量を記録する。名前は使われていない最小の番号の「話者N」にする。
     * [voicedMillis] は最初の特徴量の発話の長さ。
     */
    suspend fun createProvisionalSpeaker(
        embedding: SpeakerEmbedding,
        nowMillis: Long,
        voicedMillis: Long = 0,
    ): Speaker

    /** 特徴量のない仮の話者を作る。手で「別の話者」に直した発話の声が、もうメモリにないときに使う。 */
    suspend fun createProvisionalSpeakerWithoutVoice(nowMillis: Long): Speaker

    /** 既存の話者の特徴量を置き換える。 */
    suspend fun updateEmbedding(
        speakerId: Long,
        embedding: SpeakerEmbedding,
        sampleCount: Int,
        nowMillis: Long,
        voicedMillis: Long = 0,
    )

    /**
     * [merges] をすべて適用するか、何も適用しないかのどちらかで記録する。発話の話者を付け替え、統合元の話者を消す。
     * 統合元がもういなければその 1 件を飛ばすので、同じものをもう一度渡しても壊れない。
     * [renumberProvisional] なら、残った仮の話者の名前を id の順に「話者1」から付け直す。
     */
    suspend fun mergeSpeakers(merges: List<SpeakerMerge>, nowMillis: Long, renumberProvisional: Boolean = false)

    /** 自分を含むすべての話者と声を消し、すべての発話の話者を外す。すべて記録するか、何も記録しないかのどちらか。 */
    suspend fun resetSpeakers()

    /** 育てた自分の声を返す。[modelVersion] で育てたものだけを返す。 */
    suspend fun findSelfAdaptation(modelVersion: String): SelfAdaptation?

    suspend fun saveSelfAdaptation(adaptation: SelfAdaptation, nowMillis: Long)

    /** 手で付けた名前を記録する。推定で上書きされなくなる。話者がいなければ false を返す。 */
    suspend fun setManualName(speakerId: Long, name: String): Boolean

    /** 推定した名前を記録する。自分と、手で付けた名前は変えずに false を返す。 */
    suspend fun setInferredName(speakerId: Long, name: String): Boolean
}

/**
 * 話者の判定の集計。端末の中だけで、次の調整に使う。類似度の分布の数だけを持ち、発話や特徴量には戻せない。
 */
interface SpeakerDecisionLog {
    suspend fun record(samples: List<SpeakerDecisionSample>, nowMillis: Long)

    /** [fromMillis] を含む日から今までの集計を返す。判定、類似度の帯の順に並ぶ。 */
    suspend fun summarize(fromMillis: Long): List<SpeakerDecisionBucket>

    /** [beforeMillis] より前の日の集計を消す。 */
    suspend fun prune(beforeMillis: Long)
}

/** プロンプトから文章を生成する。出力の形式は保証しない。解析と検証は `domain` で行う。 */
interface Llm {
    /** 出力を作るモデルの版。メモに残し、モデルを変えたときに比べられるようにする。 */
    val modelVersion: String

    /**
     * 実行基盤が動かないときは例外を投げる。入力に問題がないのに出力が空のときは、空文字列を返す。
     *
     * @throws LlmInputTooLongException 入力が文脈長に収まらないとき
     */
    suspend fun generate(request: LlmRequest): String
}

/**
 * 入力が LLM の文脈長に収まらない。同じ入力では何度やっても失敗するので、呼び出し側は入力を変える。
 *
 * 実行基盤の文言に文字起こしが混ざるかもしれないので、元の例外を持たない。
 */
class LlmInputTooLongException : Exception("入力が文脈長に収まりません")

/**
 * まとまりごとの、LLM の処理を始めた回数の記録。プロセスの強制終了を越えて残す。
 *
 * 例外にならない失敗(実行基盤のネイティブのクラッシュ)も数えるために、処理の前に増やす。
 * [firstUtteranceId] はまとまりの最初の発話の id。発話の中身は渡さない。
 */
interface OrganizeAttempts {
    fun count(firstUtteranceId: Long): Int

    fun set(firstUtteranceId: Long, count: Int)

    /** まとまりの記録が終わったら呼ぶ。 */
    fun clear(firstUtteranceId: Long)
}

/** 整理の結果の記録。DB に書くのはエンジンだけなので、書き込みはこの境界に閉じる。 */
interface MemoRepository {
    /** 処理済みの印がない発話を、開始時刻、id の順に返す。 */
    suspend fun listUnorganizedUtterances(): List<Utterance>

    /**
     * メモと根拠の発話の処理済みの印を、1つのトランザクションで記録する。
     * 根拠の発話に、存在しないものか処理済みのものがあれば、何も書かずに null を返す。
     */
    suspend fun recordMemo(memo: NewMemo): Memo?

    /** 発話を、メモを作らずに処理済みにする。処理済みの発話はそのままにする。 */
    suspend fun markOrganized(utteranceIds: List<Long>, organizedAtMillis: Long)

    /** メモを開始時刻、id の順に返す。 */
    suspend fun listMemos(): List<Memo>

    /** [memoId] の根拠の発話の id を、開始時刻、id の順に返す。 */
    suspend fun listUtteranceIds(memoId: Long): List<Long>

    /** メモを開始時刻の新しい順に最大 [limit] 件返す。同時刻は後から記録したものを先にする。 */
    suspend fun listRecentMemos(limit: Int): List<Memo>

    /** メモがなければ null を返す。 */
    suspend fun findMemo(memoId: Long): Memo?

    /** [memoId] の根拠の発話を、開始時刻、id の順に返す。 */
    suspend fun listMemoUtterances(memoId: Long): List<Utterance>

    /**
     * 開始が [toMillis] より前で、終了が [fromMillis] 以降のメモのうち、タイトル、要約、根拠の発話の本文のどれかに [term] を含むものを、
     * 開始時刻の新しい順に最大 [limit] 件返す。同時刻は後から記録したものを先にする。
     * 英字の大文字と小文字は区別しない。ほかの文字は区別する。[term] が空ならすべてのメモに当たる。
     */
    suspend fun searchMemos(term: String, fromMillis: Long, toMillis: Long, limit: Int): List<Memo>
}
