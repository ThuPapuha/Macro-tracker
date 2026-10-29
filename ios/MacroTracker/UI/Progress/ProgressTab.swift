import SwiftUI

enum ProgressRoute: Hashable {
    case workout(routineId: String?)
    case routine(id: String?, sectionId: String?)
}

/// Best lift ever and progress of each exercise, most trained first.
struct ExerciseRecord: Hashable {
    let name: String
    let unit: LoadUnit
    let best: BestLift
    let sessions: Int
    let points: [ExercisePoint]
}

func exerciseRecords(_ workouts: [Workout]) -> [ExerciseRecord] {
    let ordered = workouts.sorted { $0.finishedAt < $1.finishedAt }
    let history = ordered.map { ($0.date.iso, $0.exercises) }
    // Show each exercise with the name it was last written with.
    var latest: [String: ExerciseLog] = [:]
    for exercise in ordered.flatMap(\.exercises) { latest[Gym.key(exercise)] = exercise }
    return Gym.bests(history.map(\.1))
        .compactMap { entry -> ExerciseRecord? in
            guard let exercise = latest[entry.key] else { return nil }
            let points = Gym.progress(exercise.name, history, unit: exercise.unit)
            return ExerciseRecord(name: exercise.name, unit: exercise.unit, best: entry.value, sessions: points.count, points: points)
        }
        .sorted { ($0.sessions, $1.name) > ($1.sessions, $0.name) }
}

private enum ProgressSheet: Identifiable {
    case weight, history, goal
    case editWeight(Day)
    case section(RoutineSection?)
    case record(ExerciseRecord)

    var id: String {
        switch self {
        case .weight: return "weight"
        case .history: return "history"
        case .goal: return "goal"
        case .editWeight(let day): return "edit-\(day.iso)"
        case .section(let section): return "section-\(section?.id ?? "new")"
        case .record(let record): return "record-\(Gym.key(record.name, record.unit))"
        }
    }
}

/// The Progreso tab: body weight, routines, records and workout history.
struct ProgressTab: View {
    @Environment(AppModel.self) private var app
    @State private var route: ProgressRoute?
    @State private var sheet: ProgressSheet?
    @State private var pendingStart: Routine?
    @State private var askStart = false
    @State private var deletingWorkout: Workout?
    @State private var deletingSection: RoutineSection?
    @State private var historyFilter: String?
    /// Ids of folded sections, joined with commas.
    @AppStorage("collapsedSections") private var collapsed = ""

    var body: some View {
        let progress = app.progress
        let sections = progress.sections
        let routines = progress.routines
        let workouts = progress.workouts
        let weights = progress.weights
        let records = exerciseRecords(workouts)
        let folded = Set(collapsed.split(separator: ",").map(String.init))
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if progress.activeWorkout != nil {
                    Button { route = .workout(routineId: nil) } label: {
                        GradientCard(gradient: Brand.gym) {
                            Text("🔥 Entrenamiento en curso").font(.headline)
                            Text("Toca para seguir donde lo dejaste.").font(.callout)
                        }
                    }
                    .buttonStyle(.plain)
                }
                WeekStats(workouts: workouts)
                WeightCard(weights: weights, goal: app.settings.weightGoal,
                           onLog: { sheet = .weight }, onGoal: { sheet = .goal }, onHistory: { sheet = .history })

                SectionHeader(title: "Entrenar", action: "+ Sección") { sheet = .section(nil) }
                if routines.isEmpty && sections.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Arma tus rutinas").font(.headline)
                        Text("Ordénalas en secciones como Hipertrofia o Fuerza, o crea las tuyas. En cada rutina pones tus ejercicios con sus series, repeticiones y peso (en kg o en discos). Al entrenar cambias las repes y la carga de cada serie, y la rutina recuerda lo último que hiciste.")
                            .font(.callout).foregroundStyle(.secondary)
                        HStack {
                            Button("Crear sección") { sheet = .section(nil) }.buttonStyle(.borderedProminent)
                            Button("Crear rutina") { route = .routine(id: nil, sectionId: nil) }.buttonStyle(.bordered)
                        }
                    }
                    .card()
                }
                ForEach(sections) { section in
                    let inSection = routines.filter { $0.sectionId == section.id }
                    let isFolded = folded.contains(section.id)
                    RoutineSectionHeader(
                        title: section.name,
                        count: inSection.count,
                        folded: isFolded,
                        onToggle: {
                            var next = folded
                            if isFolded { next.remove(section.id) } else { next.insert(section.id) }
                            collapsed = next.sorted().joined(separator: ",")
                        },
                        onAdd: { route = .routine(id: nil, sectionId: section.id) },
                        onRename: { sheet = .section(section) },
                        onDelete: { deletingSection = section }
                    )
                    if !isFolded {
                        if inSection.isEmpty {
                            Button { route = .routine(id: nil, sectionId: section.id) } label: {
                                Text("+ Agregar rutina a \(section.name)").frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.bordered)
                        }
                        ForEach(inSection) { routine in
                            RoutineCard(routine: routine, onStart: { start(routine) },
                                        onEdit: { route = .routine(id: routine.id, sectionId: nil) })
                        }
                    }
                }
                let known = Set(sections.map(\.id))
                let loose = routines.filter { $0.sectionId.map { !known.contains($0) } ?? true }
                if !loose.isEmpty {
                    if !sections.isEmpty {
                        RoutineSectionHeader(title: "Sin sección", count: loose.count,
                                             onAdd: { route = .routine(id: nil, sectionId: nil) })
                    }
                    ForEach(loose) { routine in
                        RoutineCard(routine: routine, onStart: { start(routine) },
                                    onEdit: { route = .routine(id: routine.id, sectionId: nil) })
                    }
                }
                HStack {
                    Button { route = .routine(id: nil, sectionId: nil) } label: { Text("Nueva rutina").frame(maxWidth: .infinity) }
                    Button { start(nil) } label: { Text("Entreno libre").frame(maxWidth: .infinity) }
                }
                .buttonStyle(.bordered)
                .controlSize(.large)

                if !records.isEmpty {
                    SectionHeader(title: "Récords")
                    VStack(spacing: 0) {
                        ForEach(records.prefix(8), id: \.self) { record in
                            Button { sheet = .record(record) } label: {
                                HStack {
                                    Text(record.name).lineLimit(1)
                                    Spacer()
                                    Text("🏆 \(liftLabel(record.best, record.unit))  ›").foregroundStyle(.secondary)
                                }
                                .padding(.vertical, 10)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            if record != records.prefix(8).last { Divider() }
                        }
                    }
                    .card(padding: 14)
                }

                if !workouts.isEmpty { SectionHeader(title: "Historial") }
                let workoutSections = workouts.compactMap(\.section).reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }
                if !workoutSections.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            Pill(text: "Todos", selected: historyFilter == nil) { historyFilter = nil }
                            ForEach(workoutSections, id: \.self) { name in
                                Pill(text: name, selected: historyFilter == name) { historyFilter = name }
                            }
                        }
                    }
                }
                ForEach(historyFilter.map { name in workouts.filter { $0.section == name } } ?? workouts) { workout in
                    WorkoutHistoryCard(workout: workout) { deletingWorkout = workout }
                }
            }
            .padding()
        }
        .screenBackground()
        .navigationTitle("Progreso")
        .navigationDestination(item: $route) { route in
            switch route {
            case .workout(let routineId): WorkoutView(routineId: routineId)
            case .routine(let id, let sectionId): RoutineEditorView(routineId: id, initialSectionId: sectionId)
            }
        }
        .sheet(item: $sheet) { sheet in
            switch sheet {
            case .weight:
                BodyLogSheet(entries: weights) { app.progress.logWeight($0) }
            case .editWeight(let day):
                BodyLogSheet(entries: weights, fixedDate: day, onDelete: { app.progress.deleteWeight($0) }) { app.progress.logWeight($0) }
            case .history:
                BodyHistorySheet(entries: weights) { self.sheet = .editWeight($0.date) }
            case .goal:
                WeightGoalSheet(goal: app.settings.weightGoal, currentKg: weights.last?.kg, share: app.settings.shareWeight) { goal, share in
                    app.settings.setWeightGoal(goal, share: share)
                    app.sharedSettingsChanged()
                }
            case .section(let section):
                SectionSheet(section: section, existing: sections.map(\.name)) { app.progress.saveSection(name: $0, id: section?.id) }
            case .record(let record):
                RecordSheet(record: record)
            }
        }
        .alert("Tienes un entrenamiento en curso", isPresented: $askStart) {
            Button("Descartar y empezar", role: .destructive) {
                app.progress.setActiveWorkout(nil)
                route = .workout(routineId: pendingStart?.id)
            }
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text("Si empiezas otro, se descarta el que tienes sin terminar.")
        }
        .alert("Borrar entrenamiento", isPresented: Binding(get: { deletingWorkout != nil }, set: { if !$0 { deletingWorkout = nil } })) {
            Button("Borrar", role: .destructive) {
                if let workout = deletingWorkout { app.progress.deleteWorkout(workout.id) }
                deletingWorkout = nil
            }
            Button("Cancelar", role: .cancel) { deletingWorkout = nil }
        } message: {
            Text(deletingWorkout.map { "Se borrará \"\($0.title)\" de \(dayLabel($0.date)), también de tus grupos." } ?? "")
        }
        .alert("Borrar sección", isPresented: Binding(get: { deletingSection != nil }, set: { if !$0 { deletingSection = nil } })) {
            Button("Borrar", role: .destructive) {
                if let section = deletingSection { app.progress.deleteSection(section.id) }
                deletingSection = nil
            }
            Button("Cancelar", role: .cancel) { deletingSection = nil }
        } message: {
            Text(deletingSection.map { "Se borrará la sección \"\($0.name)\". Sus rutinas no se borran: quedan en \"Sin sección\"." } ?? "")
        }
    }

    private func start(_ routine: Routine?) {
        if app.progress.activeWorkout != nil {
            pendingStart = routine
            askStart = true
        } else {
            route = .workout(routineId: routine?.id)
        }
    }
}

private struct WeekStats: View {
    let workouts: [Workout]

    var body: some View {
        let weekAgo = Day.today.adding(days: -6)
        let week = workouts.filter { $0.date >= weekAgo }
        var bySection: [String: Int] = [:]
        for name in week.compactMap(\.section) { bySection[name, default: 0] += 1 }
        return GradientCard(gradient: Brand.gym) {
            Text("Esta semana").font(.subheadline.weight(.semibold))
            HStack {
                StatTile(value: "\(week.count)", label: "entrenos", color: .white)
                StatTile(value: "\(week.reduce(0) { $0 + $1.prCount })", label: "récords", color: .white)
                StatTile(value: formatAmount(week.reduce(0) { $0 + Gym.totalVolume($1.exercises) }), label: "kg movidos", color: .white)
            }
            if !bySection.isEmpty {
                Text(bySection.sorted { ($0.value, $1.key) > ($1.value, $0.key) }.map { "\($0.key) \($0.value)" }.joined(separator: " · "))
                    .font(.caption.weight(.semibold))
            }
        }
    }
}

/// Title of a section of routines, with a button to add a routine to it and a menu.
private struct RoutineSectionHeader: View {
    let title: String
    let count: Int
    var folded = false
    var onToggle: (() -> Void)? = nil
    let onAdd: () -> Void
    var onRename: (() -> Void)? = nil
    var onDelete: (() -> Void)? = nil

    var body: some View {
        HStack(spacing: 8) {
            Button { onToggle?() } label: {
                HStack(spacing: 6) {
                    if onToggle != nil {
                        Image(systemName: folded ? "chevron.right" : "chevron.down").font(.subheadline.weight(.bold))
                    }
                    VStack(alignment: .leading, spacing: 0) {
                        Text(title).font(.headline).foregroundStyle(AppColor.primary)
                        Text(count == 1 ? "1 rutina" : "\(count) rutinas").font(.caption2).foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(onToggle == nil)
            .accessibilityLabel(folded ? "Mostrar rutinas de \(title)" : "Ocultar rutinas de \(title)")
            Button(action: onAdd) { Image(systemName: "plus") }
                .buttonStyle(.borderless)
                .accessibilityLabel("Agregar rutina a \(title)")
            if onRename != nil || onDelete != nil {
                Menu {
                    if let onRename { Button("Cambiar nombre", action: onRename) }
                    if let onDelete { Button("Borrar sección", role: .destructive, action: onDelete) }
                } label: {
                    Image(systemName: "ellipsis").padding(6)
                }
                .accessibilityLabel("Opciones de \(title)")
            }
        }
        .padding(.top, 4)
    }
}

private struct RoutineCard: View {
    let routine: Routine
    let onStart: () -> Void
    let onEdit: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(routine.name).font(.headline)
                Text(routine.exercises.isEmpty ? "Sin ejercicios"
                     : "\(routine.exercises.count) ejercicios · " + routine.exercises.map(\.name).joined(separator: ", "))
                    .font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
            Spacer()
            Button(action: onEdit) { Image(systemName: "pencil") }
                .buttonStyle(.borderless)
                .accessibilityLabel("Editar rutina")
            Button(action: onStart) { Label("Empezar", systemImage: "play.fill") }
                .buttonStyle(.borderedProminent)
                .tint(Brand.pink)
        }
        .card(padding: 14)
    }
}

private struct WorkoutHistoryCard: View {
    let workout: Workout
    let onDelete: () -> Void
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(workout.title).font(.headline)
                    Text([dayLabel(workout.date), workout.section].compactMap { $0 }.joined(separator: " · "))
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Menu {
                    Button("Borrar", role: .destructive, action: onDelete)
                } label: {
                    Image(systemName: "ellipsis").padding(6)
                }
                .accessibilityLabel("Opciones")
            }
            WorkoutStatsRow(exercises: workout.exercises, durationMin: workout.durationMin, prCount: workout.prCount)
            ExerciseLines(exercises: workout.exercises, limit: expanded ? Int.max : 3)
        }
        .card(padding: 14)
        .contentShape(Rectangle())
        .onTapGesture { withAnimation { expanded.toggle() } }
    }
}

private struct RecordSheet: View {
    let record: ExerciseRecord
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    Text("Récord: \(liftLabel(record.best, record.unit))").font(.title3.bold())
                    if record.best.weight > 0 && record.unit == .kg {
                        Text("1RM estimado: \(formatAmount(record.best.estimatedMax)) kg")
                    }
                    if record.points.count >= 2 {
                        Text("Mejor serie de cada sesión").font(.subheadline.weight(.semibold))
                        LineChartView(values: record.points.map { record.best.weight > 0 ? $0.best.weight : Double($0.best.reps) },
                                      color: Brand.pink)
                    }
                    ForEach(Array(record.points.suffix(6).reversed().enumerated()), id: \.offset) { item in
                        HStack {
                            Text(Day(iso: item.element.day).map { dayLabel($0) } ?? item.element.day).font(.caption)
                            Spacer()
                            Text(liftLabel(item.element.best, record.unit)).font(.caption)
                        }
                    }
                }
                .padding()
            }
            .navigationTitle(record.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Cerrar") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }
}
