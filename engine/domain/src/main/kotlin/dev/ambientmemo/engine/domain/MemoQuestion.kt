package dev.ambientmemo.engine.domain

import java.util.TimeZone

/** 質問の答えの種類。実行基盤の失敗は例外で伝える。 */
enum class AnswerOutcome {
    /** メモを根拠に答えた。 */
    ANSWERED,

    /** 関係するメモが見つからなかったか、メモから答えが分からなかった。 */
    NOT_FOUND,
}

/** 質問の進み具合。モデルの読み込みは実行の組み立ての側で知らせる。 */
enum class AskPhase { SEARCHING, ANSWERING }

/** 質問の既定値。すべて仮で、実機と実際の質問で見直す。 */
data class AskConfig(
    /** 検索の計画を LLM に頼む回数の上限。見つからなければ、前の語を伝えて別の語を頼む。 */
    val maxSearchRounds: Int = DEFAULT_MAX_SEARCH_ROUNDS,
    /** 答えの根拠として LLM に渡すメモの数の上限。 */
    val maxSources: Int = DEFAULT_MAX_SOURCES,
    /** LLM に渡すメモの文字数の上限。文脈長 4096 トークンに、指示と答えの分を残すため。 */
    val maxContextChars: Int = DEFAULT_MAX_CONTEXT_CHARS,
    /** 語ごとに読むメモの数の上限。 */
    val candidatesPerTerm: Int = DEFAULT_CANDIDATES_PER_TERM,
    /** メモごとに添える、語を含む発話の数の上限。要約から落ちた細部を拾うため。 */
    val maxQuotes: Int = DEFAULT_MAX_QUOTES,
    /** 添える発話の文字数の上限。 */
    val maxQuoteChars: Int = DEFAULT_MAX_QUOTE_CHARS,
) {
    init {
        require(maxSearchRounds > 0) { "maxSearchRounds は正の値です" }
        require(maxSources > 0) { "maxSources は正の値です" }
        require(maxContextChars > 0) { "maxContextChars は正の値です" }
        require(candidatesPerTerm > 0) { "candidatesPerTerm は正の値です" }
        require(maxQuotes >= 0) { "maxQuotes は 0 以上です" }
        require(maxQuoteChars > 0) { "maxQuoteChars は正の値です" }
    }

    private companion object {
        const val DEFAULT_MAX_SEARCH_ROUNDS = 2
        const val DEFAULT_MAX_SOURCES = 5
        const val DEFAULT_MAX_CONTEXT_CHARS = 2_400
        const val DEFAULT_CANDIDATES_PER_TERM = 20
        const val DEFAULT_MAX_QUOTES = 3
        const val DEFAULT_MAX_QUOTE_CHARS = 80
    }
}

/**
 * 質問の答え。[text] は [AnswerOutcome.ANSWERED] のときだけある。
 * [sources] は答えが挙げたメモで、挙げた順。挙げなかったときは LLM に渡したメモをすべて入れる。
 */
data class MemoAnswer(
    val outcome: AnswerOutcome,
    val text: String?,
    val sources: List<Memo>,
    /** 検索の計画を LLM に頼んだ回数。 */
    val searchRounds: Int,
)

/**
 * 過去のメモを探して質問に答える。
 *
 * 1. LLM に検索の語と期間を考えさせる
 * 2. 語ごとにメモのタイトル、要約、根拠の発話を探し、多くの語に当たったメモを先にする
 * 3. 見つからなければ、使った語を伝えて別の語を考えさせる。それでもなく、期間があれば、期間のメモを新しい順に使う
 * 4. 見つかったメモの要約と、語を含む発話を番号付きで渡し、番号を挙げて答えさせる
 *
 * 検索は DB の部分一致だけで、ベクトルは使わない。日本語の言い換えは LLM の語に任せる。
 * LLM の例外とキャンセルはそのまま投げる。質問とメモの中身は記録に残さない。
 */
class MemoQuestionAnswerer(
    private val memos: MemoRepository,
    private val speakers: SpeakerRepository,
    private val llm: Llm,
    private val clock: Clock,
    private val timeZone: TimeZone,
    private val config: AskConfig = AskConfig(),
) {
    suspend fun answer(question: String, onPhase: (AskPhase) -> Unit = {}): MemoAnswer {
        require(question.isNotBlank()) { "質問が空です" }
        val today = CalendarDate.of(clock.nowMillis(), timeZone)
        onPhase(AskPhase.SEARCHING)
        val search = search(question, today)
        if (search.memos.isEmpty()) return MemoAnswer(AnswerOutcome.NOT_FOUND, null, emptyList(), search.rounds)
        onPhase(AskPhase.ANSWERING)
        val labels = SpeakerLabels(speakers.listSpeakers())
        val context = contextOf(search.memos, search.terms, labels)
        val parsed = parseAnswer(llm.generate(AskPrompts.answer(question, today, context.text)), context.memos.size)
            ?: return MemoAnswer(AnswerOutcome.NOT_FOUND, null, emptyList(), search.rounds)
        val sources = parsed.citations.map { context.memos[it - 1] }.ifEmpty { context.memos }
        return MemoAnswer(AnswerOutcome.ANSWERED, parsed.text, sources, search.rounds)
    }

    private class Search(val memos: List<Memo>, val terms: List<String>, val rounds: Int)

    private suspend fun search(question: String, today: CalendarDate): Search {
        val tried = mutableListOf<String>()
        var lastRange: SearchPlan? = null
        for (round in 1..config.maxSearchRounds) {
            // 読めない出力は語も期間もない計画として扱い、次の回に任せる
            val plan = parseSearchPlan(llm.generate(AskPrompts.plan(question, today, tried)))
                ?: SearchPlan(emptyList(), null, null)
            if (plan.from != null) lastRange = plan
            val found = find(plan.terms, plan)
            if (found.isNotEmpty()) return Search(found, plan.terms, round)
            // 期間だけの計画は、語を変えても同じ結果になる
            if (plan.terms.isEmpty() && plan.from != null) return Search(emptyList(), emptyList(), round)
            tried += plan.terms.filterNot { it in tried }
        }
        val inRange = lastRange?.let { find(emptyList(), it) }.orEmpty()
        return Search(inRange, emptyList(), config.maxSearchRounds)
    }

    /** 語がなければ、期間のメモを新しい順に返す。語も期間もなければ探さない。 */
    private suspend fun find(terms: List<String>, range: SearchPlan): List<Memo> {
        val from = range.from?.startOfDayMillis(timeZone) ?: Long.MIN_VALUE
        val to = range.to?.plusDays(1)?.startOfDayMillis(timeZone) ?: Long.MAX_VALUE
        if (terms.isEmpty()) {
            return if (range.from == null) emptyList() else memos.searchMemos("", from, to, config.maxSources)
        }
        // 先に挙げた語ほど大事なので、重くする
        val scores = LinkedHashMap<Long, Pair<Memo, Int>>()
        terms.forEachIndexed { index, term ->
            memos.searchMemos(term, from, to, config.candidatesPerTerm).forEach { memo ->
                val score = (scores[memo.id]?.second ?: 0) + terms.size - index
                scores[memo.id] = memo to score
            }
        }
        return scores.values
            .sortedWith(
                compareByDescending<Pair<Memo, Int>> { it.second }
                    .thenByDescending { it.first.startedAtMillis }
                    .thenByDescending { it.first.id },
            )
            .take(config.maxSources)
            .map { it.first }
    }

    private class Context(val memos: List<Memo>, val text: String)

    /** 上限を超えるメモは渡さない。最初のメモだけは、上限で切って渡す。 */
    private suspend fun contextOf(found: List<Memo>, terms: List<String>, labels: SpeakerLabels): Context {
        val blocks = mutableListOf<String>()
        var used = 0
        for (memo in found) {
            val block = blockOf(blocks.size + 1, memo, terms, labels)
            if (blocks.isNotEmpty() && used + block.length > config.maxContextChars) break
            blocks += block.take(config.maxContextChars)
            used += block.length
        }
        return Context(found.take(blocks.size), blocks.joinToString("\n\n"))
    }

    /** 要約のないメモは、最初の発話を代わりに添える。 */
    private suspend fun blockOf(number: Int, memo: Memo, terms: List<String>, labels: SpeakerLabels): String {
        val date = CalendarDate.of(memo.startedAtMillis, timeZone)
        val header = "[$number] ${AskPrompts.label(date)} ${clockLabel(memo.startedAtMillis, timeZone)}" +
            memo.title?.let { " $it" }.orEmpty()
        val utterances = memos.listMemoUtterances(memo.id).filter { it.text.isNotBlank() }
        val keys = terms.map(::foldAscii)
        val quoted = utterances.filter { utterance -> keys.any { it in foldAscii(utterance.text) } }
            .ifEmpty { if (memo.summary == null) utterances else emptyList() }
            .take(config.maxQuotes)
            .map { "発話: " + it.toTranscriptLine(timeZone, labels).take(config.maxQuoteChars) }
        return (listOf(header) + listOfNotNull(memo.summary?.trim()) + quoted).joinToString("\n")
    }
}

/** 英字だけを小文字にする。SQLite の lower と同じく、ほかの文字は変えない。 */
fun foldAscii(text: String): String = buildString(text.length) {
    text.forEach { char -> append(if (char in 'A'..'Z') char + ('a' - 'A') else char) }
}
