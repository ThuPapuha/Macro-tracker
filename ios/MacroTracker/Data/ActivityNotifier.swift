import BackgroundTasks
import Foundation
import UserNotifications

/// Tells you when friends log food or workouts, and when they comment on or react to your posts.
/// There is no push server: iOS wakes the app in the background now and then (when it decides) and
/// the app asks the server what changed, like the Android app does every ~15 minutes.
@MainActor
final class ActivityNotifier {
    static let taskId = "macrotracker.group-activity"

    private let api: GroupAPI?
    private let settings: Settings

    init(api: GroupAPI?, settings: Settings) {
        self.api = api
        self.settings = settings
    }

    var active: Bool { api != nil && settings.hasGroups && settings.notifyGroupActivity }

    /// Asks iOS to wake the app in the background in about 15 minutes.
    func schedule() {
        guard active else {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.taskId)
            return
        }
        let request = BGAppRefreshTaskRequest(identifier: Self.taskId)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    func requestPermission() async {
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
        settings.setNotificationPermissionAsked()
    }

    /// Fetches what changed since the last poll and posts one notification per friend, comment,
    /// workout or post with reactions.
    func poll() async {
        guard let api, active else { return }
        var seen = settings.activitySeen
        // Overlap a little with the previous poll; already-seen changes are skipped below.
        let since = seen.cursor.flatMap(Timestamp.parse).map { Timestamp.string($0.addingTimeInterval(-30)) }
        guard let activity = try? await api.activity(since: since) else { return }

        for item in activity.items {
            let key = "\(item.userId)|\(item.day)"
            let previous = seen.days[key]
            if let previous, let new = Timestamp.parse(item.updatedAt), let old = Timestamp.parse(previous.updatedAt), new <= old { continue }
            let foods = item.meals.map(\.name)
            let added = Self.newFoods(previous?.foods ?? [], foods)
            let day = Day(iso: item.day).map { dayLabel($0) } ?? item.day
            let title: String
            let body: String
            if added.isEmpty {
                title = "\(item.displayName) actualizó sus comidas"
                body = "\(day) lleva \(kcal(item.calories))"
            } else {
                title = "\(item.displayName) registró comida"
                body = added.prefix(4).joined(separator: ", ") + (added.count > 4 ? "…" : "") + " · \(day) lleva \(kcal(item.calories))"
            }
            post(id: "food-\(item.userId)", group: item.groupId, groupName: item.groupName, title: title, body: body)
            seen.days[key] = SeenDay(updatedAt: item.updatedAt, foods: foods)
        }
        for comment in activity.comments where !seen.commentIds.contains(comment.id) {
            let what = comment.postType == PostType.workout ? "tu entrenamiento" : comment.postType == PostType.weight ? "tu progreso de peso" : "tu comida"
            let about = comment.mealName.map { "Sobre \($0): " } ?? ""
            post(id: "comment-\(comment.id)", group: comment.groupId, groupName: comment.groupName,
                 title: "\(comment.authorName) comentó \(what)", body: about + comment.body)
            seen.commentIds.append(comment.id)
        }
        for workout in activity.workouts where !seen.workoutIds.contains(workout.id) {
            let records = workout.exercises.filter(\.pr)
            let title = records.isEmpty
                ? "💪 \(workout.displayName) terminó de entrenar" + (workout.category.map { " · \($0)" } ?? "")
                : "🏆 \(workout.displayName) rompió \(records.count == 1 ? "un récord" : "\(records.count) récords")"
            let body = records.isEmpty
                ? "\(workout.title): " + workout.exercises.prefix(4).map(\.name).joined(separator: ", ")
                : records.prefix(3).map { e in Gym.best(e.sets).map { "\(e.name) \(e.unit.format($0.weight)) × \($0.reps)" } ?? e.name }.joined(separator: ", ")
            post(id: "workout-\(workout.id)", group: workout.groupId, groupName: workout.groupName, title: title, body: body)
            seen.workoutIds.append(workout.id)
        }
        // Several reactions to the same post become one notification.
        let fresh = activity.reactions.filter { !seen.reactionKeys.contains(Self.reactionKey($0)) }
        for (post, reactions) in Dictionary(grouping: fresh, by: { "\($0.groupId)|\($0.postType)|\($0.postId)" }) {
            guard let first = reactions.first else { continue }
            var names: [String] = []
            for reaction in reactions where !names.contains(reaction.authorName) { names.append(reaction.authorName) }
            let who = names.count == 1 ? names[0] : names.count == 2 ? "\(names[0]) y \(names[1])" : "\(names[0]) y \(names.count - 1) más"
            let what = first.postType == PostType.workout ? "tu entrenamiento" : first.postType == PostType.weight ? "tu progreso de peso" : "tus comidas"
            let emojis = Array(Set(reactions.map(\.emoji))).joined()
            self.post(id: "reaction-\(post)", group: first.groupId, groupName: first.groupName,
                      title: "\(emojis) \(who) reaccionó a \(what)", body: "Toca para ver la publicación")
            seen.reactionKeys.append(contentsOf: reactions.map(Self.reactionKey))
        }

        let oldest = Day.today.adding(days: -4).iso
        seen.cursor = activity.now
        seen.days = seen.days.filter { ($0.key.split(separator: "|").last.map(String.init) ?? "") >= oldest }
        seen.commentIds = Array(seen.commentIds.suffix(200))
        seen.workoutIds = Array(seen.workoutIds.suffix(200))
        seen.reactionKeys = Array(seen.reactionKeys.suffix(300))
        settings.activitySeen = seen
    }

    private func post(id: String, group: String, groupName: String, title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.subtitle = groupName
        content.sound = .default
        content.userInfo = ["groupId": group]
        content.threadIdentifier = group
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: content, trigger: nil))
    }

    private static func reactionKey(_ r: GroupActivity.Reaction) -> String {
        "\(r.groupId)|\(r.postType)|\(r.postId)|\(r.authorId)|\(r.emoji)"
    }

    /// Foods in [current] that were not in [previous], counting repeats.
    static func newFoods(_ previous: [String], _ current: [String]) -> [String] {
        var remaining: [String: Int] = [:]
        for name in previous { remaining[name, default: 0] += 1 }
        return current.filter { name in
            if let count = remaining[name], count > 0 {
                remaining[name] = count - 1
                return false
            }
            return true
        }
    }
}
