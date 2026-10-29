import SwiftUI

/// Create or edit a routine: its exercises and, for each one, the sets with reps and load.
struct RoutineEditorView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let routineId: String?
    let initialSectionId: String?

    @State private var name = ""
    @State private var sectionId: String?
    @State private var exercises: [EditableExercise] = []
    @State private var position = 0
    @State private var loaded = false
    @State private var error: String?
    @State private var adding = false
    @State private var creatingSection = false
    @State private var confirmDelete = false

    var body: some View {
        let sections = app.progress.sections
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                TextField("Nombre de la rutina (ej: Pierna, Empuje, Full body)", text: $name)
                    .textFieldStyle(.roundedBorder)
                    .textInputAutocapitalization(.sentences)
                    .onChange(of: name) { _, new in if new.count > 60 { name = String(new.prefix(60)) } }
                Text("Sección").font(.subheadline.weight(.semibold))
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        Pill(text: "Sin sección", selected: sectionId == nil) { sectionId = nil }
                        ForEach(sections) { section in
                            Pill(text: section.name, selected: sectionId == section.id) { sectionId = section.id }
                        }
                        Pill(text: "+ Nueva") { creatingSection = true }
                    }
                }
                Text("Pon las series con las repeticiones y la carga que haces hoy, en kg o en discos (para máquinas). Al terminar cada entrenamiento, la rutina se actualiza con lo que levantaste.")
                    .font(.footnote).foregroundStyle(.secondary)
                if let error { ErrorText(message: error) }
                ForEach($exercises) { $exercise in
                    ExerciseCard(exercise: $exercise, training: false, record: nil,
                                 onRemove: { exercises.removeAll { $0.id == exercise.id } })
                }
                Button { adding = true } label: { Label("Agregar ejercicio", systemImage: "plus").frame(maxWidth: .infinity) }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                if routineId != nil {
                    Button(role: .destructive) { confirmDelete = true } label: {
                        Label("Borrar rutina", systemImage: "trash").frame(maxWidth: .infinity)
                    }
                    .padding(.top, 8)
                }
            }
            .padding()
        }
        .scrollDismissesKeyboard(.interactively)
        .screenBackground()
        .navigationTitle(routineId == nil ? "Nueva rutina" : "Editar rutina")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) { Button("Guardar", action: save) }
        }
        .keyboardDoneButton()
        .onAppear(perform: load)
        .sheet(isPresented: $adding) {
            AddExerciseSheet { exercises.append(EditableExercise(empty: $0)) }
        }
        .sheet(isPresented: $creatingSection) {
            SectionSheet(section: nil, existing: sections.map(\.name)) { sectionId = app.progress.saveSection(name: $0) }
        }
        .alert("Borrar rutina", isPresented: $confirmDelete) {
            Button("Borrar", role: .destructive) {
                if let routineId { app.progress.deleteRoutine(routineId) }
                dismiss()
            }
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text("Se borrará la rutina \"\(name)\". Tus entrenamientos guardados no se borran.")
        }
    }

    private func load() {
        guard !loaded else { return }
        sectionId = initialSectionId
        if let routine = routineId.flatMap({ app.progress.routine($0) }) {
            name = routine.name
            exercises = routine.exercises.map { EditableExercise($0) }
            position = routine.position
            sectionId = routine.sectionId
        }
        loaded = true
    }

    private func save() {
        let logs = exercises.map { exercise -> ExerciseLog in
            var log = exercise.log
            log.sets = log.sets.filter { $0.reps > 0 }.map { WorkoutSet(reps: $0.reps, weight: $0.weight, done: true) }
            return log
        }
        let cleanName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if cleanName.isEmpty {
            error = "Ponle un nombre a la rutina."
        } else if logs.isEmpty {
            error = "Agrega al menos un ejercicio."
        } else if logs.contains(where: { $0.name.isEmpty }) {
            error = "Hay un ejercicio sin nombre."
        } else if logs.contains(where: { $0.sets.isEmpty }) {
            error = "Cada ejercicio necesita al menos una serie con repeticiones."
        } else {
            app.progress.saveRoutine(Routine(id: routineId ?? UUID().uuidString.lowercased(), name: String(cleanName.prefix(60)),
                                             exercises: logs, position: position, sectionId: sectionId))
            dismiss()
        }
    }
}

/// Ideas offered when creating a section.
private let sectionIdeas = ["Hipertrofia", "Fuerza", "Resistencia", "Cardio", "Calistenia", "Movilidad"]

/// Creates a section (with ideas to pick from) or renames [section].
struct SectionSheet: View {
    let section: RoutineSection?
    let existing: [String]
    let onSave: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""

    var body: some View {
        let taken = existing.filter { $0 != section?.name }.map { $0.lowercased() }
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let duplicate = taken.contains(clean.lowercased())
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    TextField("Nombre de la sección", text: $name)
                        .textFieldStyle(.roundedBorder)
                        .textInputAutocapitalization(.sentences)
                        .onChange(of: name) { _, new in if new.count > 40 { name = String(new.prefix(40)) } }
                    if duplicate { ErrorText(message: "Ya tienes una sección con ese nombre") }
                    if section == nil {
                        let ideas = sectionIdeas.filter { !taken.contains($0.lowercased()) }
                        if !ideas.isEmpty {
                            Text("Ideas").font(.subheadline.weight(.semibold))
                            FlowLayout {
                                ForEach(ideas, id: \.self) { idea in Pill(text: idea, selected: idea == name) { name = idea } }
                            }
                        }
                    }
                }
                .padding()
            }
            .navigationTitle(section == nil ? "Nueva sección" : "Cambiar nombre")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Guardar") {
                        onSave(clean)
                        dismiss()
                    }
                    .disabled(clean.isEmpty || duplicate)
                }
            }
            .onAppear { name = section?.name ?? "" }
        }
        .presentationDetents([.medium])
    }
}
