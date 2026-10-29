import SwiftUI

private func dayText(_ day: String) -> String {
    Day(iso: day).map { dayLabel($0).lowercased() } ?? day
}

struct FeedPostCard: View {
    let post: FeedPost
    let isMine: Bool
    let onReact: (String) -> Void
    let onComments: (String?) -> Void
    let onOpenMember: () -> Void

    var body: some View {
        let action: String = {
            switch post.type {
            case PostType.food: return "registró sus comidas de \(dayText(post.day))"
            case PostType.workout: return "entrenó \(post.workout?.title ?? "")"
            default:
                let measured = post.weight.map { $0.fat != nil || $0.muscle != nil || $0.bone != nil } ?? false
                return measured ? "registró su peso y composición" : "registró su peso"
            }
        }()
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                Button(action: onOpenMember) {
                    HStack(spacing: 10) {
                        Avatar(name: post.displayName, seed: post.userId, size: 42)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(post.displayName + (isMine ? " (tú)" : "")).font(.subheadline.weight(.semibold)).lineLimit(1)
                            Text("\(action) · \(timeAgo(post.at))").font(.caption).foregroundStyle(.secondary).lineLimit(2)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                Spacer()
                Text(post.type == PostType.food ? "🍽️" : post.type == PostType.workout ? "🏋️" : "⚖️").font(.title2)
            }
            if let food = post.food { FoodContent(food: food) { onComments($0) } }
            if let workout = post.workout { WorkoutContent(workout: workout) }
            if let weight = post.weight { WeightContent(weight: weight) }
            ReactionBar(reactions: post.reactions, mine: post.myReaction, commentCount: post.commentCount,
                        onReact: onReact, onComments: { onComments(nil) })
        }
        .card()
    }
}

private struct FoodContent: View {
    let food: NutritionData
    let onFood: (String) -> Void

    var body: some View {
        let targets = food.targets
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .lastTextBaseline, spacing: 0) {
                Text(formatAmount(food.calories)).font(.title2.bold())
                Text(targets.map { " / \(formatAmount($0.calories)) kcal" } ?? " kcal").font(.callout).foregroundStyle(.secondary)
                Spacer()
                if targets?.isOnTarget(food.totals) == true { Pill(text: "🎯 En objetivo", selected: true) }
            }
            if let targets, targets.calories > 0 {
                ProgressBar(value: food.calories / targets.calories,
                            color: food.calories > targets.calories * 1.1 ? AppColor.error : AppColor.primary)
            }
            FlowLayout {
                MacroPill(label: "P", value: food.protein, target: targets?.protein, color: MacroColor.protein)
                MacroPill(label: "C", value: food.carbs, target: targets?.carbs, color: MacroColor.carbs)
                MacroPill(label: "G", value: food.fat, target: targets?.fat, color: MacroColor.fat)
            }
            ForEach(mealGroups(food.meals), id: \.0) { group in
                VStack(alignment: .leading, spacing: 4) {
                    Text(group.0.label).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    FlowLayout {
                        ForEach(Array(group.1.enumerated()), id: \.offset) { item in
                            Pill(text: "\(item.element.name) · \(formatAmount(item.element.calories))") { onFood(item.element.name) }
                        }
                    }
                }
            }
        }
    }
}

private struct MacroPill: View {
    let label: String
    let value: Double
    let target: Double?
    let color: Color

    var body: some View {
        let text = (target ?? 0) > 0
            ? "\(label) \(formatAmount(value))/\(formatAmount(target ?? 0)) g"
            : "\(label) \(formatAmount(value)) g"
        Pill(text: text, tint: color.opacity(0.2))
    }
}

private struct WorkoutContent: View {
    let workout: WorkoutPostData

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let category = workout.category { Pill(text: category, tint: Brand.violet.opacity(0.22)) }
            WorkoutStatsRow(exercises: workout.exercises, durationMin: workout.durationMin, prCount: workout.prCount)
            ExerciseLines(exercises: workout.exercises, limit: 5)
        }
    }
}

private struct WeightContent: View {
    let weight: WeightPostData

    /// Measurements in the post, with the previous value of each.
    private var composition: [(BodyMetric, Double, Double?)] {
        var result: [(BodyMetric, Double, Double?)] = []
        if let fat = weight.fat { result.append((.fat, fat, weight.previousFat)) }
        if let muscle = weight.muscle { result.append((.muscle, muscle, weight.previousMuscle)) }
        if let bone = weight.bone { result.append((.bone, bone, nil)) }
        return result
    }

    var body: some View {
        let goal = weight.goal.map { WeightGoal(start: weight.start ?? weight.previous ?? weight.kg, goal: $0) }
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                Text(kg(weight.kg)).font(.title2.bold())
                // Without a goal, going down counts as the good direction.
                TrendPill(metric: .weight, change: weight.previous.map { weight.kg - $0 }, goalLosing: goal?.losing ?? true)
            }
            if !composition.isEmpty {
                FlowLayout {
                    ForEach(composition, id: \.0) { item in
                        Pill(text: "\(item.0.label) \(metricText(item.0, item.1))", tint: metricColor(item.0).opacity(0.2))
                        TrendPill(metric: item.0, change: item.2.map { item.1 - $0 })
                    }
                }
            }
            if let goal {
                let progress = goal.progress(weight.kg)
                ProgressBar(value: progress, color: Brand.amber)
                Text("Meta \(kg(goal.goal)) · \(Int(progress * 100))% del camino").font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}

/// Comments of one post, with a box to write a new one.
struct CommentsSheet: View {
    @Environment(AppModel.self) private var app
    let model: GroupDetailModel
    let post: PostRef
    @State private var text = ""
    @State private var food: String?
    @State private var confirmDelete: MealComment?

    var body: some View {
        let what: String = {
            switch post.type {
            case PostType.workout: return "Entrenamiento de \(post.displayName)"
            case PostType.weight: return "Peso de \(post.displayName)"
            default: return "Comidas de \(post.displayName) · \(dayText(post.day))"
            }
        }()
        let comments = model.postComments
        NavigationStack {
            VStack(spacing: 0) {
                List {
                    Section {
                        if model.commentsLoading && comments.isEmpty {
                            ProgressView().frame(maxWidth: .infinity)
                        } else if comments.isEmpty {
                            Text("Todavía no hay comentarios. ¡Deja el primero!").foregroundStyle(.secondary)
                        }
                        ForEach(comments) { comment in
                            HStack(alignment: .top, spacing: 10) {
                                Avatar(name: comment.authorName, seed: comment.authorId, size: 32)
                                VStack(alignment: .leading, spacing: 2) {
                                    HStack {
                                        Text(comment.authorName + (comment.authorId == model.myId ? " (tú)" : "")).font(.subheadline.weight(.semibold))
                                        Spacer()
                                        Text(timeAgo(comment.createdAt)).font(.caption2).foregroundStyle(.secondary)
                                    }
                                    if let meal = comment.mealName {
                                        Text("sobre \(meal)").font(.caption2).foregroundStyle(AppColor.primary)
                                    }
                                    Text(comment.body)
                                }
                            }
                            .swipeActions {
                                if comment.authorId == model.myId {
                                    Button("Eliminar", role: .destructive) { confirmDelete = comment }
                                }
                            }
                            .contextMenu {
                                if comment.authorId == model.myId {
                                    Button("Eliminar", role: .destructive) { confirmDelete = comment }
                                }
                            }
                        }
                    } header: {
                        Text(what)
                    }
                }
                .listStyle(.insetGrouped)
                VStack(alignment: .leading, spacing: 8) {
                    if let error = model.commentsError { ErrorText(message: error) }
                    if !post.foods.isEmpty {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 6) {
                                Pill(text: "Todo el día", selected: food == nil) { food = nil }
                                ForEach(post.foods, id: \.self) { name in
                                    Pill(text: name, selected: food == name) { food = name }
                                }
                            }
                        }
                    }
                    HStack(alignment: .bottom, spacing: 8) {
                        TextField(food.map { "Comenta sobre \($0)" } ?? "Escribe un comentario", text: $text, axis: .vertical)
                            .lineLimit(1...4)
                            .textInputAutocapitalization(.sentences)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 10)
                            .background(AppColor.cardHigh, in: RoundedRectangle(cornerRadius: 20))
                            .onChange(of: text) { _, new in if new.count > 500 { text = String(new.prefix(500)) } }
                        Button {
                            let message = text.trimmingCharacters(in: .whitespacesAndNewlines)
                            Task { if await model.sendComment(app, food: food, body: message) { text = "" } }
                        } label: {
                            Image(systemName: "paperplane.fill").font(.title3).padding(10)
                        }
                        .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.sending)
                        .accessibilityLabel("Enviar")
                    }
                }
                .padding()
                .background(AppColor.card)
            }
            .navigationTitle("Comentarios")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear { food = post.initialFood }
            .alert("Eliminar comentario", isPresented: Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } })) {
                Button("Eliminar", role: .destructive) {
                    if let comment = confirmDelete { Task { await model.deleteComment(app, comment) } }
                    confirmDelete = nil
                }
                Button("Cancelar", role: .cancel) { confirmDelete = nil }
            }
        }
        .presentationDetents([.large])
    }
}

/// A member's profile: weight progress and recent workouts with their best lifts.
struct MemberSheet: View {
    let member: GroupMember
    let isMe: Bool
    let progress: MemberProgress?
    let loading: Bool
    let error: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 14) {
                        Avatar(name: member.displayName, seed: member.userId, size: 64)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(member.displayName + (isMe ? " (tú)" : "")).font(.title2.bold())
                            if member.isOwner { Text("👑 Creó el grupo").font(.caption).foregroundStyle(.secondary) }
                        }
                    }
                    if loading && progress == nil { ProgressView().frame(maxWidth: .infinity).padding() }
                    if let error { ErrorText(message: error) }
                    if let progress {
                        MemberWeight(progress: progress)
                        workouts(progress.workouts)
                    }
                }
                .padding()
            }
            .screenBackground()
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
    }

    @ViewBuilder
    private func workouts(_ workouts: [MemberWorkout]) -> some View {
        if workouts.isEmpty {
            Text("Todavía no comparte entrenamientos.").foregroundStyle(.secondary)
        } else {
            let bests = Gym.bests(workouts.map(\.exercises))
            // Latest name and unit of each exercise (kilos and plates are separate marks).
            let latest = Dictionary(workouts.reversed().flatMap(\.exercises).map { (Gym.key($0), $0) }, uniquingKeysWith: { _, last in last })
            let weekAgo = Day.today.adding(days: -6).iso
            HStack {
                StatTile(value: "\(workouts.filter { $0.day >= weekAgo }.count)", label: "entrenos 7 días")
                StatTile(value: "\(workouts.reduce(0) { $0 + $1.prCount })", label: "récords")
                StatTile(value: "\(bests.count)", label: "ejercicios")
            }
            .card(padding: 12)
            Text("Mejores marcas").font(.headline)
            VStack(alignment: .leading, spacing: 4) {
                let top = bests.sorted { a, b in
                    let platesA = latest[a.key]?.unit == .plates, platesB = latest[b.key]?.unit == .plates
                    if platesA != platesB { return !platesA }
                    return a.value.estimatedMax > b.value.estimatedMax
                }.prefix(6)
                ForEach(Array(top), id: \.key) { entry in
                    HStack {
                        Text(latest[entry.key]?.name ?? entry.key)
                        Spacer()
                        Text("🏆 \(liftLabel(entry.value, latest[entry.key]?.unit ?? .kg))").fontWeight(.semibold)
                    }
                    .font(.subheadline)
                }
            }
            .card(padding: 14)
            Text("Entrenamientos recientes").font(.headline)
            ForEach(workouts.prefix(10)) { workout in
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text(workout.title).font(.subheadline.weight(.semibold))
                        Spacer()
                        Text([workout.category, dayText(workout.day)].compactMap { $0 }.joined(separator: " · "))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    WorkoutStatsRow(exercises: workout.exercises, durationMin: workout.durationMin, prCount: workout.prCount)
                    ExerciseLines(exercises: workout.exercises, limit: 4)
                }
                .card(padding: 14)
            }
        }
    }
}

private struct MemberWeight: View {
    let progress: MemberProgress

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("⚖️ Peso y composición").font(.headline)
            if !progress.shareWeight {
                Text("Prefiere no compartir su peso.").foregroundStyle(.secondary)
            } else if progress.weights.isEmpty && progress.goalWeight == nil {
                Text("Todavía no registra su peso.").foregroundStyle(.secondary)
            } else {
                let current = progress.weights.last?.kg
                let goal = progress.goalWeight.map { WeightGoal(start: progress.startWeight ?? progress.weights.first?.kg ?? $0, goal: $0) }
                HStack {
                    StatTile(value: current.map(kg) ?? "—", label: "Actual")
                    StatTile(value: goal.map { kg($0.goal) } ?? "—", label: "Meta")
                    StatTile(value: goal.map { kg($0.start) } ?? "—", label: "Inicio")
                }
                if let goal, let current {
                    let fraction = goal.progress(current)
                    ProgressBar(value: fraction, color: Brand.amber)
                    Text("\(Int(fraction * 100))% del camino a su meta").font(.caption).foregroundStyle(.secondary)
                }
                let logs = progress.weights.map(\.log)
                CompositionRow(logs: logs)
                BodyChart(logs: logs, goalKg: goal?.goal)
            }
        }
        .card()
    }
}
