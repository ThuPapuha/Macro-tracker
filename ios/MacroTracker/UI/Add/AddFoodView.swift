import PhotosUI
import SwiftUI

/// Add food with AI (text and/or photo), by hand, or repeating something eaten before.
struct AddFoodView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let date: Day
    @State private var meal: MealType
    @State private var tab = 0

    init(date: Day, initialMeal: MealType) {
        self.date = date
        _meal = State(initialValue: initialMeal)
    }

    var body: some View {
        VStack(spacing: 8) {
            VStack(spacing: 8) {
                MealSelector(meal: $meal)
                Picker("Modo", selection: $tab) {
                    Text("Con IA").tag(0)
                    Text("Manual").tag(1)
                    Text("Recientes").tag(2)
                }
                .pickerStyle(.segmented)
            }
            .padding(.horizontal)
            switch tab {
            case 0: AITab(date: date, meal: meal) { dismiss() }
            case 1: ManualTab(date: date, meal: meal) { dismiss() }
            default: RecentTab(date: date, meal: meal)
            }
        }
        .background(AppColor.background.ignoresSafeArea())
        .navigationTitle("Agregar · \(dayLabel(date))")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct AITab: View {
    @Environment(AppModel.self) private var app
    let date: Day
    let meal: MealType
    let onDone: () -> Void

    @State private var description = ""
    @State private var image: UIImage?
    @State private var photoItem: PhotosPickerItem?
    @State private var showCamera = false
    @State private var busy = false
    @State private var error: String?
    @State private var results: [FoodDraft] = []
    @State private var notes = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("¿Qué comiste?").font(.subheadline.weight(.semibold))
                    TextField("Ej: 2 huevos revueltos, 1 pan integral y un café con leche", text: $description, axis: .vertical)
                        .lineLimit(2...5)
                        .textFieldStyle(.roundedBorder)
                }
                HStack {
                    if CameraPicker.isAvailable {
                        Button { showCamera = true } label: { Label("Cámara", systemImage: "camera").frame(maxWidth: .infinity) }
                            .buttonStyle(.bordered)
                    }
                    PhotosPicker(selection: $photoItem, matching: .images) {
                        Label("Galería", systemImage: "photo").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                }
                if let image {
                    ZStack(alignment: .topTrailing) {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFill()
                            .frame(height: 180)
                            .frame(maxWidth: .infinity)
                            .clipShape(RoundedRectangle(cornerRadius: 14))
                        Button("Quitar") { self.image = nil }
                            .buttonStyle(.borderedProminent)
                            .padding(8)
                    }
                }
                Text("Tip: también puedes fotografiar la etiqueta nutricional de un producto.")
                    .font(.footnote).foregroundStyle(.secondary)
                Button(action: analyze) {
                    HStack {
                        if busy { ProgressView().tint(.white) }
                        Text(busy ? "Analizando…" : "✨ Calcular macros con IA")
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(busy || (description.trimmingCharacters(in: .whitespaces).isEmpty && image == nil))
                if let error { ErrorText(message: error) }

                if !results.isEmpty {
                    if !notes.isEmpty { Text(notes).font(.footnote).foregroundStyle(.secondary) }
                    ForEach($results) { $draft in
                        HStack(alignment: .top) {
                            Button { draft.selected.toggle() } label: {
                                Image(systemName: draft.selected ? "checkmark.circle.fill" : "circle").font(.title2)
                            }
                            .buttonStyle(.plain)
                            FoodFields(draft: $draft)
                        }
                        .card(padding: 12)
                    }
                    let selected = results.filter { $0.selected && $0.isValid }
                    let total = Macros.sum(selected.map(\.macros))
                    Text("Total: \(kcal(total.calories)) · \(macroLine(total))").font(.headline)
                    Button("Agregar \(selected.count) a \(meal.label.lowercased())") {
                        app.food.add(selected.map { $0.entry(date: date, meal: meal, source: "ai") })
                        onDone()
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .frame(maxWidth: .infinity)
                    .disabled(selected.isEmpty)
                }
            }
            .padding()
        }
        .scrollDismissesKeyboard(.interactively)
        .sheet(isPresented: $showCamera) {
            CameraPicker { image = $0 }.ignoresSafeArea()
        }
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self), let picked = UIImage(data: data) {
                    image = picked
                } else {
                    error = "No se pudo leer la imagen"
                }
                photoItem = nil
            }
        }
    }

    private func analyze() {
        busy = true
        error = nil
        Task {
            defer { busy = false }
            do {
                let part = image?.jpegForAI().map { AIPart.inline("image/jpeg", $0) }
                let estimate = try await app.ai.estimateMeal(description: description, image: part)
                results = estimate.items.map(FoodDraft.init)
                notes = estimate.notes
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

private struct ManualTab: View {
    @Environment(AppModel.self) private var app
    let date: Day
    let meal: MealType
    let onDone: () -> Void
    @State private var draft = FoodDraft()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                FoodFields(draft: $draft)
                Text("Si dejas kcal vacío, se calcula con los macros.").font(.footnote).foregroundStyle(.secondary)
                Button("Agregar") {
                    app.food.add([draft.entry(date: date, meal: meal, source: "manual")])
                    onDone()
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .frame(maxWidth: .infinity)
                .disabled(!draft.isValid)
            }
            .padding()
        }
        .scrollDismissesKeyboard(.interactively)
    }
}

private struct RecentTab: View {
    @Environment(AppModel.self) private var app
    let date: Day
    let meal: MealType
    @State private var query = ""
    @State private var added: String?

    var body: some View {
        let recents = app.food.recents().filter { query.isEmpty || $0.name.localizedCaseInsensitiveContains(query) }
        List {
            if let added {
                Text("✓ Agregado: \(added)").foregroundStyle(AppColor.primary)
            }
            if recents.isEmpty {
                Text(query.isEmpty ? "Todavía no hay comidas registradas." : "Sin resultados.").foregroundStyle(.secondary)
            }
            ForEach(recents) { entry in
                Button {
                    var copy = entry
                    copy.id = UUID()
                    copy.date = date
                    copy.meal = meal
                    copy.source = "recent"
                    copy.createdAt = nowMillis()
                    app.food.add([copy])
                    added = entry.name
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(entry.name)
                        Text((entry.quantity.isEmpty ? "" : "\(entry.quantity) · ") + "\(kcal(entry.calories)) · \(macroLine(entry.macros))")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                .foregroundStyle(Color.primary)
            }
        }
        .searchable(text: $query, prompt: "Buscar en lo que ya comiste")
        .screenBackground()
    }
}
