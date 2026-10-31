package dev.ambientmemo.engine.domain

/** 整理ジョブの既定値。すべて仮で、長い会話の評価と実機で見直す。 */
data class OrganizeConfig(
    /** 発話の間がこの長さ以上あいたら、別のまとまりにする。最後のまとまりは、この長さだけ静かになるまで処理しない。 */
    val minSilenceGapMillis: Long = DEFAULT_MIN_SILENCE_GAP_MILLIS,
    /** 1つのまとまりの文字起こしの上限。超えると無音がなくても分ける。LLM の文脈長に収めるため。 */
    val maxChunkChars: Int = DEFAULT_MAX_CHUNK_CHARS,
    /** LLM の出力が読めなかったときの再試行の回数。 */
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
) {
    init {
        require(minSilenceGapMillis > 0) { "minSilenceGapMillis は正の値です" }
        require(maxChunkChars > 0) { "maxChunkChars は正の値です" }
        require(maxRetries >= 0) { "maxRetries は 0 以上です" }
    }

    private companion object {
        const val DEFAULT_MIN_SILENCE_GAP_MILLIS = 5 * 60 * 1_000L

        // 検証の文脈長 4096 トークンから、指示、日付の表、出力の分を除いた余裕を見た値
        const val DEFAULT_MAX_CHUNK_CHARS = 1_800
        const val DEFAULT_MAX_RETRIES = 1
    }
}

data class LlmRequest(
    val system: String,
    val user: String,
    val maxOutputTokens: Int,
)

enum class TodoKind {
    /** 自分がこれからやること。 */
    TODO,

    /** 自分が日時を覚えておくべき予定。 */
    REMINDER,
}

enum class TodoStatus {
    /** 期限の確度が低い。人が確かめるまで、リマインドの対象にしない。 */
    CANDIDATE,

    /** 確定した ToDo。 */
    OPEN,

    /** 完了した。保存の前の [NewTodo] には使わない。 */
    DONE,
}

/** [MemoRepository.completeTodo] の結果。 */
enum class TodoCompletion {
    COMPLETED,

    /** すでに完了している。何も変えていない。 */
    ALREADY_DONE,

    NOT_FOUND,
}

enum class TodoExtraction {
    /** LLM の出力を最後まで読めた。 */
    COMPLETE,

    /** 読めなかったか一部だけ読めた。ToDo が欠けているかもしれない。 */
    INCOMPLETE,
}

data class NewTodo(
    val task: String,
    val kind: TodoKind,
    val dueDate: CalendarDate?,
    val status: TodoStatus,
) {
    init {
        require(task.isNotBlank()) { "内容が空です" }
        require(status != TodoStatus.DONE) { "完了は記録の後に completeTodo で行います" }
    }
}

data class Todo(
    val id: Long,
    val memoId: Long,
    val task: String,
    val kind: TodoKind,
    val dueDate: CalendarDate?,
    val status: TodoStatus,
    val createdAtMillis: Long,
    /** 完了した時刻。[status] が [TodoStatus.DONE] のときだけある。 */
    val completedAtMillis: Long? = null,
)

/** 保存前のメモ。[title] と [summary] は要約に失敗すると null になる。発話は残るので、後からたどれる。 */
data class NewMemo(
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val title: String?,
    val summary: String?,
    val todoExtraction: TodoExtraction,
    val modelVersion: String,
    val createdAtMillis: Long,
    val utteranceIds: List<Long>,
    val todos: List<NewTodo>,
) {
    init {
        require(endedAtMillis >= startedAtMillis) { "終了時刻が開始時刻より前です" }
        require(utteranceIds.isNotEmpty()) { "根拠の発話がありません" }
        require(utteranceIds.distinct().size == utteranceIds.size) { "発話が重複しています" }
    }
}

data class Memo(
    val id: Long,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val title: String?,
    val summary: String?,
    val todoExtraction: TodoExtraction,
    val modelVersion: String,
    val createdAtMillis: Long,
)

enum class OrganizeOutcome {
    /** 今処理できるまとまりをすべて処理した。 */
    COMPLETED,

    /** 止めるように言われたので、まとまりの区切りで止めた。 */
    STOPPED,

    /** LLM が動かなかった。そのまとまりは未処理のまま残した。 */
    LLM_FAILED,
}

data class OrganizeResult(
    val outcome: OrganizeOutcome,
    val memoCount: Int,
    val todoCount: Int,
)
