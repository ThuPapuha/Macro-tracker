import Foundation
import Observation

/// Last friends' days, comments, workouts and reactions already notified, so each is announced once.
struct ActivitySeen: Codable {
    var cursor: String? = nil
    /// "userId|day" -> last updated_at seen and the foods then.
    var days: [String: SeenDay] = [:]
    var commentIds: [String] = []
    var workoutIds: [String] = []
    var reactionKeys: [String] = []
}

struct SeenDay: Codable {
    var updatedAt: String
    var foods: [String]
}

/// User preferences, kept in UserDefaults. Changed through the setters so every change is saved.
@MainActor
@Observable
final class Settings {
    private let defaults = UserDefaults.standard

    private(set) var targets: MacroTargets?
    private(set) var displayName: String
    private(set) var displayNameSynced: Bool
    private(set) var hasGroups: Bool
    private(set) var shareMeals: Bool
    private(set) var ownGeminiKey: String
    private(set) var geminiModel: String
    private(set) var bodyProfile: BodyProfile?
    private(set) var lastGroupId: String
    private(set) var notifyGroupActivity: Bool
    private(set) var notificationPermissionAsked: Bool
    private(set) var lastBackupAt: Date?
    private(set) var weightGoal: WeightGoal?
    private(set) var shareWeight: Bool
    private(set) var weightGoalSynced: Bool

    init() {
        let d = UserDefaults.standard
        targets = Self.decode(MacroTargets.self, d.data(forKey: "targets"))
        displayName = d.string(forKey: "displayName") ?? ""
        displayNameSynced = d.bool(forKey: "displayNameSynced")
        hasGroups = d.bool(forKey: "hasGroups")
        shareMeals = d.object(forKey: "shareMeals") as? Bool ?? true
        ownGeminiKey = d.string(forKey: "ownGeminiKey") ?? ""
        geminiModel = d.string(forKey: "geminiModel") ?? ""
        bodyProfile = Self.decode(BodyProfile.self, d.data(forKey: "bodyProfile"))
        lastGroupId = d.string(forKey: "lastGroupId") ?? ""
        notifyGroupActivity = d.object(forKey: "notifyGroupActivity") as? Bool ?? true
        notificationPermissionAsked = d.bool(forKey: "notificationPermissionAsked")
        lastBackupAt = d.object(forKey: "lastBackupAt") as? Date
        weightGoal = Self.decode(WeightGoal.self, d.data(forKey: "weightGoal"))
        shareWeight = d.object(forKey: "shareWeight") as? Bool ?? true
        weightGoalSynced = d.bool(forKey: "weightGoalSynced")
    }

    private static func decode<T: Decodable>(_ type: T.Type, _ data: Data?) -> T? {
        data.flatMap { try? JSONDecoder().decode(type, from: $0) }
    }

    private func store<T: Encodable>(_ value: T?, _ key: String) {
        if let value, let data = try? JSONEncoder().encode(value) {
            defaults.set(data, forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
    }

    func setTargets(_ value: MacroTargets) {
        targets = value
        store(value, "targets")
    }

    func setDisplayName(_ name: String, synced: Bool) {
        displayName = name
        displayNameSynced = synced
        defaults.set(name, forKey: "displayName")
        defaults.set(synced, forKey: "displayNameSynced")
    }

    func setHasGroups(_ value: Bool) {
        hasGroups = value
        defaults.set(value, forKey: "hasGroups")
    }

    func setShareMeals(_ value: Bool) {
        shareMeals = value
        defaults.set(value, forKey: "shareMeals")
    }

    func setOwnGemini(key: String, model: String) {
        ownGeminiKey = key.trimmingCharacters(in: .whitespacesAndNewlines)
        geminiModel = model.trimmingCharacters(in: .whitespacesAndNewlines)
        defaults.set(ownGeminiKey, forKey: "ownGeminiKey")
        defaults.set(geminiModel, forKey: "geminiModel")
    }

    func setBodyProfile(_ value: BodyProfile) {
        bodyProfile = value
        store(value, "bodyProfile")
    }

    func setLastGroup(_ id: String) {
        lastGroupId = id
        defaults.set(id, forKey: "lastGroupId")
    }

    func setNotifyGroupActivity(_ value: Bool) {
        notifyGroupActivity = value
        defaults.set(value, forKey: "notifyGroupActivity")
    }

    func setNotificationPermissionAsked() {
        notificationPermissionAsked = true
        defaults.set(true, forKey: "notificationPermissionAsked")
    }

    func setLastBackup(_ date: Date) {
        lastBackupAt = date
        defaults.set(date, forKey: "lastBackupAt")
    }

    func setWeightGoal(_ goal: WeightGoal?, share: Bool) {
        weightGoal = goal
        shareWeight = share
        weightGoalSynced = false
        store(goal, "weightGoal")
        defaults.set(share, forKey: "shareWeight")
        defaults.set(false, forKey: "weightGoalSynced")
    }

    /// Marks the weight goal as uploaded, unless it changed while uploading.
    func markWeightGoalSynced(_ uploaded: WeightGoal?, share: Bool) {
        guard weightGoal == uploaded, shareWeight == share else { return }
        weightGoalSynced = true
        defaults.set(true, forKey: "weightGoalSynced")
    }

    var activitySeen: ActivitySeen {
        get { Self.decode(ActivitySeen.self, defaults.data(forKey: "activitySeen")) ?? ActivitySeen() }
        set { store(newValue, "activitySeen") }
    }

    /// After deleting the account on the server.
    func forgetGroups() {
        setHasGroups(false)
        setLastGroup("")
        displayNameSynced = false
        defaults.set(false, forKey: "displayNameSynced")
        weightGoalSynced = false
        defaults.set(false, forKey: "weightGoalSynced")
        defaults.removeObject(forKey: "activitySeen")
    }
}
