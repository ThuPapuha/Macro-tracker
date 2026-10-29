import Foundation

/// How an exercise's load is counted: kilos, or number of plates (machines with a stack, plate
/// loaded machines…) when the kilos are not known.
enum LoadUnit: String, Codable, CaseIterable {
    case kg
    case plates

    var label: String { self == .kg ? "kg" : "discos" }

    /// "80 kg", "1 disco", "4 discos".
    func format(_ value: Double) -> String {
        switch self {
        case .kg: return "\(Gym.formatKg(value)) kg"
        case .plates: return value == 1 ? "1 disco" : "\(Gym.formatKg(value)) discos"
        }
    }

    init(from decoder: Decoder) throws {
        // Unknown values (a newer app) fall back to kilos.
        self = LoadUnit(rawValue: (try? decoder.singleValueContainer().decode(String.self)) ?? "") ?? .kg
    }
}

/// One set: [weight] in the exercise's unit (0 for bodyweight exercises).
struct WorkoutSet: Codable, Hashable {
    var reps: Int = 0
    var weight: Double = 0
    var done: Bool = true

    init(reps: Int = 0, weight: Double = 0, done: Bool = true) {
        self.reps = reps
        self.weight = weight
        self.done = done
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        reps = c.value(.reps, 0)
        weight = c.value(.weight, 0)
        done = c.value(.done, true)
    }
}

/// An exercise as defined in a routine (its current target sets) or as performed in a workout.
/// [pr] is only meaningful in a finished workout.
struct ExerciseLog: Codable, Hashable {
    var name: String
    var sets: [WorkoutSet] = []
    var pr: Bool = false
    var note: String = ""
    var unit: LoadUnit = .kg

    init(name: String, sets: [WorkoutSet] = [], pr: Bool = false, note: String = "", unit: LoadUnit = .kg) {
        self.name = name
        self.sets = sets
        self.pr = pr
        self.note = note
        self.unit = unit
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decode(String.self, forKey: .name)
        sets = try c.decodeIfPresent([WorkoutSet].self, forKey: .sets) ?? []
        pr = c.value(.pr, false)
        note = c.value(.note, "")
        unit = c.value(.unit, .kg)
    }
}

/// Heaviest set of an exercise; at equal weight, the one with more reps.
struct BestLift: Hashable {
    let weight: Double
    let reps: Int
    var estimatedMax: Double { Gym.estimatedMax(weight: weight, reps: reps) }
}

/// One point of an exercise's progress chart.
struct ExercisePoint: Hashable {
    let day: String
    let best: BestLift
    let volume: Double
}

struct FinishedWorkout {
    let exercises: [ExerciseLog]
    let prCount: Int
}

enum Gym {
    private static let epsilon = 1e-6

    /// Epley's one-rep max estimate.
    static func estimatedMax(weight: Double, reps: Int) -> Double {
        if reps <= 0 || weight <= 0 { return 0 }
        if reps == 1 { return weight }
        return weight * (1 + Double(reps) / 30)
    }

    static func completedSets(_ sets: [WorkoutSet]) -> [WorkoutSet] { sets.filter { $0.done && $0.reps > 0 } }

    static func best(_ sets: [WorkoutSet]) -> BestLift? {
        completedSets(sets)
            .max { a, b in a.weight != b.weight ? a.weight < b.weight : a.reps < b.reps }
            .map { BestLift(weight: $0.weight, reps: $0.reps) }
    }

    /// Reps × load of the completed sets (kilos moved when the load is in kg).
    static func volume(_ sets: [WorkoutSet]) -> Double {
        completedSets(sets).reduce(0) { $0 + Double($1.reps) * $1.weight }
    }

    /// Kilos moved; exercises counted in plates are left out since their kilos are unknown.
    static func totalVolume(_ exercises: [ExerciseLog]) -> Double {
        exercises.filter { $0.unit == .kg }.reduce(0) { $0 + volume($1.sets) }
    }

    static func completedSetCount(_ exercises: [ExerciseLog]) -> Int {
        exercises.reduce(0) { $0 + completedSets($1.sets).count }
    }

    /// More weight than ever, or the same top weight for more reps.
    static func isRecord(_ current: BestLift?, _ previous: BestLift?) -> Bool {
        guard let current, let previous else { return false }
        return current.weight > previous.weight + epsilon ||
            (abs(current.weight - previous.weight) <= epsilon && current.reps > previous.reps)
    }

    /// Exercises are matched by name, ignoring case and surrounding spaces. The same exercise counted
    /// in plates is kept apart, since plates and kilos cannot be compared.
    static func key(_ name: String, _ unit: LoadUnit = .kg) -> String {
        name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() + (unit == .plates ? "#discos" : "")
    }

    static func key(_ exercise: ExerciseLog) -> String { key(exercise.name, exercise.unit) }

    /// Best lift ever per exercise in [history].
    static func bests(_ history: [[ExerciseLog]]) -> [String: BestLift] {
        var result: [String: BestLift] = [:]
        for workout in history {
            for exercise in workout {
                guard let best = best(exercise.sets) else { continue }
                let key = key(exercise)
                if let known = result[key], !isRecord(best, known) { continue }
                result[key] = best
            }
        }
        return result
    }

    /// Drops empty exercises and sets that were not done, and marks the exercises that beat every
    /// previous workout in [history]. The first time an exercise is done does not count as a record.
    static func finish(_ exercises: [ExerciseLog], history: [[ExerciseLog]]) -> FinishedWorkout {
        let previous = bests(history)
        let cleaned = exercises
            .map { e -> ExerciseLog in
                var copy = e
                copy.name = e.name.trimmingCharacters(in: .whitespacesAndNewlines)
                copy.sets = completedSets(e.sets)
                return copy
            }
            .filter { !$0.name.isEmpty && !$0.sets.isEmpty }
            .map { e -> ExerciseLog in
                var copy = e
                copy.pr = isRecord(best(e.sets), previous[key(e)])
                return copy
            }
        return FinishedWorkout(exercises: cleaned, prCount: cleaned.filter(\.pr).count)
    }

    /// After a workout, the routine keeps the reps and weights just lifted (and the unit used) as its
    /// new targets. Exercises skipped in the workout keep their previous values.
    static func updateRoutine(_ routine: [ExerciseLog], performed: [ExerciseLog]) -> [ExerciseLog] {
        var byName: [String: ExerciseLog] = [:]
        for exercise in performed { byName[key(exercise.name)] = exercise }
        return routine.map { exercise in
            guard let match = byName[key(exercise.name)] else { return exercise }
            let done = completedSets(match.sets)
            if done.isEmpty { return exercise }
            var copy = exercise
            copy.sets = done.map { WorkoutSet(reps: $0.reps, weight: $0.weight, done: true) }
            copy.unit = match.unit
            return copy
        }
    }

    /// Sets to start a workout from a routine: same targets, not done yet.
    static func startFrom(_ routine: [ExerciseLog]) -> [ExerciseLog] {
        routine.map { exercise in
            var copy = exercise
            copy.pr = false
            copy.sets = exercise.sets.map { WorkoutSet(reps: $0.reps, weight: $0.weight, done: false) }
            return copy
        }
    }

    /// Progress of one exercise across workouts given as (day, exercises), oldest first.
    static func progress(_ name: String, _ workouts: [(String, [ExerciseLog])], unit: LoadUnit = .kg) -> [ExercisePoint] {
        let wanted = key(name, unit)
        return workouts
            .compactMap { day, exercises -> ExercisePoint? in
                let sets = exercises.filter { key($0) == wanted }.flatMap(\.sets)
                guard let best = best(sets) else { return nil }
                return ExercisePoint(day: day, best: best, volume: volume(sets))
            }
            .sorted { $0.day < $1.day }
    }

    /// "3 × 10 · 40 kg", "3 × 8-10 · 20-25 kg", "3 × 10 · 4 discos", "3 × 12" for bodyweight.
    static func describe(_ sets: [WorkoutSet], unit: LoadUnit = .kg) -> String {
        if sets.isEmpty { return "Sin series" }
        let reps = sets.map(\.reps)
        let repText = Set(reps).count == 1 ? "\(reps[0])" : "\(reps.min()!)-\(reps.max()!)"
        let weights = sets.map(\.weight).filter { $0 > 0 }
        let weightText: String
        if weights.isEmpty {
            weightText = ""
        } else if Set(weights).count == 1 {
            weightText = " · \(unit.format(weights[0]))"
        } else {
            weightText = " · \(formatKg(weights.min()!))-\(unit.format(weights.max()!))"
        }
        return "\(sets.count) × \(repText)\(weightText)"
    }

    /// 80 -> "80", 82.5 -> "82.5", 1.25 -> "1.25".
    static func formatKg(_ value: Double) -> String {
        let rounded = (value * 100).rounded() / 100
        if rounded == rounded.rounded(.down) { return String(Int64(rounded)) }
        var text = String(rounded)
        while text.hasSuffix("0") { text.removeLast() }
        return text
    }
}

/// Body weight goal: [start] and [goal] in kg.
struct WeightGoal: Codable, Hashable {
    var start: Double
    var goal: Double

    var losing: Bool { goal < start }

    /// Fraction of the way from [start] to [goal] (0…1); moving away from the goal counts as 0.
    func progress(_ current: Double) -> Double {
        let total = goal - start
        if abs(total) < 1e-6 { return abs(current - goal) < 0.25 ? 1 : 0 }
        return min(max((current - start) / total, 0), 1)
    }

    /// Kilos still to lose (or gain) to reach the goal; 0 once reached.
    func remaining(_ current: Double) -> Double {
        losing ? max(current - goal, 0) : max(goal - current, 0)
    }
}
