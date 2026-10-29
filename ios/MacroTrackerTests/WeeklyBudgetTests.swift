import XCTest
@testable import MacroTracker

final class WeeklyBudgetTests: XCTestCase {
    private let goal = MacroTargets(calories: 2000, protein: 150, carbs: 200, fat: 60)
    // Monday 19 to Sunday 25 of October 2026.
    private let monday = Day(year: 2026, month: 10, day: 19)

    private func day(_ offset: Int, _ calories: Double, entries: Int = 3) -> DayIntake {
        DayIntake(date: monday.adding(days: offset), totals: Macros(calories: calories, protein: calories / 20), entries: entries)
    }

    func testUnusedCaloriesBecomeMargin() {
        let intakes = [day(0, 1700), day(1, 2100), day(2, 500), day(4, 800)]
        let week = WeeklyBudget.summarize(intakes, daily: goal, date: monday.adding(days: 4), today: monday.adding(days: 4))
        XCTAssertEqual(week.start, monday)
        XCTAssertEqual(week.days.prefix(4).map(\.status), [.counted, .counted, .incomplete, .notLogged])
        XCTAssertEqual(week.margin, 200, accuracy: 1e-9)
        XCTAssertEqual(week.daysLeft, 3)
        XCTAssertEqual(week.todayWithMargin, 2200, accuracy: 1e-9)
        XCTAssertEqual(week.paceForRest!, 2000 + 200.0 / 3, accuracy: 1e-9)
        XCTAssertEqual(week.todayCalories, 800, accuracy: 1e-9)
        XCTAssertEqual(week.weekly.calories, 14000, accuracy: 1e-9)
        XCTAssertFalse(week.isOver)
    }

    func testOvereatingAndFinishedWeeks() {
        let intakes = (0...6).map { day($0, 2300) }
        let week = WeeklyBudget.summarize(intakes, daily: goal, date: monday, today: monday.adding(days: 10))
        XCTAssertTrue(week.isOver)
        XCTAssertEqual(week.margin, -2100, accuracy: 1e-9)
        XCTAssertNil(week.paceForRest)
        let early = WeeklyBudget.summarize([day(0, 9000)], daily: goal, date: monday, today: monday.adding(days: 1))
        XCTAssertEqual(early.todayWithMargin, 0, accuracy: 1e-9)
    }

    func testWeeksStartOnMonday() {
        XCTAssertEqual(WeeklyBudget.weekStart(Day(year: 2026, month: 10, day: 25)), monday)
        XCTAssertEqual(WeeklyBudget.weekStart(monday), monday)
        let weeks = WeeklyBudget.lastWeeks([day(-3, 1500)], daily: goal, today: monday.adding(days: 2), count: 2)
        XCTAssertEqual(weeks.map(\.start), [monday, monday.adding(days: -7)])
        XCTAssertEqual(weeks[1].margin, 500, accuracy: 1e-9)
    }
}
