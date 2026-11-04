import Foundation

/**
 * 時差を持たない暦の日付。期限や「今日」の表現に使う。
 *
 * Kotlin は java.util の暦に計算を任せる。Swift は Foundation のグレゴリオ暦に任せる。
 * どちらも 1582 年 10 月より前はユリウス暦として扱う。Foundation と JDK の暦の実装が違うので、
 * その切り替えの前後の日付はずれるかもしれない。会話の日付(1900〜2100 年)では同じになる。
 *
 * Swift では存在しない日付の初期化と、範囲外になる [plusDays] が [IllegalArgumentException] を投げる。
 */
public struct CalendarDate: Sendable, Hashable, Comparable, CustomStringConvertible {
    public let year: Int
    public let month: Int
    public let day: Int

    public init(_ year: Int, _ month: Int, _ day: Int) throws {
        try require(Self.isValid(year, month, day), "存在しない日付です")
        self.year = year
        self.month = month
        self.day = day
    }

    /// 月曜が 0、日曜が 6。
    public var weekdayIndex: Int {
        // Foundation の weekday は Java の DAY_OF_WEEK と同じく、日曜が 1、土曜が 7
        let weekday = Self.utcCalendar.component(.weekday, from: utcDate())
        return (weekday + Self.DAYS_FROM_SUNDAY_TO_SATURDAY) % Self.DAYS_PER_WEEK
    }

    public func plusDays(_ days: Int) throws -> CalendarDate {
        guard let date = Self.utcCalendar.date(byAdding: .day, value: days, to: utcDate()) else {
            throw IllegalArgumentException("存在しない日付です")
        }
        return try Self.toCalendarDate(date, Self.utcCalendar)
    }

    public static func < (lhs: CalendarDate, rhs: CalendarDate) -> Bool {
        (lhs.year, lhs.month, lhs.day) < (rhs.year, rhs.month, rhs.day)
    }

    /// `YYYY-MM-DD` 形式。Kotlin の `toString()`。
    public var description: String {
        "\(Self.pad(year, 4))-\(Self.pad(month, 2))-\(Self.pad(day, 2))"
    }

    private func utcDate() -> Date {
        // isValid を通った日付なので必ず作れる
        Self.utcCalendar.date(from: DateComponents(year: year, month: month, day: day))!
    }

    /// `YYYY-MM-DD` 形式で存在する日付だけを読む。それ以外は nil。
    ///
    /// Kotlin の正規表現 `(\d{4})-(\d{2})-(\d{2})` の `\d` は ASCII の数字だけなので、全角の数字は読まない。
    public static func parse(_ text: String) -> CalendarDate? {
        let units = Array(text.utf16)
        guard units.count == 10, units[4] == 0x2D, units[7] == 0x2D else { return nil }
        let digitPositions = [0, 1, 2, 3, 5, 6, 8, 9]
        guard digitPositions.allSatisfy({ isRegexDigit(units[$0]) }) else { return nil }
        func number(_ range: Range<Int>) -> Int {
            range.reduce(0) { $0 * 10 + Int(units[$1] - 0x30) }
        }
        let year = number(0..<4)
        let month = number(5..<7)
        let day = number(8..<10)
        return isValid(year, month, day) ? try? CalendarDate(year, month, day) : nil
    }

    /// [timeZone] での、その時刻の日付。
    public static func of(_ millis: Int64, _ timeZone: TimeZone) throws -> CalendarDate {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        calendar.locale = Locale(identifier: "en_US_POSIX")
        let date = Date(timeIntervalSince1970: Double(millis) / 1_000)
        return try toCalendarDate(date, calendar)
    }

    private static let utcCalendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        calendar.locale = Locale(identifier: "en_US_POSIX")
        return calendar
    }()

    private static let DAYS_PER_WEEK = 7
    private static let DAYS_FROM_SUNDAY_TO_SATURDAY = 5
    private static let MAX_YEAR = 9_999
    private static let MONTHS_PER_YEAR = 12

    /// Java の Calendar.YEAR と同じく、紀元前も年の数だけを使う。
    private static func toCalendarDate(_ date: Date, _ calendar: Calendar) throws -> CalendarDate {
        let components = calendar.dateComponents([.year, .month, .day], from: date)
        return try CalendarDate(components.year!, components.month!, components.day!)
    }

    private static func isValid(_ year: Int, _ month: Int, _ day: Int) -> Bool {
        if !isIn(year, 1, MAX_YEAR) || !isIn(month, 1, MONTHS_PER_YEAR) || day < 1 { return false }
        guard
            let first = utcCalendar.date(from: DateComponents(year: year, month: month, day: 1)),
            let range = utcCalendar.range(of: .day, in: .month, for: first)
        else { return false }
        return day <= range.upperBound - 1
    }

    private static func pad(_ value: Int, _ width: Int) -> String {
        let text = String(value)
        return text.count >= width ? text : String(repeating: "0", count: width - text.count) + text
    }
}
