import Foundation

// Same file format as the Android app, so a backup can move between phones.

struct BackupEntry: Codable, Hashable {
    var date: String
    var meal: String
    var name: String
    var quantity: String = ""
    var calories: Double = 0
    var protein: Double = 0
    var carbs: Double = 0
    var fat: Double = 0
    var source: String = "manual"
    var createdAt: Int64 = 0

    init(date: String, meal: String, name: String, quantity: String = "", calories: Double = 0, protein: Double = 0,
         carbs: Double = 0, fat: Double = 0, source: String = "manual", createdAt: Int64 = 0) {
        self.date = date
        self.meal = meal
        self.name = name
        self.quantity = quantity
        self.calories = calories
        self.protein = protein
        self.carbs = carbs
        self.fat = fat
        self.source = source
        self.createdAt = createdAt
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        date = try c.decode(String.self, forKey: .date)
        meal = c.value(.meal, "SNACK")
        name = try c.decode(String.self, forKey: .name)
        quantity = c.value(.quantity, "")
        calories = c.value(.calories, 0)
        protein = c.value(.protein, 0)
        carbs = c.value(.carbs, 0)
        fat = c.value(.fat, 0)
        source = c.value(.source, "manual")
        createdAt = c.value(.createdAt, 0)
    }
}

/// A group of routines the user created, like "Hipertrofia" or "Fuerza".
struct BackupSection: Codable, Hashable {
    var id: String
    var name: String
    var position: Int = 0

    init(id: String, name: String, position: Int = 0) {
        self.id = id
        self.name = name
        self.position = position
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        position = c.value(.position, 0)
    }
}

struct BackupRoutine: Codable, Hashable {
    var id: String
    var name: String
    var exercises: [ExerciseLog] = []
    var position: Int = 0
    var sectionId: String? = nil

    init(id: String, name: String, exercises: [ExerciseLog] = [], position: Int = 0, sectionId: String? = nil) {
        self.id = id
        self.name = name
        self.exercises = exercises
        self.position = position
        self.sectionId = sectionId
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        exercises = c.value(.exercises, [])
        position = c.value(.position, 0)
        sectionId = c.optional(.sectionId)
    }
}

struct BackupWorkout: Codable, Hashable {
    var id: String
    var date: String
    var title: String
    var routineId: String? = nil
    var exercises: [ExerciseLog] = []
    var prCount: Int = 0
    var durationMin: Int? = nil
    var finishedAt: Int64 = 0
    var section: String? = nil

    init(id: String, date: String, title: String, routineId: String? = nil, exercises: [ExerciseLog] = [],
         prCount: Int = 0, durationMin: Int? = nil, finishedAt: Int64 = 0, section: String? = nil) {
        self.id = id
        self.date = date
        self.title = title
        self.routineId = routineId
        self.exercises = exercises
        self.prCount = prCount
        self.durationMin = durationMin
        self.finishedAt = finishedAt
        self.section = section
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        date = try c.decode(String.self, forKey: .date)
        title = c.value(.title, "Entrenamiento")
        routineId = c.optional(.routineId)
        exercises = c.value(.exercises, [])
        prCount = c.value(.prCount, 0)
        durationMin = c.optional(.durationMin)
        finishedAt = c.value(.finishedAt, 0)
        section = c.optional(.section)
    }
}

struct BackupWeight: Codable, Hashable {
    var date: String
    var kg: Double
    var fatPercent: Double? = nil
    var musclePercent: Double? = nil
    var boneKg: Double? = nil
}

/// Everything needed to rebuild the app after a reinstall: food log, goals, gym and weight data
/// and, if the user was in groups, the anonymous session so they come back as the same member.
struct BackupFile: Codable {
    static let appId = "macro-tracker"

    var app: String = BackupFile.appId
    var format: Int = 2
    var exportedAt: String
    var entries: [BackupEntry] = []
    var targets: MacroTargets? = nil
    var displayName: String = ""
    var lastGroupId: String = ""
    var hadGroups: Bool = false
    var session: AuthSession? = nil
    var sections: [BackupSection] = []
    var routines: [BackupRoutine] = []
    var workouts: [BackupWorkout] = []
    var weights: [BackupWeight] = []
    var weightGoal: WeightGoal? = nil
    var shareWeight: Bool = true

    init(exportedAt: String) {
        self.exportedAt = exportedAt
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        app = try c.decode(String.self, forKey: .app)
        format = c.value(.format, 1)
        exportedAt = c.value(.exportedAt, "")
        entries = c.value(.entries, [])
        targets = c.optional(.targets)
        displayName = c.value(.displayName, "")
        lastGroupId = c.value(.lastGroupId, "")
        hadGroups = c.value(.hadGroups, false)
        session = c.optional(.session)
        sections = c.value(.sections, [])
        routines = c.value(.routines, [])
        workouts = c.value(.workouts, [])
        weights = c.value(.weights, [])
        weightGoal = c.optional(.weightGoal)
        shareWeight = c.value(.shareWeight, true)
    }
}

struct BackupError: LocalizedError {
    var errorDescription: String? { "Este archivo no es un respaldo de Macro Tracker" }
}

enum Backups {
    static func encode(_ file: BackupFile) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(file)
    }

    static func decode(_ data: Data) throws -> BackupFile {
        guard let file = try? JSONDecoder().decode(BackupFile.self, from: data), file.app == BackupFile.appId else {
            throw BackupError()
        }
        return file
    }

    /// Entries of [incoming] not already in [existing] (same day, meal, food and creation time).
    static func missingEntries(existing: [BackupEntry], incoming: [BackupEntry]) -> [BackupEntry] {
        var known = Set(existing.map(key))
        return incoming.filter { known.insert(key($0)).inserted }
    }

    private static func key(_ e: BackupEntry) -> String {
        [e.date, e.meal, e.name.trimmingCharacters(in: .whitespaces).lowercased(), String(e.createdAt)].joined(separator: "|")
    }
}
