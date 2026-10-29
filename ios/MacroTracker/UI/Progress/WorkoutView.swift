import SwiftUI

private func elapsed(since startedAt: Int64, now: Date) -> String {
    let seconds = max(Int64(now.timeIntervalSince1970 * 1000) - startedAt, 0) / 1000
    let h = seconds / 3600
    let m = (seconds % 3600) / 60
    let s = seconds % 60
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
}

/// A workout in progress: edit reps and load of each set and tick them as you go. Leaving keeps it
/// saved as "in progress".
struct WorkoutView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let routineId: String?

    @State private var title = "Entrenamiento"
    @State private var exercises: [EditableExercise] = []
    @State private var startedAt: Int64 = 0
    @State private var date = Day.today
    @State private var section: String?
    @State private var currentRoutineId: String?
    /// Best lift ever per exercise (by Gym.key) before this workout.
    @State private var records: [String: BestLift] = [:]
    @State private var loaded = false
    @State private var closed = false
    @State private var finished: Workout?
    @State private var error: String?
    @State private var adding = false
    @State private var renaming = false
    @State private var newTitle = ""
    @State private var confirmFinish = false
    @State private var confirmDiscard = false

    private var doneSets: Int { exercises.reduce(0) { $0 + $1.sets.filter(\.done).count } }
    private var totalSets: Int { exercises.reduce(0) { $0 + $1.sets.count } }

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                if exercises.isEmpty {
                    Text("Agrega los ejercicios que vas a hacer. Cambia las repes y la carga de cada serie y márcala con ✓ cuando la termines.")
                        .font(.callout).foregroundStyle(.secondary)
                }
                if let error { ErrorText(message: error) }
                ForEach($exercises) { $exercise in
                    ExerciseCard(
                        exercise: $exercise,
                        training: true,
                        record: records[Gym.key(exercise.name, exercise.unit)],
                        onRemove: { exercises.removeAll { $0.id == exercise.id } }
                    )
                }
                Button { adding = true } label: { Label("Agregar ejercicio", systemImage: "plus").frame(maxWidth: .infinity) }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                Button { confirmFinish = true } label: { Text("Terminar entrenamiento").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
            }
            .padding()
        }
        .scrollDismissesKeyboard(.interactively)
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 0) {
                    Text(title).font(.headline).lineLimit(1)
                    if loaded {
                        TimelineView(.periodic(from: .now, by: 1)) { context in
                            Text([section, "⏱ \(elapsed(since: startedAt, now: context.date))", "\(doneSets)/\(totalSets) series"]
                                .compactMap { $0 }.joined(separator: " · "))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button("Terminar") { confirmFinish = true }
                    Button("Cambiar nombre") {
                        newTitle = title
                        renaming = true
                    }
                    Button("Descartar entrenamiento", role: .destructive) { confirmDiscard = true }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .keyboardDoneButton()
        .onAppear(perform: load)
        .onChange(of: exercises) { _, _ in persist() }
        .onChange(of: title) { _, _ in persist() }
        .sheet(isPresented: $adding) {
            AddExerciseSheet { exercises.append(EditableExercise(empty: $0)) }
        }
        .alert("Nombre del entrenamiento", isPresented: $renaming) {
            TextField("Nombre", text: $newTitle)
            Button("Guardar") {
                let clean = newTitle.trimmingCharacters(in: .whitespacesAndNewlines)
                if !clean.isEmpty { title = String(clean.prefix(60)) }
            }
            Button("Cancelar", role: .cancel) {}
        }
        .confirmationDialog("Terminar entrenamiento", isPresented: $confirmFinish, titleVisibility: .visible) {
            if totalSets - doneSets > 0 {
                Button("Marcar todas y guardar") { finish(markAll: true) }
                if doneSets > 0 { Button("Guardar solo las marcadas") { finish(markAll: false) } }
            } else {
                Button("Guardar") { finish(markAll: false) }
            }
            Button("Seguir entrenando", role: .cancel) {}
        } message: {
            if totalSets - doneSets == 0 {
                Text("Se guardará y lo verán tus grupos. La rutina recordará las repes y la carga de hoy.")
            } else {
                Text("Marcaste \(doneSets) de \(totalSets) series como hechas. Las que no marques no se guardan.")
            }
        }
        .alert("Descartar entrenamiento", isPresented: $confirmDiscard) {
            Button("Descartar", role: .destructive) {
                closed = true
                app.progress.setActiveWorkout(nil)
                dismiss()
            }
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text("Se borrará lo que registraste en este entrenamiento.")
        }
        .sheet(item: $finished, onDismiss: { dismiss() }) { workout in
            FinishedWorkoutSheet(workout: workout)
        }
    }

    private func load() {
        guard !loaded else { return }
        // A workout left open (app closed at the gym) always comes back first.
        if let draft = app.progress.activeWorkout {
            title = draft.title
            currentRoutineId = draft.routineId
            date = draft.date
            startedAt = draft.startedAt
            section = draft.section
            exercises = draft.exercises.map { EditableExercise($0) }
        } else {
            let routine = routineId.flatMap { app.progress.routine($0) }
            title = routine?.name ?? "Entrenamiento libre"
            currentRoutineId = routine?.id
            section = app.progress.sectionName(routine?.sectionId)
            startedAt = nowMillis()
            exercises = Gym.startFrom(routine?.exercises ?? []).map { EditableExercise($0) }
        }
        records = Gym.bests(app.progress.history)
        loaded = true
        persist()
    }

    private func persist() {
        guard loaded, !closed else { return }
        app.progress.setActiveWorkout(ActiveWorkout(title: title, routineId: currentRoutineId, date: date, startedAt: startedAt,
                                                    exercises: exercises.map(\.log), section: section))
    }

    /// Saves the workout. With [markAll], sets not ticked count as done (people forget to tick).
    private func finish(markAll: Bool) {
        let logs = exercises.map { exercise -> ExerciseLog in
            var log = exercise.log
            if markAll { log.sets = log.sets.map { WorkoutSet(reps: $0.reps, weight: $0.weight, done: true) } }
            return log
        }
        guard let workout = app.progress.finishWorkout(title: title, routineId: currentRoutineId, exercises: logs, date: date,
                                                       startedAt: startedAt, section: section) else {
            error = "Marca al menos una serie con repeticiones como hecha."
            return
        }
        closed = true
        app.progress.setActiveWorkout(nil)
        finished = workout
    }
}

private struct FinishedWorkoutSheet: View {
    let workout: Workout
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text(workout.prCount > 0 ? "🏆 ¡Nuevos récords!" : "💪 ¡Buen entrenamiento!").font(.title2.bold())
                    WorkoutStatsRow(exercises: workout.exercises, durationMin: workout.durationMin, prCount: workout.prCount)
                    ExerciseLines(exercises: workout.exercises)
                }
                .padding()
            }
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Listo") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }
}
