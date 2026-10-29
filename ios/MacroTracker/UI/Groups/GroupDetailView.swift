import SwiftUI
import UIKit

private let feedPage = 30

/// The post a comments sheet is about. [postId] is the day for food and weight, the id for workouts.
struct PostRef: Identifiable, Hashable {
    let userId: String
    let displayName: String
    let type: String
    let postId: String
    let day: String
    var foods: [String] = []
    var initialFood: String? = nil

    var id: String { "\(type)|\(userId)|\(postId)" }
}

extension FeedPost {
    func ref(initialFood: String? = nil) -> PostRef {
        PostRef(userId: userId, displayName: displayName, type: type, postId: postId, day: day,
                foods: distinctNames(food?.meals ?? []), initialFood: initialFood)
    }
}

func distinctNames(_ meals: [SharedMeal]) -> [String] {
    var seen = Set<String>()
    return meals.map(\.name).filter { seen.insert($0).inserted }
}

/// Everything shown for one group.
@MainActor
@Observable
final class GroupDetailModel {
    let groupId: String
    var group: GroupInfo?
    var members: [GroupMember] = []
    var summaries: [DailySummary] = []
    /// Everybody's food today, for the rings around their avatars.
    var todaySummaries: [DailySummary] = []
    var comments: [MealComment] = []
    var feed: [FeedPost] = []
    var feedLoaded = false
    var feedEnd = false
    var loadingMore = false
    var gymStats: [GymStats] = []
    var myId: String?
    var date = Day.today
    var loading = false
    var error: String?

    var openPost: PostRef?
    var postComments: [MealComment] = []
    var commentsLoading = false
    var commentsError: String?
    var sending = false

    var shownMember: GroupMember?
    var memberProgress: MemberProgress?
    var memberLoading = false
    var memberError: String?

    init(groupId: String) { self.groupId = groupId }

    func refresh(_ app: AppModel) async {
        guard let api = app.groupAPI else { return }
        await load {
            // Make sure our own numbers are fresh before showing everybody's.
            await app.sync.syncFood(Set([Day.today, self.date]))
            await app.sync.syncProgress()
            self.myId = try await api.currentUserId()
            guard let group = try await api.myGroups().first(where: { $0.id == self.groupId }) else {
                throw BackendError(message: "Ya no perteneces a este grupo")
            }
            self.group = group
            let today = Day.today
            let id = self.groupId
            async let members = api.members(id)
            async let feed = api.feed(id, limit: feedPage)
            async let stats = api.gymStats(id, from: today.adding(days: -6))
            try await self.loadWindow(api, self.date)
            if self.date != today {
                self.todaySummaries = try await api.summaries(self.groupId, from: today, to: today)
            }
            self.members = try await members
            self.feed = try await feed
            self.feedLoaded = true
            self.feedEnd = self.feed.count < feedPage
            self.gymStats = try await stats
        }
    }

    func loadMore(_ app: AppModel) async {
        guard let api = app.groupAPI, !loadingMore, !feedEnd, let before = feed.last?.at else { return }
        loadingMore = true
        do {
            let page = try await api.feed(groupId, before: before, limit: feedPage)
            let known = Set(feed.map(\.id))
            feed += page.filter { !known.contains($0.id) }
            feedEnd = page.count < feedPage
        } catch {
            self.error = friendlyMessage(error, "No se pudieron cargar más publicaciones")
        }
        loadingMore = false
    }

    func shiftDay(_ app: AppModel, _ days: Int) async {
        guard let api = app.groupAPI else { return }
        let next = date.adding(days: days)
        guard next <= Day.today else { return }
        date = next
        await load { try await self.loadWindow(api, next) }
    }

    /// Toggles my reaction right away and undoes it if the server refuses.
    func react(_ app: AppModel, _ post: FeedPost, _ emoji: String) async {
        guard let api = app.groupAPI else { return }
        let (counts, mine) = toggledReactions(post.reactions, mine: post.myReaction, emoji: emoji)
        replacePost(post.id) {
            $0.reactions = counts
            $0.myReaction = mine
        }
        do {
            _ = try await api.react(groupId: groupId, targetUser: post.userId, postType: post.type, postId: post.postId, emoji: emoji)
        } catch {
            replacePost(post.id) {
                $0.reactions = post.reactions
                $0.myReaction = post.myReaction
            }
            self.error = friendlyMessage(error, "No se pudo reaccionar")
        }
    }

    func openComments(_ app: AppModel, _ post: PostRef) async {
        openPost = post
        postComments = []
        commentsError = nil
        await reloadComments(app, post)
    }

    func sendComment(_ app: AppModel, food: String?, body: String) async -> Bool {
        guard let api = app.groupAPI, let post = openPost else { return false }
        sending = true
        commentsError = nil
        defer { sending = false }
        do {
            try await api.addComment(groupId: groupId, targetUser: post.userId, day: post.day, mealName: food, body: body,
                                     postType: post.type, postId: post.postId)
            await reloadComments(app, post)
            return true
        } catch {
            commentsError = friendlyMessage(error, "No se pudo enviar el comentario")
            return false
        }
    }

    func deleteComment(_ app: AppModel, _ comment: MealComment) async {
        guard let api = app.groupAPI, let post = openPost else { return }
        do {
            try await api.deleteComment(comment.id)
            await reloadComments(app, post)
        } catch {
            commentsError = friendlyMessage(error, "No se pudo eliminar")
        }
    }

    private func reloadComments(_ app: AppModel, _ post: PostRef) async {
        guard let api = app.groupAPI else { return }
        commentsLoading = true
        do {
            let list = try await api.postComments(groupId: groupId, targetUser: post.userId, postType: post.type, postId: post.postId)
            if openPost == post { postComments = list }
            // Keep the counters of the feed and of the day cards in step.
            replacePost(post.id) { $0.commentCount = list.count }
            if post.type == PostType.food {
                comments = try await api.comments(groupId, from: date.adding(days: -6), to: date)
            }
        } catch {
            commentsError = friendlyMessage(error, "No se pudieron cargar los comentarios")
        }
        commentsLoading = false
    }

    func openMember(_ app: AppModel, _ member: GroupMember) async {
        guard let api = app.groupAPI else { return }
        shownMember = member
        memberProgress = nil
        memberError = nil
        memberLoading = true
        do {
            let progress = try await api.memberProgress(groupId: groupId, userId: member.userId)
            if shownMember == member { memberProgress = progress }
        } catch {
            memberError = friendlyMessage(error, "No se pudo cargar su progreso")
        }
        memberLoading = false
    }

    func leave(_ app: AppModel) async -> Bool {
        guard let api = app.groupAPI else { return false }
        var left = false
        await load {
            try await api.leaveGroup(self.groupId)
            app.settings.setLastGroup("")
            let remaining = try await api.myGroups()
            app.settings.setHasGroups(!remaining.isEmpty)
            left = true
        }
        return left
    }

    private func replacePost(_ id: String, _ change: (inout FeedPost) -> Void) {
        guard let index = feed.firstIndex(where: { $0.id == id }) else { return }
        change(&feed[index])
    }

    private func loadWindow(_ api: GroupAPI, _ day: Day) async throws {
        summaries = try await api.summaries(groupId, from: day.adding(days: -6), to: day)
        comments = try await api.comments(groupId, from: day.adding(days: -6), to: day)
        if day == Day.today { todaySummaries = summaries.filter { $0.day == day.iso } }
    }

    private func load(_ block: () async throws -> Void) async {
        loading = true
        error = nil
        do {
            try await block()
        } catch is CancellationError {
        } catch {
            self.error = friendlyMessage(error, "No se pudo cargar el grupo")
        }
        loading = false
    }
}

private enum GroupTab: String, CaseIterable {
    case feed = "Feed"
    case today = "Hoy"
    case ranking = "Ranking"
}

/// Members sorted with me first, then by name.
func orderedMembers(_ members: [GroupMember], myId: String?) -> [GroupMember] {
    members.sorted {
        let mine0 = $0.userId == myId, mine1 = $1.userId == myId
        if mine0 != mine1 { return mine0 }
        return $0.displayName.lowercased() < $1.displayName.lowercased()
    }
}

/// Latest goals a member shared in the window.
func latestTargets(_ summaries: [DailySummary], userId: String) -> MacroTargets? {
    summaries.filter { $0.userId == userId }.sorted { $0.day > $1.day }.lazy.compactMap(\.nutrition.targets).first
}

func inviteText(_ group: GroupInfo) -> String {
    "¡Únete a mi grupo \"\(group.name)\" en Macro Tracker! Código: \(group.inviteCode)"
}

struct GroupDetailView: View {
    @Environment(AppModel.self) private var app
    let groupId: String
    let groups: [GroupInfo]
    let onSelectGroup: (String) -> Void
    let onDialog: (GroupDialog) -> Void
    let onLeft: () -> Void

    @State private var model: GroupDetailModel
    @State private var tab = GroupTab.feed
    @State private var confirmLeave = false

    init(groupId: String, groups: [GroupInfo], onSelectGroup: @escaping (String) -> Void,
         onDialog: @escaping (GroupDialog) -> Void, onLeft: @escaping () -> Void) {
        self.groupId = groupId
        self.groups = groups
        self.onSelectGroup = onSelectGroup
        self.onDialog = onDialog
        self.onLeft = onLeft
        _model = State(initialValue: GroupDetailModel(groupId: groupId))
    }

    var body: some View {
        let group = model.group ?? groups.first { $0.id == groupId }
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                StoriesRow(members: model.members, today: model.todaySummaries, window: model.summaries, myId: model.myId) { member in
                    Task { await model.openMember(app, member) }
                }
                Picker("Vista", selection: $tab) {
                    ForEach(GroupTab.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)
                if model.loading { ProgressView().frame(maxWidth: .infinity) }
                if let error = model.error { ErrorText(message: error).padding(.horizontal) }
                Group {
                    switch tab {
                    case .feed: feedContent(group)
                    case .today: todayContent
                    case .ranking: rankingContent(group)
                    }
                }
                .padding(.horizontal)
            }
            .padding(.bottom, 24)
        }
        .screenBackground()
        .refreshable { await model.refresh(app) }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Menu {
                    ForEach(groups) { item in
                        Button { onSelectGroup(item.id) } label: {
                            if item.id == groupId { Label(item.name, systemImage: "checkmark") } else { Text(item.name) }
                        }
                    }
                    Divider()
                    Button("Crear grupo") { onDialog(.create) }
                    Button("Unirme con código") { onDialog(.join) }
                } label: {
                    HStack(spacing: 4) {
                        Text(group?.name ?? "Grupo").font(.headline).lineLimit(1)
                        Image(systemName: "chevron.down").font(.caption.weight(.bold))
                    }
                    .foregroundStyle(Color.primary)
                }
                .accessibilityLabel("Cambiar de grupo")
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                if let group {
                    ShareLink(item: inviteText(group)) { Image(systemName: "square.and.arrow.up") }
                        .accessibilityLabel("Compartir código")
                }
                Menu {
                    Button("Actualizar") { Task { await model.refresh(app) } }
                    Button("Cambiar mi nombre") { onDialog(.name) }
                    if group != nil { Button("Salir del grupo", role: .destructive) { confirmLeave = true } }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .accessibilityLabel("Más opciones")
            }
        }
        .task { await model.refresh(app) }
        .sheet(item: Binding(get: { model.openPost }, set: { if $0 == nil { model.openPost = nil } })) { post in
            CommentsSheet(model: model, post: post)
        }
        .sheet(item: Binding(get: { model.shownMember }, set: { if $0 == nil { model.shownMember = nil } })) { member in
            MemberSheet(member: member, isMe: member.userId == model.myId, progress: model.memberProgress,
                        loading: model.memberLoading, error: model.memberError)
        }
        .alert("Salir del grupo", isPresented: $confirmLeave) {
            Button("Salir", role: .destructive) {
                Task { if await model.leave(app) { onLeft() } }
            }
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text("Dejarás de ver el progreso de este grupo. Puedes volver a unirte con el código.")
        }
    }

    @ViewBuilder
    private func feedContent(_ group: GroupInfo?) -> some View {
        if let group, group.memberCount <= 1 { InviteCard(group: group) }
        if model.feedLoaded && model.feed.isEmpty {
            Text("Aún no hay publicaciones. Registra tus comidas, tu peso o un entrenamiento y aparecerán aquí para tus amigos.")
                .font(.callout).foregroundStyle(.secondary)
        }
        ForEach(model.feed) { post in
            FeedPostCard(
                post: post,
                isMine: post.userId == model.myId,
                onReact: { emoji in Task { await model.react(app, post, emoji) } },
                onComments: { food in Task { await model.openComments(app, post.ref(initialFood: food)) } },
                onOpenMember: {
                    if let member = model.members.first(where: { $0.userId == post.userId }) {
                        Task { await model.openMember(app, member) }
                    }
                }
            )
        }
        if !model.feed.isEmpty && !model.feedEnd {
            Button(model.loadingMore ? "Cargando…" : "Ver publicaciones anteriores") {
                Task { await model.loadMore(app) }
            }
            .buttonStyle(.bordered)
            .disabled(model.loadingMore)
            .frame(maxWidth: .infinity)
        }
    }

    @ViewBuilder
    private var todayContent: some View {
        let day = model.date.iso
        HStack {
            Button { Task { await model.shiftDay(app, -1) } } label: { Image(systemName: "chevron.left").padding(8) }
                .accessibilityLabel("Día anterior")
            Spacer()
            Text(dayLabel(model.date)).font(.headline)
            Spacer()
            Button { Task { await model.shiftDay(app, 1) } } label: { Image(systemName: "chevron.right").padding(8) }
                .disabled(model.date >= Day.today)
                .accessibilityLabel("Día siguiente")
        }
        ForEach(orderedMembers(model.members, myId: model.myId)) { member in
            let summary = model.summaries.first { $0.userId == member.userId && $0.day == day }
            MemberDayCard(
                member: member,
                isMe: member.userId == model.myId,
                summary: summary,
                // A member without a row for this day still shows their latest goals.
                fallbackTargets: latestTargets(model.summaries, userId: member.userId),
                commentCount: model.comments.filter { $0.targetUser == member.userId && $0.day == day && $0.postType == PostType.food }.count,
                onOpenMember: { Task { await model.openMember(app, member) } },
                onComment: { food in
                    let ref = PostRef(userId: member.userId, displayName: member.displayName, type: PostType.food, postId: day, day: day,
                                      foods: distinctNames(summary?.nutrition.meals ?? []), initialFood: food)
                    Task { await model.openComments(app, ref) }
                }
            )
        }
    }

    @ViewBuilder
    private func rankingContent(_ group: GroupInfo?) -> some View {
        if !model.members.isEmpty {
            RankingCard(members: model.members, summaries: model.summaries, myId: model.myId)
            GymRankingCard(members: model.members, stats: model.gymStats, myId: model.myId)
            WeightGoalsCard(members: model.members, myId: model.myId) { member in
                Task { await model.openMember(app, member) }
            }
        }
        if let group { InviteCard(group: group) }
    }
}

/// Members as stories: the ring fills with today's calories against their goal.
private struct StoriesRow: View {
    let members: [GroupMember]
    let today: [DailySummary]
    let window: [DailySummary]
    let myId: String?
    let onOpen: (GroupMember) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: 14) {
                ForEach(orderedMembers(members, myId: myId)) { member in
                    let summary = today.first { $0.userId == member.userId }
                    let targets = summary?.nutrition.targets ?? latestTargets(window, userId: member.userId)
                    let calories = summary?.nutrition.calories ?? 0
                    Button { onOpen(member) } label: {
                        VStack(spacing: 4) {
                            StoryAvatar(
                                name: member.displayName,
                                seed: member.userId,
                                progress: targets.map { $0.calories > 0 ? calories / $0.calories : 0 },
                                highlight: targets != nil && summary != nil && targets!.isOnTarget(summary!.nutrition.totals)
                            )
                            Text(member.userId == myId ? "Tú" : String(member.displayName.split(separator: " ").first ?? ""))
                                .font(.caption.weight(.semibold)).lineLimit(1)
                            Text(calories > 0 ? "\(formatAmount(calories)) kcal" : "—").font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                        }
                        .frame(width: 72)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 8)
        }
    }
}

private struct InviteCard: View {
    let group: GroupInfo
    @State private var copied = false

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("Invita a tus amigos").font(.subheadline.weight(.semibold))
                Text(group.inviteCode).font(.system(.title, design: .monospaced).bold()).textSelection(.enabled)
                Text(group.memberCount == 1 ? "1 miembro" : "\(group.memberCount) miembros").font(.caption)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 8) {
                ShareLink("Compartir", item: inviteText(group))
                Button(copied ? "¡Copiado!" : "Copiar") {
                    UIPasteboard.general.string = group.inviteCode
                    copied = true
                }
            }
        }
        .padding(16)
        .background(AppColor.primarySoft, in: RoundedRectangle(cornerRadius: 22))
    }
}

private struct MemberDayCard: View {
    let member: GroupMember
    let isMe: Bool
    let summary: DailySummary?
    let fallbackTargets: MacroTargets?
    let commentCount: Int
    let onOpenMember: () -> Void
    let onComment: (String?) -> Void
    @State private var expanded = false

    var body: some View {
        let totals = summary?.nutrition.totals ?? .zero
        let targets = summary?.nutrition.targets ?? fallbackTargets
        let meals = summary?.nutrition.meals ?? []
        let logged = totals.calories > 0 || !meals.isEmpty
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                Button(action: onOpenMember) { Avatar(name: member.displayName, seed: member.userId, size: 36) }
                    .buttonStyle(.plain)
                Text(member.displayName + (isMe ? " (tú)" : "") + (member.isOwner ? " 👑" : "")).font(.headline).lineLimit(1)
                Spacer()
                Text(targets.map { "\(formatAmount(totals.calories)) / \(formatAmount($0.calories)) kcal" }
                     ?? (logged ? kcal(totals.calories) : "Sin registros"))
                    .font(.subheadline.weight(.semibold))
            }
            if let targets {
                ProgressBar(value: targets.calories > 0 ? totals.calories / targets.calories : 0,
                            color: totals.calories > targets.calories * 1.1 ? AppColor.error : AppColor.primary)
                MacroBars(totals: totals, targets: targets)
                if !logged { Text("Sin registros este día").font(.caption).foregroundStyle(.secondary) }
            } else if logged {
                MacroBars(totals: totals, targets: nil)
            }
            if expanded && !meals.isEmpty {
                ForEach(mealGroups(meals), id: \.0) { group in
                    Text(group.0.label).font(.subheadline.weight(.semibold)).padding(.top, 4)
                    ForEach(Array(group.1.enumerated()), id: \.offset) { item in
                        Button { onComment(item.element.name) } label: {
                            HStack {
                                Text("• \(item.element.name)").font(.caption)
                                Spacer()
                                Text("\(kcal(item.element.calories))  💬").font(.caption)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            HStack {
                if !meals.isEmpty {
                    Button(expanded ? "Ocultar comidas ▴" : "Ver \(meals.count) alimentos ▾") { withAnimation { expanded.toggle() } }
                }
                Spacer()
                Button(commentCount == 0 ? "💬 Comentar" : commentCount == 1 ? "💬 1 comentario" : "💬 \(commentCount) comentarios") {
                    onComment(nil)
                }
            }
            .font(.subheadline.weight(.semibold))
            .buttonStyle(.borderless)
        }
        .card()
    }
}

/// Meals grouped by type, in the order of the day.
func mealGroups(_ meals: [SharedMeal]) -> [(MealType, [SharedMeal])] {
    Dictionary(grouping: meals) { MealType.from($0.meal) }.sorted { $0.key < $1.key }.map { ($0.key, $0.value) }
}

private func medal(_ index: Int) -> String {
    switch index {
    case 0: return "🥇"
    case 1: return "🥈"
    case 2: return "🥉"
    default: return "\(index + 1)."
    }
}

private struct RankingLine: View {
    let index: Int
    let member: GroupMember
    let myId: String?
    let detail: String

    var body: some View {
        HStack(spacing: 8) {
            Text(medal(index)).frame(width: 30, alignment: .leading)
            Avatar(name: member.displayName, seed: member.userId, size: 28)
            Text(member.displayName + (member.userId == myId ? " (tú)" : "")).lineLimit(1)
            Spacer()
            Text(detail).font(.subheadline.weight(.semibold))
        }
    }
}

private struct RankingCard: View {
    let members: [GroupMember]
    let summaries: [DailySummary]
    let myId: String?

    var body: some View {
        let rows = members.map { member -> (GroupMember, Int, Int) in
            let days = summaries.filter { $0.userId == member.userId && $0.nutrition.calories > 0 }
            return (member, days.filter { $0.nutrition.targets?.isOnTarget($0.nutrition.totals) == true }.count, days.count)
        }
        .sorted { ($0.1, $0.2) > ($1.1, $1.2) }
        VStack(alignment: .leading, spacing: 8) {
            Text("🎯 Alimentación · 7 días").font(.headline)
            ForEach(Array(rows.enumerated()), id: \.element.0.userId) { item in
                RankingLine(index: item.offset, member: item.element.0, myId: myId, detail: "\(item.element.1) 🎯 · \(item.element.2) días")
            }
            Text("🎯 = días dentro de ±10% de su objetivo de calorías.").font(.caption).foregroundStyle(.secondary)
        }
        .card()
    }
}

private struct GymRankingCard: View {
    let members: [GroupMember]
    let stats: [GymStats]
    let myId: String?

    var body: some View {
        let byUser = Dictionary(stats.map { ($0.userId, $0) }, uniquingKeysWith: { first, _ in first })
        let rows = members.map { ($0, byUser[$0.userId] ?? GymStats(userId: $0.userId)) }
            .sorted { ($0.1.workouts, $0.1.prs) > ($1.1.workouts, $1.1.prs) }
        VStack(alignment: .leading, spacing: 8) {
            Text("🏋️ Gym · 7 días").font(.headline)
            ForEach(Array(rows.enumerated()), id: \.element.0.userId) { item in
                let stat = item.element.1
                RankingLine(index: item.offset, member: item.element.0, myId: myId,
                            detail: "\(stat.workouts) entrenos · \(stat.prs) 🏆" + (stat.volume > 0 ? " · \(formatAmount(stat.volume)) kg" : ""))
            }
        }
        .card()
    }
}

/// Members who share a weight goal, by how far along they are.
private struct WeightGoalsCard: View {
    let members: [GroupMember]
    let myId: String?
    let onOpen: (GroupMember) -> Void

    var body: some View {
        let rows = members.compactMap { member -> (GroupMember, Double)? in
            guard let goal = member.goalWeight, let current = member.currentWeight else { return nil }
            return (member, WeightGoal(start: member.startWeight ?? current, goal: goal).progress(current))
        }
        .sorted { $0.1 > $1.1 }
        if !rows.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Text("⚖️ Metas de peso").font(.headline)
                ForEach(rows, id: \.0.userId) { row in
                    Button { onOpen(row.0) } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack(spacing: 8) {
                                Avatar(name: row.0.displayName, seed: row.0.userId, size: 28)
                                Text(row.0.displayName + (row.0.userId == myId ? " (tú)" : ""))
                                Spacer()
                                Text("\(Int(row.1 * 100))%").font(.subheadline.weight(.semibold))
                            }
                            ProgressBar(value: row.1, color: Brand.amber, height: 6)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .card()
        }
    }
}
