import Foundation

// Kotlin の標準ライブラリと同じ挙動を Swift で書いたもの。Kotlin のファイルには対応しない。
//
// 共通の JSON テストで Kotlin と同じ結果にするため、文字列は Kotlin と同じく UTF-16 の単位で扱う。
// Swift の String の既定(書記素クラスター)や Character の判定は Kotlin と違うので、ここの関数を使う。

// MARK: - 例外

/// Kotlin の `require` が投げる `IllegalArgumentException` にあたる。
public struct IllegalArgumentException: Error, Equatable, CustomStringConvertible {
    public let message: String

    public init(_ message: String) {
        self.message = message
    }

    public var description: String { message }
}

/// Kotlin の `check` が投げる `IllegalStateException` にあたる。
public struct IllegalStateException: Error, Equatable, CustomStringConvertible {
    public let message: String

    public init(_ message: String) {
        self.message = message
    }

    public var description: String { message }
}

/// Kotlin の `first()` などが空のときに投げる `NoSuchElementException` にあたる。
public struct NoSuchElementException: Error, Equatable, CustomStringConvertible {
    public let message: String

    public init(_ message: String) {
        self.message = message
    }

    public var description: String { message }
}

/// Kotlin の `Math.toIntExact` などが投げる `ArithmeticException` にあたる。
public struct ArithmeticException: Error, Equatable, CustomStringConvertible {
    public let message: String

    public init(_ message: String) {
        self.message = message
    }

    public var description: String { message }
}

/// Kotlin の `require`。条件を満たさなければ [IllegalArgumentException] を投げる。
@inline(__always)
func require(_ condition: Bool, _ message: @autoclosure () -> String) throws {
    if !condition { throw IllegalArgumentException(message()) }
}

/// Kotlin の `check`。条件を満たさなければ [IllegalStateException] を投げる。
@inline(__always)
func check(_ condition: Bool, _ message: @autoclosure () -> String) throws {
    if !condition { throw IllegalStateException(message()) }
}

/// Kotlin の `x in lo..hi`。Swift の ClosedRange は lo > hi で落ちるので、比較で書く。
@inline(__always)
func isIn<T: Comparable>(_ value: T, _ lower: T, _ upper: T) -> Bool {
    value >= lower && value <= upper
}

// MARK: - 整数

/// Java の `Math.floorDiv`。
@inline(__always)
func floorDiv(_ x: Int64, _ y: Int64) -> Int64 {
    let quotient = x / y
    return (x % y != 0 && ((x < 0) != (y < 0))) ? quotient - 1 : quotient
}

/// Java の `Math.floorMod`。
@inline(__always)
func floorMod(_ x: Int64, _ y: Int64) -> Int64 {
    let remainder = x % y
    return (remainder != 0 && ((remainder < 0) != (y < 0))) ? remainder + y : remainder
}

@inline(__always)
func floorMod(_ x: Int, _ y: Int) -> Int {
    let remainder = x % y
    return (remainder != 0 && ((remainder < 0) != (y < 0))) ? remainder + y : remainder
}

/// Java の `Math.toIntExact`。Kotlin の Int は 32 ビットなので、その範囲を超えたら投げる。
func toIntExact(_ value: Int64) throws -> Int {
    guard value >= Int64(Int32.min), value <= Int64(Int32.max) else {
        throw ArithmeticException("integer overflow")
    }
    return Int(value)
}

/// Kotlin の `Double.toInt()`。NaN は 0、範囲外は Int の上限か下限にする。Swift の `Int(_:)` は落ちるので使わない。
func kotlinToInt(_ value: Double) -> Int {
    if value.isNaN { return 0 }
    if value >= Double(Int32.max) { return Int(Int32.max) }
    if value <= Double(Int32.min) { return Int(Int32.min) }
    return Int(value)
}

/// Kotlin の `Float.toInt()`。
func kotlinToInt(_ value: Float) -> Int {
    kotlinToInt(Double(value))
}

// MARK: - 浮動小数

/// Kotlin(Java)の `Float.compareTo`。-0.0 は 0.0 より小さく、NaN はどれよりも大きく、NaN 同士は等しい。
func kotlinCompare(_ first: Float, _ second: Float) -> Int {
    if first < second { return -1 }
    if first > second { return 1 }
    let firstBits = first.isNaN ? Float.nan.bitPattern : first.bitPattern
    let secondBits = second.isNaN ? Float.nan.bitPattern : second.bitPattern
    if firstBits == secondBits { return 0 }
    // ここに来るのは -0.0 と 0.0 か、NaN が片方だけのとき
    if first.isNaN { return 1 }
    if second.isNaN { return -1 }
    return first.sign == .minus ? -1 : 1
}

/// Java の `Math.max(float, float)`。NaN があれば NaN、-0.0 と 0.0 なら 0.0。
func kotlinMax(_ first: Float, _ second: Float) -> Float {
    if first.isNaN { return first }
    if second.isNaN { return second }
    if first == 0, second == 0 { return first.sign == .minus ? second : first }
    return first >= second ? first : second
}

/// Java の `Math.min(float, float)`。NaN があれば NaN、-0.0 と 0.0 なら -0.0。
func kotlinMin(_ first: Float, _ second: Float) -> Float {
    if first.isNaN { return first }
    if second.isNaN { return second }
    if first == 0, second == 0 { return first.sign == .minus ? first : second }
    return first <= second ? first : second
}

/// Java の `Math.min(double, double)`。
func kotlinMin(_ first: Double, _ second: Double) -> Double {
    if first.isNaN { return first }
    if second.isNaN { return second }
    if first == 0, second == 0 { return first.sign == .minus ? first : second }
    return first <= second ? first : second
}

/// Kotlin の `Double.coerceIn`。NaN はそのまま返す。
@inline(__always)
func coerceIn(_ value: Double, _ lower: Double, _ upper: Double) -> Double {
    if value < lower { return lower }
    if value > upper { return upper }
    return value
}

/// Kotlin の `Float.coerceIn`。NaN はそのまま返す。
@inline(__always)
func coerceIn(_ value: Float, _ lower: Float, _ upper: Float) -> Float {
    if value < lower { return lower }
    if value > upper { return upper }
    return value
}

// MARK: - 集まり

extension Sequence {
    /// Kotlin の `maxByOrNull`。同じ最大が複数あれば最初のものを返す。
    func kotlinMaxBy<Key: Comparable>(_ selector: (Element) throws -> Key) rethrows -> Element? {
        var best: Element?
        var bestKey: Key?
        for element in self {
            let key = try selector(element)
            if let current = bestKey {
                if current < key {
                    best = element
                    bestKey = key
                }
            } else {
                best = element
                bestKey = key
            }
        }
        return best
    }

    /// Kotlin の `maxByOrNull` で、選ぶ値が Float のもの。Kotlin と同じく `Float.compareTo` の順で比べる。
    func kotlinMaxByFloat(_ selector: (Element) throws -> Float) rethrows -> Element? {
        var best: Element?
        var bestKey: Float = 0
        for element in self {
            let key = try selector(element)
            if best == nil || kotlinCompare(bestKey, key) < 0 {
                best = element
                bestKey = key
            }
        }
        return best
    }

    /// Kotlin の `minByOrNull`。同じ最小が複数あれば最初のものを返す。
    func kotlinMinBy<Key: Comparable>(_ selector: (Element) throws -> Key) rethrows -> Element? {
        var best: Element?
        var bestKey: Key?
        for element in self {
            let key = try selector(element)
            if let current = bestKey {
                if key < current {
                    best = element
                    bestKey = key
                }
            } else {
                best = element
                bestKey = key
            }
        }
        return best
    }

    /// Kotlin の `groupBy`。キーは最初に現れた順に並ぶ。
    func kotlinGroupBy<Key: Hashable>(_ key: (Element) throws -> Key) rethrows -> [(key: Key, values: [Element])] {
        var order: [Key] = []
        var groups: [Key: [Element]] = [:]
        for element in self {
            let groupKey = try key(element)
            if groups[groupKey] == nil {
                order.append(groupKey)
                groups[groupKey] = [element]
            } else {
                groups[groupKey]!.append(element)
            }
        }
        return order.map { ($0, groups[$0]!) }
    }

    /// Kotlin の `sortedWith`。同じ順位の要素は元の順を保つ(安定)。
    func kotlinSorted(by areInIncreasingOrder: (Element, Element) throws -> Bool) rethrows -> [Element] {
        try enumerated()
            .sorted { first, second in
                if try areInIncreasingOrder(first.element, second.element) { return true }
                if try areInIncreasingOrder(second.element, first.element) { return false }
                return first.offset < second.offset
            }
            .map(\.element)
    }
}

// MARK: - 文字

/// Kotlin の `Char.isWhitespace()`(Java の `Character.isWhitespace` か `Character.isSpaceChar`)。
/// UTF-16 の 1 単位で判定する。全角の空白(U+3000)と改行しない空白(U+00A0)を含み、U+0085 を含まない。
func isKotlinWhitespace(_ unit: UInt16) -> Bool {
    switch unit {
    case 0x0009...0x000D, 0x001C...0x0020:
        return true
    case 0x00A0, 0x1680, 0x2000...0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000:
        return true
    default:
        return false
    }
}

/// Java の正規表現の `\s`(UNICODE_CHARACTER_CLASS なし)。ASCII の空白だけ。
@inline(__always)
func isRegexSpace(_ unit: UInt16) -> Bool {
    unit == 0x20 || (unit >= 0x09 && unit <= 0x0D)
}

/// Java の正規表現の `\d`(UNICODE_CHARACTER_CLASS なし)。ASCII の数字だけ。
@inline(__always)
func isRegexDigit(_ unit: UInt16) -> Bool {
    unit >= 0x30 && unit <= 0x39
}

/// Kotlin の `Char.isLetterOrDigit()`。UTF-16 の 1 単位で判定するので、サロゲートの片方はどちらでもない。
///
/// 分類は Swift が持つ Unicode の版で決まる。JDK の版と違うと、新しく足された文字の判定がずれることがある。
func isKotlinLetterOrDigit(_ unit: UInt16) -> Bool {
    guard let scalar = Unicode.Scalar(unit) else { return false }
    switch scalar.properties.generalCategory {
    case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter, .decimalNumber:
        return true
    default:
        return false
    }
}

// MARK: - 文字列

/// UTF-16 の単位から文字列を作る。対にならないサロゲートは U+FFFD になる(Kotlin はそのまま持つ)。
@inline(__always)
func stringFromUTF16<C: Collection>(_ units: C) -> String where C.Element == UInt16 {
    String(decoding: units, as: UTF16.self)
}

extension String {
    /// Kotlin の `String.length`。UTF-16 の単位の数。
    var kotlinLength: Int { utf16.count }

    /// Kotlin の `String.trim()`。両端の [isKotlinWhitespace] を除く。
    func kotlinTrim() -> String {
        kotlinTrim(where: isKotlinWhitespace)
    }

    /// Kotlin の `String.trim(predicate)`。UTF-16 の単位で両端を除く。
    func kotlinTrim(where predicate: (UInt16) -> Bool) -> String {
        let units = Array(utf16)
        var start = 0
        var end = units.count
        while start < end, predicate(units[start]) { start += 1 }
        while end > start, predicate(units[end - 1]) { end -= 1 }
        if start == 0, end == units.count { return self }
        return stringFromUTF16(units[start..<end])
    }

    /// Kotlin の `isBlank()`。
    var kotlinIsBlank: Bool { utf16.allSatisfy(isKotlinWhitespace) }

    /// Kotlin の `lines()`。`\r\n`、`\n`、`\r` で分ける。末尾の改行の後ろの空の行も含む。
    func kotlinLines() -> [String] {
        let units = Array(utf16)
        var lines: [String] = []
        var start = 0
        var index = 0
        while index < units.count {
            let unit = units[index]
            if unit == 0x0A || unit == 0x0D {
                lines.append(stringFromUTF16(units[start..<index]))
                if unit == 0x0D, index + 1 < units.count, units[index + 1] == 0x0A { index += 1 }
                start = index + 1
            }
            index += 1
        }
        lines.append(stringFromUTF16(units[start..<units.count]))
        return lines
    }

    /// Kotlin の `take(n)`。UTF-16 の単位で数える。サロゲートの対を割ると、その片方は U+FFFD になる。
    func kotlinTake(_ count: Int) -> String {
        let units = Array(utf16)
        if units.count <= count { return self }
        return stringFromUTF16(units[0..<count])
    }

    /// Kotlin の `filterNot(Char::isWhitespace)`。
    func kotlinWithoutWhitespace() -> String {
        stringFromUTF16(utf16.filter { !isKotlinWhitespace($0) })
    }

    /// Kotlin の `count(Char::isLetterOrDigit)`。
    func kotlinCountLetterOrDigit() -> Int {
        utf16.reduce(0) { $0 + (isKotlinLetterOrDigit($1) ? 1 : 0) }
    }

    /// Kotlin の `any(Char::isLetterOrDigit)`。
    func kotlinAnyLetterOrDigit() -> Bool {
        utf16.contains(where: isKotlinLetterOrDigit)
    }

    /// Kotlin の `lowercase()`(`Locale.ROOT`)。Swift の Unicode の大文字小文字の対応で代える。
    func kotlinLowercase() -> String {
        lowercased()
    }

    /// Kotlin の `indexOf(other)`。UTF-16 の単位で比べ、見つからなければ -1 を返す。
    func kotlinIndexOf(_ other: String, from start: Int = 0) -> Int {
        let units = Array(utf16)
        let target = Array(other.utf16)
        if target.isEmpty { return min(max(start, 0), units.count) }
        if units.count < target.count { return -1 }
        var index = max(start, 0)
        while index + target.count <= units.count {
            if units[index] == target[0], Array(units[index..<index + target.count]) == target { return index }
            index += 1
        }
        return -1
    }

    /// Kotlin の `contains(other)`。UTF-16 の単位で比べる。Swift の `contains` は書記素で比べるので使わない。
    func kotlinContains(_ other: String) -> Bool {
        kotlinIndexOf(other) >= 0
    }

    /// Kotlin の `substringAfter(delimiter)`。見つからなければ全体を返す。
    func kotlinSubstringAfter(_ delimiter: String) -> String {
        let index = kotlinIndexOf(delimiter)
        if index < 0 { return self }
        return stringFromUTF16(Array(utf16)[(index + delimiter.utf16.count)...])
    }
}

// MARK: - ロック

/// 値をロックで守る。Kotlin の `@Synchronized` や `synchronized` にあたる。
/// `Synchronization.Mutex` は iOS 18 からなので、NSLock で書く。
final class LockedState<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Value

    init(_ value: Value) {
        self.value = value
    }

    func withLock<Result>(_ body: (inout Value) throws -> Result) rethrows -> Result {
        lock.lock()
        defer { lock.unlock() }
        return try body(&value)
    }
}
