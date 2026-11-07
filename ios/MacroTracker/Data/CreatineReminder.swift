import Foundation
import UserNotifications

/// Local reminders are scheduled per day so a recorded dose can cancel that day's alert.
@MainActor
final class CreatineReminder {
    private static let prefix = "creatine-reminder-"
    private let settings: Settings
    private let progress: ProgressStore
    private var pending: Task<Void, Never>?

    init(settings: Settings, progress: ProgressStore) {
        self.settings = settings
        self.progress = progress
    }

    func refresh() {
        let previous = pending
        pending = Task {
            await previous?.value
            await update()
        }
    }

    private func update() async {
        let center = UNUserNotificationCenter.current()
        let requests = await center.pendingNotificationRequests()
        center.removePendingNotificationRequests(withIdentifiers: requests.map(\.identifier).filter { $0.hasPrefix(Self.prefix) })
        guard settings.remindCreatine else { return }

        let calendar = Calendar.current
        let hour = settings.creatineReminderMinute / 60
        let minute = settings.creatineReminderMinute % 60
        let today = Day.today
        for offset in 0..<60 {
            let day = today.adding(days: offset)
            guard !progress.creatineDays.contains(day),
                  let date = calendar.date(bySettingHour: hour, minute: minute, second: 0, of: day.date),
                  date > Date() else { continue }

            let content = UNMutableNotificationContent()
            content.title = "¿Ya tomaste tu creatina?"
            content.body = "Registra la toma de hoy en Progreso."
            content.sound = .default
            let parts = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: date)
            let request = UNNotificationRequest(identifier: Self.prefix + day.iso, content: content,
                                                trigger: UNCalendarNotificationTrigger(dateMatching: parts, repeats: false))
            try? await center.add(request)
        }
    }
}
