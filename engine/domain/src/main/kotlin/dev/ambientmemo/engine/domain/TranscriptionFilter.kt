package dev.ambientmemo.engine.domain

/** 文字起こしの結果を保存するかを決める。保存しない発話は欠落にしない。 */
fun interface TranscriptionFilter {
    fun shouldSave(text: String, durationMillis: Long): Boolean
}

/**
 * 雑音だけの区間から出た短い文字を除く。
 *
 * 仮の規則で、値は DEMAND の環境音 5 分 × 12 種類と、Mac の読み上げの短い語で決めた。実際の会話では測っていない。
 * - 1 文字は除く。雑音でよく出る。「え」のような本物の相づちも落ちる
 * - 2 文字は、区間が長いときだけ除く。雑音の区間は長いのに文字が少なく、「そう」のような本物の短い発話は区間が短い
 */
class NoiseTextFilter(
    private val longDurationMillis: Long = DEFAULT_LONG_DURATION_MILLIS,
) : TranscriptionFilter {
    override fun shouldSave(text: String, durationMillis: Long): Boolean {
        val length = text.count(Char::isLetterOrDigit)
        return when {
            length <= 1 -> false
            length == 2 -> durationMillis < longDurationMillis
            else -> true
        }
    }

    private companion object {
        const val DEFAULT_LONG_DURATION_MILLIS = 2_000L
    }
}
