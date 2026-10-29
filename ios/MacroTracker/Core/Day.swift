import Foundation

/// A calendar day in the phone's time zone, written "yyyy-MM-dd" like the Android app and the server.
struct Day: Hashable, Comparable, Codable, Identifiable, CustomStringConvertible {
    let year: Int
    let month: Int
    let day: Int

    var id: String { iso }
    var description: String { iso }
    var iso: String { String(format: "%04d-%02d-%02d", year, month, day) }

    init(year: Int, month: Int, day: Int) {
        self.year = year
        self.month = month
        self.day = day
    }

    init(_ date: Date, calendar: Calendar = .current) {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        self.init(year: parts.year ?? 1970, month: parts.month ?? 1, day: parts.day ?? 1)
    }

    /// Parses "yyyy-MM-dd" (anything after the date, like a time, is ignored).
    init?(iso: String) {
        let parts = iso.prefix(10).split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3, (1...12).contains(parts[1]), (1...31).contains(parts[2]) else { return nil }
        self.init(year: parts[0], month: parts[1], day: parts[2])
    }

    static var today: Day { Day(Date()) }

    /// Noon of this day, so adding days never trips over daylight saving changes.
    var date: Date {
        var parts = DateComponents()
        parts.year = year
        parts.month = month
        parts.day = day
        parts.hour = 12
        return Calendar.current.date(from: parts) ?? Date()
    }

    func adding(days: Int) -> Day {
        Day(Calendar.current.date(byAdding: .day, value: days, to: date) ?? date)
    }

    /// Monday = 0 … Sunday = 6.
    var weekdayIndex: Int {
        let weekday = Calendar.current.component(.weekday, from: date) // Sunday = 1
        return (weekday + 5) % 7
    }

    /// Days from [other] to this day.
    func days(since other: Day) -> Int {
        Calendar.current.dateComponents([.day], from: other.date, to: date).day ?? 0
    }

    static func < (lhs: Day, rhs: Day) -> Bool {
        (lhs.year, lhs.month, lhs.day) < (rhs.year, rhs.month, rhs.day)
    }

    init(from decoder: Decoder) throws {
        let text = try decoder.singleValueContainer().decode(String.self)
        guard let parsed = Day(iso: text) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Fecha inválida: \(text)"))
        }
        self = parsed
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(iso)
    }
}

/// Timestamps from the server ("2026-10-21T23:10:47.009395+00:00") and the app.
enum Timestamp {
    private static let withFraction: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    private static let plain: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    static func parse(_ text: String) -> Date? {
        if let date = withFraction.date(from: text) ?? plain.date(from: text) { return date }
        // Microseconds: keep three decimals, which is what the formatter understands everywhere.
        guard let dot = text.firstIndex(of: ".") else { return nil }
        let afterDot = text[text.index(after: dot)...]
        let digits = afterDot.prefix { $0.isNumber }
        let rest = afterDot.dropFirst(digits.count)
        let trimmed = String(text[..<dot]) + "." + String(digits.prefix(3)).padding(toLength: 3, withPad: "0", startingAt: 0) + rest
        return withFraction.date(from: trimmed)
    }

    static func string(_ date: Date) -> String { withFraction.string(from: date) }
}

extension KeyedDecodingContainer {
    /// Reads a value, falling back to [fallback] when it is missing, null or of another type
    /// (the server and older backups may not have every field).
    func value<T: Decodable>(_ key: Key, _ fallback: T) -> T {
        (try? decodeIfPresent(T.self, forKey: key)) ?? fallback
    }

    func optional<T: Decodable>(_ key: Key) -> T? {
        try? decodeIfPresent(T.self, forKey: key)
    }
}
