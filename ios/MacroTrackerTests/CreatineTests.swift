import XCTest
@testable import MacroTracker

final class CreatineTests: XCTestCase {
    private let today = Day(year: 2026, month: 11, day: 6)

    func testDailyUseBuildsGradually() {
        let days = Set((0..<28).map { today.adding(days: -$0) })
        XCTAssertEqual(Creatine.estimatedLevel(takenDays: days, on: today), 1, accuracy: 0.0001)
        XCTAssertLessThan(Creatine.estimatedLevel(takenDays: [today], on: today), 0.04)
    }

    func testMissedDaysLowerTheEstimate() {
        let days = Set((42..<70).map { today.adding(days: -$0) })
        XCTAssertEqual(Creatine.estimatedLevel(takenDays: days, on: today), 0, accuracy: 0.0001)
        let earlier = Set((1...28).map { today.adding(days: -$0) })
        XCTAssertLessThan(Creatine.estimatedLevel(takenDays: earlier, on: today),
                          Creatine.estimatedLevel(takenDays: earlier, on: today.adding(days: -1)))
    }

    func testConfiguredDoseChangesTheIllustrativeRate() {
        let days = Set((0..<6).map { today.adding(days: -$0) })
        let at20 = Dictionary(uniqueKeysWithValues: days.map { ($0, 20) })
        let at3 = Dictionary(uniqueKeysWithValues: days.map { ($0, 3) })
        let at10 = Dictionary(uniqueKeysWithValues: days.map { ($0, 10) })
        XCTAssertEqual(Creatine.estimatedLevel(takenDays: days, dosesByDay: at20, on: today), 1, accuracy: 0.0001)
        XCTAssertLessThan(Creatine.estimatedLevel(takenDays: days, dosesByDay: at3, on: today),
                          Creatine.estimatedLevel(takenDays: days, on: today))
        XCTAssertLessThan(Creatine.estimatedLevel(takenDays: days, on: today),
                          Creatine.estimatedLevel(takenDays: days, dosesByDay: at10, on: today))
    }
}
