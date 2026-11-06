package dev.ambientmemo.engine.domain

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * 時差を持たない暦の日付。期限や「今日」の表現に使う。
 *
 * java.time は minSdk 24 の端末で使えないので、計算は java.util の暦に任せる。
 */
data class CalendarDate(
    val year: Int,
    val month: Int,
    val day: Int,
) : Comparable<CalendarDate> {
    init {
        require(isValid(year, month, day)) { "存在しない日付です" }
    }

    /** 月曜が 0、日曜が 6。 */
    val weekdayIndex: Int
        get() = (utcCalendar().get(Calendar.DAY_OF_WEEK) + DAYS_FROM_SUNDAY_TO_SATURDAY) % DAYS_PER_WEEK

    /** 曜日の 1 文字。「月」から「日」。 */
    val weekdayName: String
        get() = WEEKDAYS[weekdayIndex].toString()

    /** [timeZone] での、この日が始まる時刻。 */
    fun startOfDayMillis(timeZone: TimeZone): Long = strictCalendar(timeZone).apply {
        clear()
        set(year, month - 1, day)
    }.timeInMillis

    fun plusDays(days: Int): CalendarDate = utcCalendar().apply { add(Calendar.DAY_OF_MONTH, days) }.toCalendarDate()

    override fun compareTo(other: CalendarDate): Int =
        compareValuesBy(this, other, { it.year }, { it.month }, { it.day })

    /** `YYYY-MM-DD` 形式。 */
    override fun toString(): String = String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day)

    private fun utcCalendar() = strictCalendar(UTC).apply {
        clear()
        set(year, month - 1, day)
    }

    companion object {
        private val ISO_DATE = Regex("""(\d{4})-(\d{2})-(\d{2})""")
        private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
        private const val WEEKDAYS = "月火水木金土日"
        private const val DAYS_PER_WEEK = 7
        private const val DAYS_FROM_SUNDAY_TO_SATURDAY = 5

        /** `YYYY-MM-DD` 形式で存在する日付だけを読む。それ以外は null。 */
        fun parse(text: String): CalendarDate? = ISO_DATE.matchEntire(text)?.destructured?.let { (year, month, day) ->
            if (isValid(year.toInt(), month.toInt(), day.toInt())) {
                CalendarDate(year.toInt(), month.toInt(), day.toInt())
            } else {
                null
            }
        }

        /** [timeZone] での、その時刻の日付。 */
        fun of(millis: Long, timeZone: TimeZone): CalendarDate =
            strictCalendar(timeZone).apply { timeInMillis = millis }.toCalendarDate()

        private fun strictCalendar(timeZone: TimeZone) = GregorianCalendar(timeZone, Locale.ROOT)

        private fun Calendar.toCalendarDate() =
            CalendarDate(get(Calendar.YEAR), get(Calendar.MONTH) + 1, get(Calendar.DAY_OF_MONTH))

        private fun isValid(year: Int, month: Int, day: Int): Boolean {
            if (year !in 1..MAX_YEAR || month !in 1..MONTHS_PER_YEAR || day < 1) return false
            val lastDay = strictCalendar(UTC).apply {
                clear()
                set(year, month - 1, 1)
            }.getActualMaximum(Calendar.DAY_OF_MONTH)
            return day <= lastDay
        }

        private const val MAX_YEAR = 9_999
        private const val MONTHS_PER_YEAR = 12
    }
}
