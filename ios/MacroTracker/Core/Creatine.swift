import Foundation

/// Visual estimate of the extra muscle creatine from logged daily use, not a measured body level.
/// Dose changes the illustrative time to the display maximum; without use the extra level fades
/// over about six weeks. Only the last 90 days matter to either transition.
enum Creatine {
    static let doses = [3, 5, 10, 20]

    static func daysToFull(for doseGrams: Int) -> Double {
        switch doseGrams {
        case 3: 35
        case 10: 18
        case 20: 6
        default: 28
        }
    }

    static func estimatedLevel(takenDays: Set<Day>, dosesByDay: [Day: Int] = [:], on today: Day) -> Double {
        var level = 0.0
        for offset in stride(from: 89, through: 0, by: -1) {
            let day = today.adding(days: -offset)
            level = if takenDays.contains(day) {
                min(1, level + 1.0 / daysToFull(for: dosesByDay[day] ?? 5))
            } else {
                max(0, level - 1.0 / 42)
            }
        }
        return level
    }
}
