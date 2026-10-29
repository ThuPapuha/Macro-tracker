import Foundation

let spanish = Locale(identifier: "es")

/// 1234.4 -> "1234", 12.25 -> "12,3", 12.0 -> "12".
func formatAmount(_ value: Double) -> String {
    if abs(value) >= 100 || value == value.rounded(.down) {
        return String(Int(value.rounded()))
    }
    return String(format: "%.1f", locale: spanish, value)
}

/// Value for an editable field: no thousands separators, dot decimals.
func inputAmount(_ value: Double) -> String {
    let rounded = (value * 10).rounded() / 10
    return rounded == rounded.rounded(.down) ? String(Int64(rounded)) : String(rounded)
}

/// Accepts "12,5" or "12.5"; nil for empty, invalid or negative text.
func toAmount(_ text: String) -> Double? {
    guard let value = Double(text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")),
          value >= 0, value.isFinite else { return nil }
    return value
}

private let dayFormatter: DateFormatter = {
    let formatter = DateFormatter()
    formatter.locale = spanish
    formatter.dateFormat = "EEE d 'de' MMM"
    return formatter
}()

private let shortFormatter: DateFormatter = {
    let formatter = DateFormatter()
    formatter.locale = spanish
    formatter.dateFormat = "d MMM"
    return formatter
}()

private let timeFormatter: DateFormatter = {
    let formatter = DateFormatter()
    formatter.locale = spanish
    formatter.dateFormat = "d MMM, HH:mm"
    return formatter
}()

func dayLabel(_ day: Day, today: Day = .today) -> String {
    if day == today { return "Hoy" }
    if day == today.adding(days: -1) { return "Ayer" }
    if day == today.adding(days: 1) { return "Mañana" }
    let text = dayFormatter.string(from: day.date)
    return text.prefix(1).uppercased() + text.dropFirst()
}

/// "20 oct"
func shortDate(_ day: Day) -> String { shortFormatter.string(from: day.date) }

/// "20 oct, 18:30"
func dateTime(_ date: Date) -> String { timeFormatter.string(from: date) }

/// "ahora", "hace 5 min", "hace 3 h", "ayer", "hace 4 d" or a date.
func timeAgo(_ iso: String, now: Date = Date()) -> String {
    guard let then = Timestamp.parse(iso) else { return "" }
    let minutes = max(Int(now.timeIntervalSince(then) / 60), 0)
    switch minutes {
    case ..<1: return "ahora"
    case ..<60: return "hace \(minutes) min"
    case ..<(24 * 60): return "hace \(minutes / 60) h"
    case ..<(48 * 60): return "ayer"
    case ..<(7 * 24 * 60): return "hace \(minutes / (24 * 60)) d"
    default: return shortFormatter.string(from: then)
    }
}

/// "P 30 · C 45 · G 12"
func macroLine(_ macros: Macros) -> String {
    "P \(formatAmount(macros.protein)) · C \(formatAmount(macros.carbs)) · G \(formatAmount(macros.fat))"
}

func kcal(_ value: Double) -> String { "\(formatAmount(value)) kcal" }
