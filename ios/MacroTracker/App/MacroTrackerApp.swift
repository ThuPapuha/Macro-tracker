import SwiftUI
import UIKit
import UserNotifications

@main
struct MacroTrackerApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .onAppear { NotificationRouter.shared.handler = { [model] in model.openGroup($0) } }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active: model.start()
            case .background: model.didEnterBackground()
            default: break
            }
        }
        .backgroundTask(.appRefresh(ActivityNotifier.taskId)) {
            await model.notifier.poll()
            await model.notifier.schedule()
        }
    }
}

/// Opens the group of a tapped notification, also when the tap launched the app.
@MainActor
final class NotificationRouter {
    static let shared = NotificationRouter()

    var handler: ((String) -> Void)? {
        didSet { flush() }
    }
    private var pending: String?

    func open(_ groupId: String) {
        pending = groupId
        flush()
    }

    private func flush() {
        guard let handler, let groupId = pending else { return }
        pending = nil
        handler(groupId)
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    /// Friends' activity also shows while the app is open.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                            withCompletionHandler completionHandler: @escaping () -> Void) {
        if let groupId = response.notification.request.content.userInfo["groupId"] as? String {
            Task { @MainActor in NotificationRouter.shared.open(groupId) }
        }
        completionHandler()
    }
}
