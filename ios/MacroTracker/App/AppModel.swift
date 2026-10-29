import Foundation
import Observation
import UIKit

/// Everything the screens share, created once when the app starts.
@MainActor
@Observable
final class AppModel {
    let settings: Settings
    let food: FoodStore
    let progress: ProgressStore
    let sessionStore: KeychainSessionStore
    let client: SupabaseClient?
    let groupAPI: GroupAPI?
    let ai: AIService
    let sync: SyncService
    let notifier: ActivityNotifier
    let backup: BackupManager

    var selectedTab: AppTab = .today

    static var geminiModel: String {
        let model = Bundle.main.object(forInfoDictionaryKey: "GeminiModel") as? String ?? ""
        return model.isEmpty || model.contains("$(") ? "gemini-3.5-flash-lite" : model
    }

    init() {
        let settings = Settings()
        let food = FoodStore()
        let progress = ProgressStore()
        let sessionStore = KeychainSessionStore()
        let ref = Bundle.main.object(forInfoDictionaryKey: "SupabaseProjectRef") as? String ?? ""
        let key = Bundle.main.object(forInfoDictionaryKey: "SupabaseAnonKey") as? String ?? ""
        // UI tests run without the server so they never create accounts on it.
        let uiTesting = ProcessInfo.processInfo.arguments.contains("-uiTesting")
        let configured = !ref.isEmpty && !key.isEmpty && !ref.contains("$(") && !uiTesting
        let client = configured ? SupabaseClient(baseURL: "https://\(ref).supabase.co", anonKey: key, store: sessionStore) : nil
        let groupAPI = client.map { GroupAPI(client: $0) }

        self.settings = settings
        self.food = food
        self.progress = progress
        self.sessionStore = sessionStore
        self.client = client
        self.groupAPI = groupAPI
        ai = AIService {
            if !settings.ownGeminiKey.isEmpty {
                return .direct(apiKey: settings.ownGeminiKey, model: settings.geminiModel.isEmpty ? AppModel.geminiModel : settings.geminiModel)
            }
            guard let client else {
                throw AIError(message: "La IA no está configurada. Agrega tu propia clave de Gemini en Perfil.")
            }
            return .proxy(client)
        }
        sync = SyncService(api: groupAPI, settings: settings, food: food, progress: progress)
        notifier = ActivityNotifier(api: groupAPI, settings: settings)
        backup = BackupManager(food: food, progress: progress, settings: settings, client: client, sessionStore: sessionStore)

        food.onChange = { [weak self] days in
            self?.backup.dirty = true
            self?.sync.requestFood(days)
        }
        progress.onChange = { [weak self] in
            self?.backup.dirty = true
            self?.sync.requestProgress()
        }
    }

    var serverConfigured: Bool { client != nil }

    func start() {
        sync.requestRecentFood()
        sync.requestProgress()
    }

    /// The app went to the background.
    func didEnterBackground() {
        backup.backupIfChanged()
        notifier.schedule()
    }

    /// Shows a group, e.g. after tapping one of its notifications.
    func openGroup(_ groupId: String) {
        settings.setLastGroup(groupId)
        selectedTab = .groups
    }

    /// Settings that change what is shared with the groups.
    func sharedSettingsChanged() {
        sync.requestRecentFood()
        sync.requestProgress()
    }
}

enum AppTab: Hashable {
    case today, history, progress, groups, profile
}
