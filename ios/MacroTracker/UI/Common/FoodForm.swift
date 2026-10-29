import SwiftUI

/// Editable text version of a food, used by the manual form, AI results and the edit sheet.
struct FoodDraft: Identifiable, Hashable {
    var id = UUID()
    var name = ""
    var quantity = ""
    var calories = ""
    var protein = ""
    var carbs = ""
    var fat = ""
    var selected = true

    var isValid: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty &&
            (toAmount(calories) != nil || [protein, carbs, fat].contains { toAmount($0) != nil })
    }

    var macros: Macros {
        let p = toAmount(protein) ?? 0
        let c = toAmount(carbs) ?? 0
        let f = toAmount(fat) ?? 0
        return Macros(calories: toAmount(calories) ?? Macros.caloriesFrom(protein: p, carbs: c, fat: f), protein: p, carbs: c, fat: f)
    }

    init() {}

    init(_ entry: FoodEntry) {
        name = entry.name
        quantity = entry.quantity
        calories = inputAmount(entry.calories)
        protein = inputAmount(entry.protein)
        carbs = inputAmount(entry.carbs)
        fat = inputAmount(entry.fat)
    }

    init(_ food: EstimatedFood) {
        name = food.name
        quantity = food.quantity
        calories = inputAmount(food.calories)
        protein = inputAmount(food.protein)
        carbs = inputAmount(food.carbs)
        fat = inputAmount(food.fat)
    }

    func entry(date: Day, meal: MealType, source: String, base: FoodEntry? = nil) -> FoodEntry {
        let m = macros
        var entry = base ?? FoodEntry(date: date, meal: meal, name: name, source: source)
        entry.date = date
        entry.meal = meal
        entry.name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        entry.quantity = quantity.trimmingCharacters(in: .whitespacesAndNewlines)
        entry.calories = m.calories
        entry.protein = m.protein
        entry.carbs = m.carbs
        entry.fat = m.fat
        return entry
    }
}

struct FoodFields: View {
    @Binding var draft: FoodDraft

    var body: some View {
        VStack(spacing: 8) {
            TextField("Alimento", text: $draft.name)
                .textInputAutocapitalization(.sentences)
                .textFieldStyle(.roundedBorder)
            TextField("Cantidad (ej. 150 g)", text: $draft.quantity)
                .textFieldStyle(.roundedBorder)
            HStack {
                NumberField(label: "kcal", text: $draft.calories)
                NumberField(label: "Proteína g", text: $draft.protein)
            }
            HStack {
                NumberField(label: "Carbos g", text: $draft.carbs)
                NumberField(label: "Grasas g", text: $draft.fat)
            }
        }
    }
}

struct MealSelector: View {
    @Binding var meal: MealType

    var body: some View {
        Picker("Comida", selection: $meal) {
            ForEach(MealType.allCases) { Text($0.label).tag($0) }
        }
        .pickerStyle(.segmented)
    }
}

/// Sheet to edit (or delete) a logged food.
struct FoodEditSheet: View {
    let entry: FoodEntry
    let onSave: (FoodEntry) -> Void
    let onDelete: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var draft: FoodDraft
    @State private var meal: MealType

    init(entry: FoodEntry, onSave: @escaping (FoodEntry) -> Void, onDelete: @escaping () -> Void) {
        self.entry = entry
        self.onSave = onSave
        self.onDelete = onDelete
        _draft = State(initialValue: FoodDraft(entry))
        _meal = State(initialValue: entry.meal)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    MealSelector(meal: $meal)
                    FoodFields(draft: $draft)
                    if draft.calories.isEmpty {
                        Text("Si dejas kcal vacío, se calcula con los macros.").font(.footnote).foregroundStyle(.secondary)
                    }
                    Button(role: .destructive) {
                        onDelete()
                        dismiss()
                    } label: {
                        Label("Eliminar", systemImage: "trash").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .padding(.top, 8)
                }
                .padding()
            }
            .navigationTitle("Editar alimento")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Guardar") {
                        onSave(draft.entry(date: entry.date, meal: meal, source: entry.source, base: entry))
                        dismiss()
                    }
                    .disabled(!draft.isValid)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
