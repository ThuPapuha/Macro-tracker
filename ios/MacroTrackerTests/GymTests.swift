import XCTest
@testable import MacroTracker

final class GymTests: XCTestCase {
    private func sets(_ pairs: (Int, Double)...) -> [WorkoutSet] { pairs.map { WorkoutSet(reps: $0.0, weight: $0.1) } }

    func testBestSetIsHeaviestThenMostReps() {
        let best = Gym.best(sets((10, 60), (6, 70), (8, 70), (12, 50)) + [WorkoutSet(reps: 3, weight: 90, done: false)])
        XCTAssertEqual(best, BestLift(weight: 70, reps: 8))
        XCTAssertEqual(best!.estimatedMax, 70 * (1 + 8.0 / 30), accuracy: 1e-9)
        XCTAssertEqual(Gym.volume(sets((10, 60), (6, 70), (8, 70), (12, 50))), 600 + 420 + 560 + 600, accuracy: 1e-9)
    }

    func testRecordsBeatEveryPreviousWorkout() {
        let history = [
            [ExerciseLog(name: "Sentadilla", sets: sets((8, 80), (8, 80))), ExerciseLog(name: "Dominadas", sets: sets((8, 0)))],
            [ExerciseLog(name: "sentadilla ", sets: sets((5, 85)))],
        ]
        let today = [
            ExerciseLog(name: "Sentadilla", sets: sets((6, 85), (8, 80))),
            ExerciseLog(name: "Dominadas", sets: sets((10, 0))),
            ExerciseLog(name: "Peso muerto", sets: sets((5, 100))),
            ExerciseLog(name: "Curl", sets: [WorkoutSet(reps: 10, weight: 12, done: false)]),
            ExerciseLog(name: "  ", sets: sets((5, 10))),
        ]
        let finished = Gym.finish(today, history: history)
        XCTAssertEqual(finished.exercises.map(\.name), ["Sentadilla", "Dominadas", "Peso muerto"])
        XCTAssertEqual(finished.exercises.map(\.pr), [true, true, false])
        XCTAssertEqual(finished.prCount, 2)
        XCTAssertFalse(Gym.finish([ExerciseLog(name: "Sentadilla", sets: sets((10, 80)))], history: history).exercises[0].pr)
    }

    func testRoutineKeepsLastLiftedValues() {
        let routine = [
            ExerciseLog(name: "Press banca", sets: sets((10, 60), (10, 60), (10, 60))),
            ExerciseLog(name: "Fondos", sets: sets((12, 0))),
        ]
        XCTAssertTrue(Gym.startFrom(routine).allSatisfy { $0.sets.allSatisfy { !$0.done } })
        let updated = Gym.updateRoutine(routine, performed: [ExerciseLog(name: "press banca", sets: sets((10, 62.5), (9, 62.5)))])
        XCTAssertEqual(updated[0].sets, sets((10, 62.5), (9, 62.5)))
        XCTAssertEqual(updated[0].name, "Press banca")
        XCTAssertEqual(updated[1], routine[1])
    }

    func testProgressAndDescriptions() {
        let workouts: [(String, [ExerciseLog])] = [
            ("2026-10-20", [ExerciseLog(name: "Remo", sets: sets((10, 50)))]),
            ("2026-10-10", [ExerciseLog(name: "Remo", sets: sets((10, 45))), ExerciseLog(name: "Otro", sets: sets((1, 1)))]),
            ("2026-10-15", [ExerciseLog(name: "Curl", sets: sets((10, 10)))]),
        ]
        let points = Gym.progress("remo", workouts)
        XCTAssertEqual(points.map(\.day), ["2026-10-10", "2026-10-20"])
        XCTAssertEqual(points.map(\.best.weight), [45, 50])
        XCTAssertEqual(Gym.describe(sets((10, 40), (10, 40), (10, 40))), "3 × 10 · 40 kg")
        XCTAssertEqual(Gym.describe(sets((10, 20), (8, 22.5))), "2 × 8-10 · 20-22.5 kg")
        XCTAssertEqual(Gym.describe(sets((12, 0))), "1 × 12")
        XCTAssertEqual(Gym.formatKg(82.5), "82.5")
        XCTAssertEqual(Gym.formatKg(80), "80")
    }

    func testPlatesAreKeptApartFromKilos() {
        let history = [[ExerciseLog(name: "Prensa", sets: sets((10, 100)))]]
        let first = Gym.finish([ExerciseLog(name: "Prensa", sets: sets((10, 5)), unit: .plates)], history: history)
        XCTAssertFalse(first.exercises[0].pr)
        let withPlates = history + [first.exercises]
        let second = Gym.finish([ExerciseLog(name: "prensa", sets: sets((10, 6)), unit: .plates)], history: withPlates)
        XCTAssertTrue(second.exercises[0].pr)
        XCTAssertEqual(Gym.bests(withPlates)[Gym.key("Prensa")], BestLift(weight: 100, reps: 10))
        XCTAssertEqual(Gym.bests(withPlates)[Gym.key("Prensa", .plates)], BestLift(weight: 5, reps: 10))
        XCTAssertEqual(Gym.totalVolume(withPlates.flatMap { $0 }), 1000, accuracy: 1e-9)
        XCTAssertEqual(Gym.describe(sets((10, 4), (10, 4), (10, 4)), unit: .plates), "3 × 10 · 4 discos")
        XCTAssertEqual(LoadUnit.plates.format(1), "1 disco")
        let updated = Gym.updateRoutine([ExerciseLog(name: "Prensa", sets: sets((10, 100)))], performed: second.exercises)
        XCTAssertEqual(updated[0].unit, .plates)
    }

    func testWeightGoalProgress() {
        let cut = WeightGoal(start: 90, goal: 80)
        XCTAssertEqual(cut.progress(85), 0.5, accuracy: 1e-9)
        XCTAssertEqual(cut.progress(92), 0, accuracy: 1e-9)
        XCTAssertEqual(cut.progress(79), 1, accuracy: 1e-9)
        XCTAssertEqual(cut.remaining(85), 5, accuracy: 1e-9)
        let bulk = WeightGoal(start: 60, goal: 66)
        XCTAssertFalse(bulk.losing)
        XCTAssertEqual(bulk.progress(63), 0.5, accuracy: 1e-9)
    }

    func testBodyCompositionTrends() {
        let logs = [
            BodyLog(day: "2026-10-22", kg: 80, fatPercent: 18, boneKg: 3.1),
            BodyLog(day: "2026-10-10", kg: 82, fatPercent: 20, musclePercent: 40),
            BodyLog(day: "2026-10-15", kg: 81),
        ]
        XCTAssertEqual(BodyStats.trend(logs, .fat)!.change!, -2, accuracy: 1e-9)
        XCTAssertNil(BodyStats.trend(logs, .muscle)!.change)
        XCTAssertEqual(BodyStats.series(logs, .weight).map(\.1), [82, 81, 80])
        XCTAssertEqual(BodyStats.chartable(logs), [.weight, .fat])
        XCTAssertEqual(logs[0].fatMassKg!, 14.4, accuracy: 1e-9)
    }
}
