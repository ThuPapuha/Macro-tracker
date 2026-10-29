import Foundation
import SwiftUI
import UniformTypeIdentifiers

struct ImportResult {
    let added: Int
    let alreadyPresent: Int
    let restoredGroups: Bool
    let addedWorkouts: Int
    let addedWeights: Int

    var message: String {
        var text = added == 1 ? "Se importó 1 alimento" : "Se importaron \(added) alimentos"
        if alreadyPresent > 0 { text += " (\(alreadyPresent) ya estaban)" }
        text += "."
        if addedWorkouts > 0 { text += " Entrenamientos: \(addedWorkouts)." }
        if addedWeights > 0 { text += " Registros de peso: \(addedWeights)." }
        if restoredGroups { text += " Recuperaste tu usuario de grupos." }
        return text
    }
}

/// A backup file for the share sheet / Files app.
struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    let data: Data

    init(data: Data) { self.data = data }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

/// The food log only lives on the phone. This keeps a copy in the app's Documents folder (visible in
/// the Files app) every time the app goes to the background, and exports/imports backup files.
/// Same format as the Android app.
@MainActor
final class BackupManager {
    static let autoFileName = "macrotracker-respaldo.json"

    private let food: FoodStore
    private let progress: ProgressStore
    private let settings: Settings
    private let client: SupabaseClient?
    private let sessionStore: SessionStoring
    var dirty = true

    init(food: FoodStore, progress: ProgressStore, settings: Settings, client: SupabaseClient?, sessionStore: SessionStoring) {
        self.food = food
        self.progress = progress
        self.settings = settings
        self.client = client
        self.sessionStore = sessionStore
    }

    func snapshot() -> BackupFile {
        var file = BackupFile(exportedAt: Timestamp.string(Date()))
        file.entries = food.backupEntries
        file.targets = settings.targets
        file.displayName = settings.displayName
        file.lastGroupId = settings.lastGroupId
        file.hadGroups = settings.hasGroups
        file.session = settings.hasGroups ? sessionStore.loadSession() : nil
        file.sections = progress.backupSections
        file.routines = progress.backupRoutines
        file.workouts = progress.backupWorkouts
        file.weights = progress.backupWeights
        file.weightGoal = settings.weightGoal
        file.shareWeight = settings.shareWeight
        return file
    }

    func exportData() throws -> Data { try Backups.encode(snapshot()) }

    /// Writes Documents/macrotracker-respaldo.json when something changed.
    func backupIfChanged() {
        guard dirty, let data = try? exportData() else { return }
        do {
            try data.write(to: FileStore.documents.appendingPathComponent(Self.autoFileName), options: .atomic)
            dirty = false
            settings.setLastBackup(Date())
        } catch {
            // Tried again next time.
        }
    }

    func importFrom(_ url: URL) async throws -> ImportResult {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let backup = try Backups.decode(try Data(contentsOf: url))

        let added = food.importEntries(backup.entries)
        let (workouts, weights) = progress.importBackup(backup)
        if settings.targets == nil, let targets = backup.targets { settings.setTargets(targets) }
        if settings.weightGoal == nil, let goal = backup.weightGoal { settings.setWeightGoal(goal, share: backup.shareWeight) }

        // Coming back as the same group member only makes sense on an install without groups.
        let restoreGroups = !settings.hasGroups && backup.hadGroups && backup.session != nil
        if restoreGroups, let session = backup.session {
            await client?.replaceSession(session)
            settings.setHasGroups(true)
            if settings.lastGroupId.isEmpty { settings.setLastGroup(backup.lastGroupId) }
        }
        if settings.displayName.isEmpty && !backup.displayName.isEmpty {
            settings.setDisplayName(backup.displayName, synced: restoreGroups)
        }
        dirty = true
        return ImportResult(added: added, alreadyPresent: backup.entries.count - added, restoredGroups: restoreGroups,
                            addedWorkouts: workouts, addedWeights: weights)
    }
}
