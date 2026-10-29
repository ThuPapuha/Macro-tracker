import SwiftUI

/// Where "Agregar comida" goes.
struct AddFoodRoute: Hashable {
    let date: Day
    let meal: MealType
}

/// The day's food: summary ring, week budget and meals. On the main tab it also greets the user and
/// offers quick actions; opened from Historial it shows that day.
struct TodayView: View {
    @Environment(AppModel.self) private var app
    let isMainTab: Bool
    @State private var date: Day
    @State private var editing: FoodEntry?
    @State private var loggingWeight = false
    @State private var addRoute: AddFoodRoute?

    init(initialDate: Day? = nil) {
        isMainTab = initialDate == nil
        _date = State(initialValue: initialDate ?? .today)
    }

    var body: some View {
        let entries = app.food.entries(on: date)
        let targets = app.settings.targets
        ScrollView {
            VStack(spacing: 12) {
                if isMainTab {
                    hero
                    quickActions
                }
                MacroSummaryCard(totals: Macros.sum(entries.map(\.macros)), targets: targets)
                if let targets, targets.calories > 0 {
                    let start = WeeklyBudget.weekStart(date)
                    WeekCard(week: WeeklyBudget.summarize(app.food.intakes(from: start, to: start.adding(days: 6)),
                                                          daily: targets, date: date, today: .today))
                } else {
                    Button { app.selectedTab = .profile } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Configura tus objetivos").font(.headline)
                            Text("Ve a Perfil para subir el plan de tu nutricionista o calcular tus calorías y macros.")
                                .font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.leading)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(16)
                        .background(AppColor.primarySoft, in: RoundedRectangle(cornerRadius: 22))
                    }
                    .buttonStyle(.plain)
                }
                ForEach(MealType.allCases) { meal in
                    MealCard(meal: meal, entries: entries.filter { $0.meal == meal },
                             onAdd: { addRoute = AddFoodRoute(date: date, meal: meal) },
                             onTap: { editing = $0 })
                }
                Spacer(minLength: 80)
            }
            .padding(.horizontal)
        }
        .screenBackground()
        .overlay(alignment: .bottomTrailing) {
            Button {
                addRoute = AddFoodRoute(date: date, meal: .forHour(Calendar.current.component(.hour, from: Date())))
            } label: {
                Label("Agregar comida", systemImage: "plus")
                    .font(.headline)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 14)
                    .background(AppColor.primary, in: Capsule())
                    .foregroundStyle(.white)
                    .shadow(radius: 6, y: 3)
            }
            .padding()
        }
        .navigationTitle(dayLabel(date))
        .navigationBarTitleDisplayMode(isMainTab ? .large : .inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button { date = date.adding(days: -1) } label: { Image(systemName: "chevron.left") }
                if date != .today { Button("Hoy") { date = .today } }
                Button { date = date.adding(days: 1) } label: { Image(systemName: "chevron.right") }
            }
        }
        .navigationDestination(item: $addRoute) { route in
            AddFoodView(date: route.date, initialMeal: route.meal)
        }
        .sheet(item: $editing) { entry in
            FoodEditSheet(entry: entry, onSave: { app.food.update($0) }, onDelete: { app.food.delete(entry) })
        }
        .sheet(isPresented: $loggingWeight) {
            BodyLogSheet(entries: app.progress.weights) { app.progress.logWeight($0) }
        }
    }

    private var greeting: String {
        switch Calendar.current.component(.hour, from: Date()) {
        case 5...11: return "Buenos días"
        case 12...19: return "Buenas tardes"
        default: return "Buenas noches"
        }
    }

    /// Consecutive days with food logged, up to today (or yesterday if today is still empty).
    private var streak: Int {
        let logged = Set(app.food.entries.map(\.date))
        var day = Day.today
        if !logged.contains(day) { day = day.adding(days: -1) }
        var count = 0
        while logged.contains(day) {
            count += 1
            day = day.adding(days: -1)
        }
        return count
    }

    private var hero: some View {
        let name = app.settings.displayName
        let firstName = name.split(separator: " ").first.map(String.init) ?? ""
        let days = streak
        return HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(greeting + (firstName.isEmpty ? "" : ", \(firstName)")).font(.title2.bold())
                Text(days > 0 ? "🔥 \(days) \(days == 1 ? "día" : "días") seguidos registrando" : "Registra hoy y empieza tu racha 🔥")
                    .font(.callout).foregroundStyle(.secondary)
            }
            Spacer()
            Button { app.selectedTab = .profile } label: {
                Avatar(name: name.isEmpty ? "Yo" : name, seed: app.sessionStore.loadSession()?.userId ?? name, size: 48)
            }
        }
    }

    private var quickActions: some View {
        HStack(spacing: 10) {
            QuickAction(emoji: "🍽️", label: "Comida") {
                addRoute = AddFoodRoute(date: date, meal: .forHour(Calendar.current.component(.hour, from: Date())))
            }
            QuickAction(emoji: "🏋️", label: "Entrenar") { app.selectedTab = .progress }
            QuickAction(emoji: "⚖️", label: "Peso") { loggingWeight = true }
        }
    }
}

private struct QuickAction: View {
    let emoji: String
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Text(emoji).font(.title2)
                Text(label).font(.subheadline.weight(.semibold))
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(AppColor.card, in: RoundedRectangle(cornerRadius: 20))
        }
        .buttonStyle(.plain)
    }
}

private struct MealCard: View {
    let meal: MealType
    let entries: [FoodEntry]
    let onAdd: () -> Void
    let onTap: (FoodEntry) -> Void

    var body: some View {
        let total = Macros.sum(entries.map(\.macros))
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(meal.emoji) \(meal.label)").font(.headline)
                    if !entries.isEmpty {
                        Text("\(kcal(total.calories)) · \(macroLine(total))").font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Button(action: onAdd) { Image(systemName: "plus.circle.fill").font(.title2) }
                    .accessibilityLabel("Agregar a \(meal.label)")
            }
            .padding(.bottom, entries.isEmpty ? 4 : 8)
            if entries.isEmpty {
                Text("Sin registros").font(.footnote).foregroundStyle(.secondary)
            }
            ForEach(entries) { entry in
                Button { onTap(entry) } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(entry.name).lineLimit(1)
                            if !entry.quantity.isEmpty { Text(entry.quantity).font(.caption).foregroundStyle(.secondary) }
                        }
                        Spacer()
                        VStack(alignment: .trailing, spacing: 2) {
                            Text(kcal(entry.calories)).font(.subheadline.weight(.semibold))
                            Text(macroLine(entry.macros)).font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 8)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                if entry.id != entries.last?.id { Divider() }
            }
        }
        .card()
    }
}
