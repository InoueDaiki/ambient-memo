import Foundation

// Swift にしたことで変わった点:
// - suspend 関数は `async throws` にした。Kotlin はどの関数も例外を投げうるので、外部の実装が失敗しうるものは `throws` にする。
// - ポートは別のタスクから呼ぶので、すべて `Sendable` にする。
// - Flow は AsyncThrowingStream にした。

public protocol Clock: Sendable {
    func nowMillis() -> Int64
}

public struct SystemClock: Clock {
    public init() {}

    public func nowMillis() -> Int64 {
        Int64((Date().timeIntervalSince1970 * 1_000).rounded(.down))
    }
}

/// 16 ビットのモノラル PCM を流す。
public protocol AudioSource: Sendable {
    var sampleRateHz: Int { get }

    /**
     * 呼ぶたびに読み取りを始める。空のフレームは流さない。
     *
     * Kotlin の Flow と同じく、受け取る側が次を求めるまで読まない作り(`AsyncThrowingStream(unfolding:)` など)にする。
     * 処理が遅れたときの欠落は時刻の飛びとして表す。
     * 受け取る側のタスクが取り消されると、Swift の AsyncThrowingStream は例外を投げずに終わる。
     * そのため受け取る側は、終わった後に取り消しを確かめる。
     */
    func frames() -> AsyncThrowingStream<AudioFrame, any Error>
}

/// フレームを発話か無音かに分ける。状態を持つ実装を想定し、フレームは時系列で渡す。
public protocol VoiceActivityDetector: Sendable {
    func isSpeech(_ frame: AudioFrame) throws -> Bool

    /// 内部の状態を捨てる。読み取りの開始時と、音声が途切れたときに呼ぶ。
    func reset()
}

/**
 * 発話区間を文字にする。
 *
 * [segment] は呼び出し側が解放するので、実装は解放せず、戻った後に参照を残さない。
 */
public protocol Transcriber: Sendable {
    func transcribe(_ segment: SpeechSegment) async throws -> TimedTranscription
}

/// 発話区間から話者の特徴量を作る。[segment] の扱いは [Transcriber] と同じ。
public protocol SpeakerEmbedder: Sendable {
    /// 作る特徴量のモデルの版。違う版の特徴量は比べられない。
    var modelVersion: String { get }

    /// 音声が短すぎて特徴量を作れないときは nil を返す。
    func embed(_ segment: SpeechSegment) async throws -> SpeakerEmbedding?
}

public protocol UtteranceRepository: Sendable {
    func insert(_ utterance: NewUtterance) async throws -> Utterance

    /// 1 つの発話区間から分けた行を、渡した順に、すべて保存するか何も保存しないかのどちらかで記録する。
    func insertAll(_ utterances: [NewUtterance]) async throws -> [Utterance]

    /// `[fromMillis, toMillis)` と重なる発話を、開始時刻、id の順に返す。
    func listOverlapping(fromMillis: Int64, toMillis: Int64) async throws -> [Utterance]

    /// 開始時刻の新しい順に最大 [limit] 件を返す。同時刻は後から保存したものを先にする。
    func listRecent(limit: Int) async throws -> [Utterance]

    func find(id: Int64) async throws -> Utterance?

    /// 発話の話者を手で直す。[speakerId] が nil なら不明にする。類似度は消す。発話がなければ false を返す。
    func setSpeaker(id: Int64, speakerId: Int64?) async throws -> Bool

    /// 話者ごとの発話の数を返す。発話のない話者は含まない。
    func countBySpeaker() async throws -> [Int64: Int]
}

public protocol CaptureGapRepository: Sendable {
    func insert(_ gap: CaptureGap) async throws

    /// `[fromMillis, toMillis)` と重なる欠落を、開始時刻の順に返す。
    func listOverlapping(fromMillis: Int64, toMillis: Int64) async throws -> [CaptureGap]
}

/// 話者と特徴量の記録。特徴量は端末の中だけで使い、この境界の外へ渡さない。
public protocol SpeakerRepository: Sendable {
    /// 話者を id の順に返す。
    func listSpeakers() async throws -> [Speaker]

    /**
     * [modelVersion] の特徴量を、自分、更新の新しい順に最大 [limit] 件返す。
     * 違う版の特徴量は比べられないので返さない。
     */
    func listEmbeddings(modelVersion: String, limit: Int) async throws -> [StoredSpeakerEmbedding]

    /**
     * 自分の特徴量を置き換える。自分の話者がなければ作る。過去の発話の話者は変えない。
     * 育てた自分の声はすべての版で消す。登録し直すのは、それが合わなくなったときなので。
     */
    func saveSelfEmbedding(_ embedding: SpeakerEmbedding, sampleCount: Int, nowMillis: Int64) async throws -> Speaker

    /**
     * 仮の話者を作り、最初の特徴量を記録する。名前は使われていない最小の番号の「話者N」にする。
     * [voicedMillis] は最初の特徴量の発話の長さ。Kotlin の既定値 0 は、下の extension で補う。
     */
    func createProvisionalSpeaker(
        _ embedding: SpeakerEmbedding,
        nowMillis: Int64,
        voicedMillis: Int64
    ) async throws -> Speaker

    /// 特徴量のない仮の話者を作る。手で「別の話者」に直した発話の声が、もうメモリにないときに使う。
    func createProvisionalSpeakerWithoutVoice(nowMillis: Int64) async throws -> Speaker

    /// 既存の話者の特徴量を置き換える。
    func updateEmbedding(
        speakerId: Int64,
        _ embedding: SpeakerEmbedding,
        sampleCount: Int,
        nowMillis: Int64,
        voicedMillis: Int64
    ) async throws

    /**
     * [merges] をすべて適用するか、何も適用しないかのどちらかで記録する。発話の話者を付け替え、統合元の話者を消す。
     * 統合元がもういなければその 1 件を飛ばすので、同じものをもう一度渡しても壊れない。
     * [renumberProvisional] なら、残った仮の話者の名前を id の順に「話者1」から付け直す。
     */
    func mergeSpeakers(_ merges: [SpeakerMerge], nowMillis: Int64, renumberProvisional: Bool) async throws

    /// 自分を含むすべての話者と声を消し、すべての発話の話者を外す。すべて記録するか、何も記録しないかのどちらか。
    func resetSpeakers() async throws

    /// 育てた自分の声を返す。[modelVersion] で育てたものだけを返す。
    func findSelfAdaptation(modelVersion: String) async throws -> SelfAdaptation?

    func saveSelfAdaptation(_ adaptation: SelfAdaptation, nowMillis: Int64) async throws

    /// 手で付けた名前を記録する。推定で上書きされなくなる。話者がいなければ false を返す。
    func setManualName(speakerId: Int64, name: String) async throws -> Bool

    /// 推定した名前を記録する。自分と、手で付けた名前は変えずに false を返す。
    func setInferredName(speakerId: Int64, name: String) async throws -> Bool
}

// Swift の protocol は既定の引数を持てないので、Kotlin の既定値をここで補う
extension SpeakerRepository {
    public func createProvisionalSpeaker(_ embedding: SpeakerEmbedding, nowMillis: Int64) async throws -> Speaker {
        try await createProvisionalSpeaker(embedding, nowMillis: nowMillis, voicedMillis: 0)
    }

    public func updateEmbedding(
        speakerId: Int64,
        _ embedding: SpeakerEmbedding,
        sampleCount: Int,
        nowMillis: Int64
    ) async throws {
        try await updateEmbedding(
            speakerId: speakerId,
            embedding,
            sampleCount: sampleCount,
            nowMillis: nowMillis,
            voicedMillis: 0
        )
    }

    public func mergeSpeakers(_ merges: [SpeakerMerge], nowMillis: Int64) async throws {
        try await mergeSpeakers(merges, nowMillis: nowMillis, renumberProvisional: false)
    }
}

/**
 * 話者の判定の集計。端末の中だけで、次の調整に使う。類似度の分布の数だけを持ち、発話や特徴量には戻せない。
 */
public protocol SpeakerDecisionLog: Sendable {
    func record(_ samples: [SpeakerDecisionSample], nowMillis: Int64) async throws

    /// [fromMillis] を含む日から今までの集計を返す。判定、類似度の帯の順に並ぶ。
    func summarize(fromMillis: Int64) async throws -> [SpeakerDecisionBucket]

    /// [beforeMillis] より前の日の集計を消す。
    func prune(beforeMillis: Int64) async throws
}

/// プロンプトから文章を生成する。出力の形式は保証しない。解析と検証は `domain` で行う。
public protocol Llm: Sendable {
    /// 出力を作るモデルの版。メモに残し、モデルを変えたときに比べられるようにする。
    var modelVersion: String { get }

    /**
     * 実行基盤が動かないときは例外を投げる。入力に問題がないのに出力が空のときは、空文字列を返す。
     *
     * 入力が文脈長に収まらないときは [LlmInputTooLongException] を投げる。
     */
    func generate(_ request: LlmRequest) async throws -> String
}

/**
 * 入力が LLM の文脈長に収まらない。同じ入力では何度やっても失敗するので、呼び出し側は入力を変える。
 *
 * 実行基盤の文言に文字起こしが混ざるかもしれないので、元の例外を持たない。
 */
public struct LlmInputTooLongException: Error, Equatable, CustomStringConvertible {
    public let message = "入力が文脈長に収まりません"

    public init() {}

    public var description: String { message }
}

/**
 * まとまりごとの、LLM の処理を始めた回数の記録。プロセスの強制終了を越えて残す。
 *
 * 例外にならない失敗(実行基盤のネイティブのクラッシュ)も数えるために、処理の前に増やす。
 * [firstUtteranceId] はまとまりの最初の発話の id。発話の中身は渡さない。
 * ファイルに残す実装が失敗を伝えられるように、Swift では `throws` にした。
 */
public protocol OrganizeAttempts: Sendable {
    func count(firstUtteranceId: Int64) throws -> Int

    func set(firstUtteranceId: Int64, count: Int) throws

    /// まとまりの記録が終わったら呼ぶ。
    func clear(firstUtteranceId: Int64) throws
}

/// 整理の結果の記録。DB に書くのはエンジンだけなので、書き込みはこの境界に閉じる。
public protocol MemoRepository: Sendable {
    /// 処理済みの印がない発話を、開始時刻、id の順に返す。
    func listUnorganizedUtterances() async throws -> [Utterance]

    /**
     * メモ、ToDo、根拠の発話の処理済みの印を、1つのトランザクションで記録する。
     * 根拠の発話に、存在しないものか処理済みのものがあれば、何も書かずに nil を返す。
     */
    func recordMemo(_ memo: NewMemo) async throws -> Memo?

    /// 発話を、メモを作らずに処理済みにする。処理済みの発話はそのままにする。
    func markOrganized(_ utteranceIds: [Int64], organizedAtMillis: Int64) async throws

    /// メモを開始時刻、id の順に返す。
    func listMemos() async throws -> [Memo]

    /// [memoId] の根拠の発話の id を、開始時刻、id の順に返す。
    func listUtteranceIds(memoId: Int64) async throws -> [Int64]

    /// [memoId] の ToDo を、記録した順に返す。
    func listTodos(memoId: Int64) async throws -> [Todo]

    /// メモを開始時刻の新しい順に最大 [limit] 件返す。同時刻は後から記録したものを先にする。
    func listRecentMemos(limit: Int) async throws -> [Memo]

    /// メモがなければ nil を返す。
    func findMemo(memoId: Int64) async throws -> Memo?

    /// [memoId] の根拠の発話を、開始時刻、id の順に返す。
    func listMemoUtterances(memoId: Int64) async throws -> [Utterance]

    /**
     * 画面に出す順で ToDo を最大 [limit] 件返す。
     * 未完了を先に、期限の早い順。期限がなければ後ろ、同じなら新しい順、それも同じなら記録した順。
     */
    func listTodosByPriority(limit: Int) async throws -> [Todo]

    /// ToDo を完了にする。完了の前の状態は取り消しのために残す。すでに完了していれば何も変えない。
    func completeTodo(todoId: Int64, completedAtMillis: Int64) async throws -> TodoCompletion
}
