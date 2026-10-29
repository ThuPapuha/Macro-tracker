import SwiftUI

/// A set being typed: text fields keep what the user wrote until it is saved.
struct EditableSet: Identifiable, Hashable {
    var id = UUID()
    var reps = ""
    var weight = ""
    var done = false

    var workoutSet: WorkoutSet { WorkoutSet(reps: Int(reps.trimmingCharacters(in: .whitespaces)) ?? 0, weight: toAmount(weight) ?? 0, done: done) }

    init(reps: String = "", weight: String = "", done: Bool = false) {
        self.reps = reps
        self.weight = weight
        self.done = done
    }

    init(_ set: WorkoutSet) {
        reps = set.reps > 0 ? String(set.reps) : ""
        weight = set.weight > 0 ? inputAmount(set.weight) : ""
        done = set.done
    }
}

struct EditableExercise: Identifiable, Hashable {
    var id = UUID()
    var name: String
    var sets: [EditableSet]
    var unit: LoadUnit = .kg

    var log: ExerciseLog { ExerciseLog(name: name.trimmingCharacters(in: .whitespacesAndNewlines), sets: sets.map(\.workoutSet), unit: unit) }

    init(_ log: ExerciseLog) {
        name = log.name
        sets = log.sets.map { EditableSet($0) }
        unit = log.unit
    }

    init(empty name: String) {
        self.name = name
        sets = (0..<3).map { _ in EditableSet(reps: "10") }
    }

    /// Adds a set copying the previous one, which is what people usually do next.
    mutating func addSet() {
        if var last = sets.last {
            last.id = UUID()
            last.done = false
            sets.append(last)
        } else {
            sets.append(EditableSet(reps: "10"))
        }
    }
}

let commonExercises = [
    "Sentadilla", "Press banca", "Peso muerto", "Press militar", "Remo con barra", "Dominadas",
    "Jalón al pecho", "Fondos", "Hip thrust", "Prensa", "Zancadas", "Curl de bíceps",
    "Extensión de tríceps", "Elevaciones laterales", "Press inclinado", "Peso muerto rumano",
    "Curl femoral", "Extensión de cuádriceps", "Pantorrillas", "Abdominales", "Plancha",
]

func liftLabel(_ lift: BestLift, _ unit: LoadUnit = .kg) -> String {
    lift.weight > 0 ? "\(unit.format(lift.weight)) × \(lift.reps)" : "\(lift.reps) repes"
}

/// Card to edit an exercise's sets. While [training], each set has a check to mark it done and
/// sets that beat the previous [record] get a trophy.
struct ExerciseCard: View {
    @Binding var exercise: EditableExercise
    let training: Bool
    let record: BestLift?
    let onRemove: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                if training {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(exercise.name).font(.headline).lineLimit(2)
                        if let record {
                            Text("Récord: \(liftLabel(record, exercise.unit))").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                } else {
                    TextField("Ejercicio", text: $exercise.name)
                        .textFieldStyle(.roundedBorder)
                        .textInputAutocapitalization(.sentences)
                }
                Spacer()
                Button(role: .destructive, action: onRemove) { Image(systemName: "trash") }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Quitar ejercicio")
            }
            // Machines are often counted in plates instead of kilos.
            HStack(spacing: 6) {
                Text("Carga en").font(.caption).foregroundStyle(.secondary)
                Pill(text: "Kg", selected: exercise.unit == .kg) { exercise.unit = .kg }
                Pill(text: "Discos", selected: exercise.unit == .plates) { exercise.unit = .plates }
            }
            HStack(spacing: 8) {
                Text("Serie").frame(width: 40, alignment: .leading)
                Text("Repes").frame(maxWidth: .infinity, alignment: .leading)
                Text(exercise.unit == .plates ? "Discos" : "Peso (kg)").frame(maxWidth: .infinity, alignment: .leading)
                Color.clear.frame(width: 36, height: 1)
            }
            .font(.caption.weight(.semibold))
            .foregroundStyle(.secondary)
            ForEach($exercise.sets) { $set in
                let number = (exercise.sets.firstIndex { $0.id == set.id } ?? 0) + 1
                let isRecord = training && set.done && Gym.isRecord(Gym.best([set.workoutSet]), record)
                HStack(spacing: 8) {
                    Text(isRecord ? "🏆" : "\(number)")
                        .font(.headline)
                        .foregroundStyle(set.done ? AppColor.primary : Color.secondary)
                        .frame(width: 40, alignment: .leading)
                    TextField("0", text: $set.reps)
                        .keyboardType(.numberPad)
                        .textFieldStyle(.roundedBorder)
                        .onChange(of: set.reps) { _, new in
                            let digits = String(new.filter(\.isNumber).prefix(3))
                            if digits != new { set.reps = digits }
                        }
                    NumberField(label: "", text: $set.weight)
                    if training {
                        Button { set.done.toggle() } label: {
                            Image(systemName: set.done ? "checkmark.circle.fill" : "circle")
                                .font(.title2)
                                .foregroundStyle(set.done ? AppColor.primary : Color.secondary)
                        }
                        .buttonStyle(.borderless)
                        .frame(width: 36)
                        .accessibilityLabel(set.done ? "Serie hecha" : "Marcar serie como hecha")
                    } else {
                        Button { exercise.sets.removeAll { $0.id == set.id } } label: { Image(systemName: "xmark") }
                            .buttonStyle(.borderless)
                            .frame(width: 36)
                            .accessibilityLabel("Quitar serie")
                    }
                }
            }
            Button { exercise.addSet() } label: { Label("Agregar serie", systemImage: "plus") }
                .buttonStyle(.borderless)
                .font(.subheadline.weight(.semibold))
        }
        .card(padding: 14)
    }
}

/// Sheet to name a new exercise, with common ones to pick from.
struct AddExerciseSheet: View {
    let onAdd: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    TextField("Nombre del ejercicio", text: $name)
                        .textFieldStyle(.roundedBorder)
                        .textInputAutocapitalization(.sentences)
                    let query = name.trimmingCharacters(in: .whitespaces)
                    FlowLayout {
                        ForEach(commonExercises.filter { query.isEmpty || $0.localizedCaseInsensitiveContains(query) }, id: \.self) { option in
                            Pill(text: option) { add(option) }
                        }
                    }
                }
                .padding()
            }
            .navigationTitle("Agregar ejercicio")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Agregar") { add(name) }.disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func add(_ value: String) {
        onAdd(String(value.trimmingCharacters(in: .whitespacesAndNewlines).prefix(60)))
        dismiss()
    }
}

/// "⏱ 55 min · 12 series · 4520 kg" chips for a finished workout.
struct WorkoutStatsRow: View {
    let exercises: [ExerciseLog]
    let durationMin: Int?
    let prCount: Int

    var body: some View {
        FlowLayout {
            if prCount > 0 {
                Pill(text: prCount == 1 ? "🏆 1 récord" : "🏆 \(prCount) récords", tint: Brand.record.opacity(0.22))
            }
            if let durationMin { Pill(text: "⏱ \(durationMin) min") }
            Pill(text: "\(Gym.completedSetCount(exercises)) series")
            let volume = Gym.totalVolume(exercises)
            if volume > 0 { Pill(text: "\(formatAmount(volume)) kg movidos") }
        }
    }
}

/// One line per exercise: "Sentadilla   3 × 8 · 80 kg  🏆".
struct ExerciseLines: View {
    let exercises: [ExerciseLog]
    var limit = Int.max

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(Array(exercises.prefix(limit).enumerated()), id: \.offset) { item in
                HStack {
                    Text(item.element.name + (item.element.pr ? "  🏆" : ""))
                        .font(.subheadline.weight(item.element.pr ? .semibold : .regular))
                        .lineLimit(1)
                    Spacer()
                    Text(Gym.describe(item.element.sets, unit: item.element.unit)).font(.caption).foregroundStyle(.secondary)
                }
            }
            if exercises.count > limit {
                Text("y \(exercises.count - limit) ejercicios más").font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}
