import Foundation
import Observation

/// A user-made group of routines, like "Hipertrofia" or "Fuerza".
struct RoutineSection: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var position: Int = 0
}

struct Routine: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var exercises: [ExerciseLog]
    var position: Int = 0
    var sectionId: String? = nil
}

/// A finished workout. [synced]/[deleted] track what still has to reach the groups' server.
struct Workout: Codable, Identifiable, Hashable {
    var id: String
    var date: Day
    var title: String
    var routineId: String?
    var exercises: [ExerciseLog]
    var prCount: Int
    var durationMin: Int?
    /// Epoch milliseconds.
    var finishedAt: Int64
    var section: String? = nil
    var updatedAt: Int64
    var synced = false
    var deleted = false
}

/// A day's weigh-in; body fat and muscle in %, bone mass in kg, when measured.
struct WeightEntry: Codable, Identifiable, Hashable {
    var date: Day
    var kg: Double
    var fatPercent: Double? = nil
    var musclePercent: Double? = nil
    var boneKg: Double? = nil
    var updatedAt: Int64 = nowMillis()
    var synced = false
    var deleted = false

    var id: String { date.iso }
    var log: BodyLog { BodyLog(day: date.iso, kg: kg, fatPercent: fatPercent, musclePercent: musclePercent, boneKg: boneKg) }
}

/// A workout in progress, kept so closing the app at the gym does not lose it.
struct ActiveWorkout: Codable {
    var title: String
    var routineId: String?
    var date: Day
    /// Epoch milliseconds.
    var startedAt: Int64
    var exercises: [ExerciseLog]
    var section: String? = nil
}

func nowMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

private struct ProgressData: Codable {
    var sections: [RoutineSection] = []
    var routines: [Routine] = []
    var workouts: [Workout] = []
    var weights: [WeightEntry] = []
}

/// Gym and body data: sections, routines, workouts and weigh-ins.
@MainActor
@Observable
final class ProgressStore {
    private static let file = "progress.json"
    private static let draftFile = "active-workout.json"

    private var data: ProgressData
    private(set) var activeWorkout: ActiveWorkout?

    /// Called after anything changes (for syncing and backups).
    @ObservationIgnored var onChange: (() -> Void)?

    init() {
        data = FileStore.load(ProgressData.self, Self.file) ?? ProgressData()
        activeWorkout = FileStore.load(ActiveWorkout.self, Self.draftFile)
    }

    var sections: [RoutineSection] { data.sections.sorted { ($0.position, $0.name) < ($1.position, $1.name) } }
    var routines: [Routine] { data.routines.sorted { ($0.position, $0.name) < ($1.position, $1.name) } }
    /// Newest first.
    var workouts: [Workout] {
        data.workouts.filter { !$0.deleted }.sorted { ($0.date, $0.finishedAt) > ($1.date, $1.finishedAt) }
    }
    /// Oldest first.
    var weights: [WeightEntry] { data.weights.filter { !$0.deleted }.sorted { $0.date < $1.date } }

    func routine(_ id: String) -> Routine? { data.routines.first { $0.id == id } }
    func sectionName(_ id: String?) -> String? { id.flatMap { id in data.sections.first { $0.id == id }?.name } }

    /// Exercises of every saved workout, oldest first (to find personal records).
    var history: [[ExerciseLog]] {
        data.workouts.filter { !$0.deleted }.sorted { $0.finishedAt < $1.finishedAt }.map(\.exercises)
    }

    private func persist() {
        FileStore.save(data, Self.file)
        onChange?()
    }

    // Sections and routines.

    /// Creates a section, or renames it when [id] is given. Returns its id.
    @discardableResult
    func saveSection(name: String, id: String? = nil) -> String {
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if let id, let index = data.sections.firstIndex(where: { $0.id == id }) {
            data.sections[index].name = clean
            persist()
            return id
        }
        let section = RoutineSection(id: UUID().uuidString.lowercased(), name: clean, position: data.sections.count)
        data.sections.append(section)
        persist()
        return section.id
    }

    /// Deletes a section; its routines stay, without a section.
    func deleteSection(_ id: String) {
        data.sections.removeAll { $0.id == id }
        for index in data.routines.indices where data.routines[index].sectionId == id {
            data.routines[index].sectionId = nil
        }
        persist()
    }

    func saveRoutine(_ routine: Routine) {
        var copy = routine
        copy.name = routine.name.trimmingCharacters(in: .whitespacesAndNewlines)
        if let index = data.routines.firstIndex(where: { $0.id == routine.id }) {
            data.routines[index] = copy
        } else {
            copy.position = data.routines.count
            data.routines.append(copy)
        }
        persist()
    }

    func deleteRoutine(_ id: String) {
        data.routines.removeAll { $0.id == id }
        persist()
    }

    // Workouts.

    func setActiveWorkout(_ workout: ActiveWorkout?) {
        activeWorkout = workout
        if let workout {
            FileStore.save(workout, Self.draftFile)
        } else {
            FileStore.remove(Self.draftFile)
        }
    }

    /// Saves a finished workout: marks personal records against every earlier workout and, if it came
    /// from a routine, stores the reps and weights just lifted as the routine's new targets.
    /// Returns nil if nothing was done.
    func finishWorkout(title: String, routineId: String?, exercises: [ExerciseLog], date: Day, startedAt: Int64,
                       section: String?) -> Workout? {
        let finished = Gym.finish(exercises, history: history)
        guard !finished.exercises.isEmpty else { return nil }
        let now = nowMillis()
        let minutes = Int((now - startedAt) / 60_000)
        let cleanTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanSection = section?.trimmingCharacters(in: .whitespacesAndNewlines)
        let workout = Workout(
            id: UUID().uuidString.lowercased(),
            date: date,
            title: cleanTitle.isEmpty ? "Entrenamiento" : cleanTitle,
            routineId: routineId,
            exercises: finished.exercises,
            prCount: finished.prCount,
            durationMin: startedAt > 0 && (1...600).contains(minutes) ? minutes : nil,
            finishedAt: now,
            section: cleanSection?.isEmpty == false ? cleanSection : nil,
            updatedAt: now
        )
        data.workouts.append(workout)
        if let routineId, let index = data.routines.firstIndex(where: { $0.id == routineId }) {
            data.routines[index].exercises = Gym.updateRoutine(data.routines[index].exercises, performed: finished.exercises)
        }
        persist()
        return workout
    }

    func deleteWorkout(_ id: String) {
        guard let index = data.workouts.firstIndex(where: { $0.id == id }) else { return }
        data.workouts[index].deleted = true
        data.workouts[index].synced = false
        data.workouts[index].updatedAt = nowMillis()
        persist()
    }

    // Weigh-ins.

    /// Saves the day's weigh-in, replacing what was there (also the body composition).
    func logWeight(_ entry: WeightEntry) {
        var copy = entry
        copy.updatedAt = nowMillis()
        copy.synced = false
        copy.deleted = false
        data.weights.removeAll { $0.date == entry.date }
        data.weights.append(copy)
        persist()
    }

    func deleteWeight(_ day: Day) {
        guard let index = data.weights.firstIndex(where: { $0.date == day }) else { return }
        data.weights[index].deleted = true
        data.weights[index].synced = false
        data.weights[index].updatedAt = nowMillis()
        persist()
    }

    // Syncing with the groups' server.

    var pendingWeights: [WeightEntry] { data.weights.filter { !$0.synced }.sorted { $0.date < $1.date } }
    var pendingWorkouts: [Workout] { data.workouts.filter { !$0.synced }.sorted { $0.finishedAt < $1.finishedAt } }

    /// Marks it uploaded (or forgets it, if it was a deletion), unless it changed while uploading.
    func markSynced(_ weight: WeightEntry) {
        guard let index = data.weights.firstIndex(where: { $0.date == weight.date }),
              data.weights[index].updatedAt == weight.updatedAt else { return }
        if weight.deleted { data.weights.remove(at: index) } else { data.weights[index].synced = true }
        FileStore.save(data, Self.file)
    }

    func markSynced(_ workout: Workout) {
        guard let index = data.workouts.firstIndex(where: { $0.id == workout.id }),
              data.workouts[index].updatedAt == workout.updatedAt else { return }
        if workout.deleted { data.workouts.remove(at: index) } else { data.workouts[index].synced = true }
        FileStore.save(data, Self.file)
    }

    /// Everything goes up again (e.g. after joining with a new account).
    func markAllUnsynced() {
        for index in data.weights.indices { data.weights[index].synced = false }
        for index in data.workouts.indices { data.workouts[index].synced = false }
        data.weights.removeAll { $0.deleted }
        data.workouts.removeAll { $0.deleted }
        persist()
    }

    // Backups.

    var backupSections: [BackupSection] { data.sections.map { BackupSection(id: $0.id, name: $0.name, position: $0.position) } }

    var backupRoutines: [BackupRoutine] {
        data.routines.map { BackupRoutine(id: $0.id, name: $0.name, exercises: $0.exercises, position: $0.position, sectionId: $0.sectionId) }
    }

    var backupWorkouts: [BackupWorkout] {
        data.workouts.filter { !$0.deleted }.map {
            BackupWorkout(id: $0.id, date: $0.date.iso, title: $0.title, routineId: $0.routineId, exercises: $0.exercises,
                          prCount: $0.prCount, durationMin: $0.durationMin, finishedAt: $0.finishedAt, section: $0.section)
        }
    }

    var backupWeights: [BackupWeight] {
        weights.map { BackupWeight(date: $0.date.iso, kg: $0.kg, fatPercent: $0.fatPercent, musclePercent: $0.musclePercent, boneKg: $0.boneKg) }
    }

    /// Adds what is missing; returns how many workouts and weigh-ins were added.
    func importBackup(_ file: BackupFile) -> (workouts: Int, weights: Int) {
        for section in file.sections where !data.sections.contains(where: { $0.id == section.id }) {
            data.sections.append(RoutineSection(id: section.id, name: section.name, position: section.position))
        }
        let sectionIds = Set(data.sections.map(\.id))
        for routine in file.routines where !data.routines.contains(where: { $0.id == routine.id }) {
            // A routine whose section is not in the file ends up without a section.
            let sectionId = routine.sectionId.flatMap { sectionIds.contains($0) ? $0 : nil }
            data.routines.append(Routine(id: routine.id, name: routine.name, exercises: routine.exercises,
                                         position: routine.position, sectionId: sectionId))
        }
        var addedWorkouts = 0
        for workout in file.workouts where !data.workouts.contains(where: { $0.id == workout.id }) {
            guard let day = Day(iso: workout.date) else { continue }
            data.workouts.append(Workout(id: workout.id, date: day, title: workout.title, routineId: workout.routineId,
                                         exercises: workout.exercises, prCount: workout.prCount, durationMin: workout.durationMin,
                                         finishedAt: workout.finishedAt, section: workout.section, updatedAt: nowMillis()))
            addedWorkouts += 1
        }
        var known = Set(data.weights.filter { !$0.deleted }.map(\.date))
        var addedWeights = 0
        for weight in file.weights {
            guard let day = Day(iso: weight.date), known.insert(day).inserted else { continue }
            data.weights.removeAll { $0.date == day }
            data.weights.append(WeightEntry(date: day, kg: weight.kg, fatPercent: weight.fatPercent,
                                            musclePercent: weight.musclePercent, boneKg: weight.boneKg))
            addedWeights += 1
        }
        persist()
        return (addedWorkouts, addedWeights)
    }
}
