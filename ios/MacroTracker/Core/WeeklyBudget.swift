import Foundation

/// What was eaten on a day; [entries] is how many foods were logged (0 = nothing logged).
struct DayIntake {
    let date: Day
    let totals: Macros
    let entries: Int
}

/// How a past day counts for the weekly margin.
enum DayStatus {
    /// Logged: its difference with the goal goes to the margin.
    case counted
    /// Nothing logged: counts as if the goal was met.
    case notLogged
    /// So few calories that the log is probably incomplete: counts as if the goal was met.
    case incomplete
    /// Today or later: not settled yet.
    case pending
}

struct WeekDay {
    let date: Day
    let totals: Macros
    let status: DayStatus
}

/// The week's budget (the daily goal × 7, Monday to Sunday). Calories not eaten on past days build a
/// margin that can be used on the days left; eating more than the goal takes from it.
struct WeekSummary {
    let start: Day
    let today: Day
    /// Daily goals.
    let daily: MacroTargets
    let days: [WeekDay]

    var end: Day { start.adding(days: 6) }

    /// The daily goals × 7.
    var weekly: MacroTargets {
        MacroTargets(calories: daily.calories * 7, protein: daily.protein * 7, carbs: daily.carbs * 7, fat: daily.fat * 7)
    }

    /// Everything eaten this week so far, today included.
    var consumed: Macros { Macros.sum(days.map(\.totals)) }

    /// Calories left unused on past days (positive) or eaten over the goal (negative).
    var margin: Double {
        days.filter { $0.status == .counted }.reduce(0) { $0 + daily.calories - $1.totals.calories }
    }

    /// Days from today to Sunday (0 once the week is over).
    var daysLeft: Int { days.filter { $0.status == .pending }.count }

    var skippedDays: [WeekDay] { days.filter { $0.status == .incomplete } }

    var isOver: Bool { today > end }

    /// Today's goal if all the margin is used today (never below zero).
    var todayWithMargin: Double { max(daily.calories + margin, 0) }

    /// Daily calories for the days left that would meet the weekly budget exactly.
    var paceForRest: Double? {
        daysLeft == 0 ? nil : max(daily.calories + margin / Double(daysLeft), 0)
    }

    /// Calories eaten on [today].
    var todayCalories: Double { days.first { $0.date == today }?.totals.calories ?? 0 }
}

enum WeeklyBudget {
    /// A logged day under this share of the goal is treated as an incomplete log.
    static let incompleteShare = 0.35

    static func weekStart(_ date: Day) -> Day { date.adding(days: -date.weekdayIndex) }

    /// Builds the summary of the week containing [date]. [today] decides which days are settled.
    static func summarize(_ intakes: [DayIntake], daily: MacroTargets, date: Day, today: Day) -> WeekSummary {
        let start = weekStart(date)
        var byDate: [Day: DayIntake] = [:]
        for intake in intakes { byDate[intake.date] = intake }
        let days = (0..<7).map { offset -> WeekDay in
            let day = start.adding(days: offset)
            let intake = byDate[day]
            let totals = intake?.totals ?? .zero
            let status: DayStatus
            if day >= today {
                status = .pending
            } else if intake == nil || intake!.entries == 0 {
                status = .notLogged
            } else if daily.calories > 0 && totals.calories < daily.calories * incompleteShare {
                status = .incomplete
            } else {
                status = .counted
            }
            return WeekDay(date: day, totals: totals, status: status)
        }
        return WeekSummary(start: start, today: today, daily: daily, days: days)
    }

    /// Summaries of the [count] weeks up to the one containing [today], newest first.
    static func lastWeeks(_ intakes: [DayIntake], daily: MacroTargets, today: Day, count: Int) -> [WeekSummary] {
        (0..<count).map { summarize(intakes, daily: daily, date: weekStart(today).adding(days: -7 * $0), today: today) }
    }
}
