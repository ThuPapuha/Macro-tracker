import Foundation

/// Uploads what group members see: each day's food totals (and, if allowed, the food names),
/// workouts, weigh-ins and the weight goal. Only once the user belongs to a group, so solo users never
/// create a server account.
@MainActor
final class SyncService {
    private let api: GroupAPI?
    private let settings: Settings
    private let food: FoodStore
    private let progress: ProgressStore
    private var pendingDays = Set<Day>()
    private var foodTask: Task<Void, Never>?
    private var progressTask: Task<Void, Never>?
    private var running = false

    init(api: GroupAPI?, settings: Settings, food: FoodStore, progress: ProgressStore) {
        self.api = api
        self.settings = settings
        self.food = food
        self.progress = progress
    }

    static func recentDays() -> [Day] { (0..<7).map { Day.today.adding(days: -$0) } }

    /// Uploads these days after a short pause (several edits in a row become one upload).
    func requestFood(_ days: Set<Day>) {
        guard api != nil else { return }
        pendingDays.formUnion(days)
        foodTask?.cancel()
        foodTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            guard let self, !Task.isCancelled else { return }
            let days = self.pendingDays
            self.pendingDays = []
            _ = await self.syncFood(days)
        }
    }

    func requestRecentFood() { requestFood(Set(Self.recentDays())) }

    func requestProgress() {
        guard api != nil else { return }
        progressTask?.cancel()
        progressTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            guard let self, !Task.isCancelled else { return }
            _ = await self.syncProgress()
        }
    }

    /// Uploads the given days right away. Returns false if the upload failed.
    @discardableResult
    func syncFood(_ days: Set<Day>) async -> Bool {
        guard let api else { return false }
        guard settings.hasGroups else { return true }
        do {
            try await ensureName(api)
            for day in days.sorted() {
                let entries = food.entries(on: day)
                let meals = settings.shareMeals ? entries.map { SharedMeal(name: $0.name, calories: $0.calories, meal: $0.meal.rawValue) } : []
                try await api.upsertSummary(day: day, totals: Macros.sum(entries.map(\.macros)), targets: settings.targets, meals: meals)
            }
            return true
        } catch {
            return false
        }
    }

    /// Uploads the weight goal, weigh-ins and workouts not uploaded yet.
    @discardableResult
    func syncProgress() async -> Bool {
        guard let api else { return false }
        guard settings.hasGroups, !running else { return true }
        running = true
        defer { running = false }
        do {
            try await ensureName(api)
            if !settings.weightGoalSynced {
                let goal = settings.weightGoal
                let share = settings.shareWeight
                try await api.setWeightGoal(goal: goal?.goal, start: goal?.start, share: share)
                settings.markWeightGoalSynced(goal, share: share)
            }
            for weight in progress.pendingWeights {
                try await skipIfRejected {
                    if weight.deleted {
                        try await api.deleteWeight(day: weight.date.iso)
                    } else {
                        try await api.upsertWeight(day: weight.date.iso, kg: weight.kg, fat: weight.fatPercent,
                                                   muscle: weight.musclePercent, bone: weight.boneKg)
                    }
                }
                progress.markSynced(weight)
            }
            for workout in progress.pendingWorkouts {
                try await skipIfRejected {
                    if workout.deleted {
                        try await api.deleteWorkout(workout.id)
                    } else {
                        let performedAt = Timestamp.string(Date(timeIntervalSince1970: Double(workout.finishedAt) / 1000))
                        try await api.upsertWorkout(id: workout.id, day: workout.date.iso, title: workout.title,
                                                    exercises: workout.exercises, prCount: workout.prCount,
                                                    durationMin: workout.durationMin, performedAt: performedAt,
                                                    category: workout.section)
                    }
                }
                progress.markSynced(workout)
            }
            return true
        } catch {
            return false
        }
    }

    /// Uploads the display name if it changed while offline.
    func ensureName(_ api: GroupAPI) async throws {
        let name = settings.displayName
        guard !settings.displayNameSynced, !name.isEmpty else { return }
        try await api.setDisplayName(name)
        settings.setDisplayName(name, synced: true)
    }

    /// The server refused this item (e.g. a date too old): retrying it forever would block the rest.
    private func skipIfRejected(_ upload: () async throws -> Void) async throws {
        do {
            try await upload()
        } catch let error as BackendError where error.status == 400 {
            // Skipped.
        }
    }
}
