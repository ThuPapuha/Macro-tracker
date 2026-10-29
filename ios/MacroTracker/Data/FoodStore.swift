import Foundation
import Observation

struct FoodEntry: Codable, Identifiable, Hashable {
    var id = UUID()
    var date: Day
    var meal: MealType
    var name: String
    var quantity: String = ""
    var calories: Double = 0
    var protein: Double = 0
    var carbs: Double = 0
    var fat: Double = 0
    /// "manual", "ai" or "recent".
    var source: String = "manual"
    /// Epoch milliseconds.
    var createdAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)

    var macros: Macros { Macros(calories: calories, protein: protein, carbs: carbs, fat: fat) }
}

/// The food log. It only lives on the phone (and in backups); groups get daily totals.
@MainActor
@Observable
final class FoodStore {
    private static let file = "food.json"

    private(set) var entries: [FoodEntry]

    /// Days whose entries just changed (for syncing and backups).
    @ObservationIgnored var onChange: ((Set<Day>) -> Void)?

    init() {
        entries = FileStore.load([FoodEntry].self, Self.file) ?? []
    }

    func entries(on day: Day) -> [FoodEntry] {
        entries.filter { $0.date == day }.sorted { $0.createdAt < $1.createdAt }
    }

    func totals(on day: Day) -> Macros { Macros.sum(entries(on: day).map(\.macros)) }

    /// Totals per logged day between [from] and [to].
    func intakes(from: Day, to: Day) -> [DayIntake] {
        var byDay: [Day: (Macros, Int)] = [:]
        for entry in entries where entry.date >= from && entry.date <= to {
            let current = byDay[entry.date] ?? (.zero, 0)
            byDay[entry.date] = (current.0 + entry.macros, current.1 + 1)
        }
        return byDay.map { DayIntake(date: $0.key, totals: $0.value.0, entries: $0.value.1) }.sorted { $0.date < $1.date }
    }

    /// Latest entry of each distinct food, newest first.
    func recents(limit: Int = 60) -> [FoodEntry] {
        var seen = Set<String>()
        var result: [FoodEntry] = []
        for entry in entries.sorted(by: { $0.createdAt > $1.createdAt }) {
            let key = entry.name.trimmingCharacters(in: .whitespaces).lowercased()
            if seen.insert(key).inserted { result.append(entry) }
            if result.count >= limit { break }
        }
        return result
    }

    func add(_ new: [FoodEntry]) {
        guard !new.isEmpty else { return }
        entries.append(contentsOf: new)
        persist(Set(new.map(\.date)))
    }

    func update(_ entry: FoodEntry) {
        guard let index = entries.firstIndex(where: { $0.id == entry.id }) else { return }
        let old = entries[index]
        entries[index] = entry
        persist([old.date, entry.date])
    }

    func delete(_ entry: FoodEntry) {
        entries.removeAll { $0.id == entry.id }
        persist([entry.date])
    }

    private func persist(_ days: Set<Day>) {
        FileStore.save(entries, Self.file)
        onChange?(days)
    }

    // Backups.

    var backupEntries: [BackupEntry] {
        entries.map {
            BackupEntry(date: $0.date.iso, meal: $0.meal.rawValue, name: $0.name, quantity: $0.quantity, calories: $0.calories,
                        protein: $0.protein, carbs: $0.carbs, fat: $0.fat, source: $0.source, createdAt: $0.createdAt)
        }
    }

    /// Adds the entries of a backup that are not here yet; returns how many were added.
    func importEntries(_ incoming: [BackupEntry]) -> Int {
        let missing = Backups.missingEntries(existing: backupEntries, incoming: incoming)
        let new = missing.compactMap { e -> FoodEntry? in
            guard let day = Day(iso: e.date) else { return nil }
            return FoodEntry(date: day, meal: .from(e.meal), name: e.name, quantity: e.quantity, calories: e.calories,
                             protein: e.protein, carbs: e.carbs, fat: e.fat, source: e.source, createdAt: e.createdAt)
        }
        add(new)
        return new.count
    }
}
