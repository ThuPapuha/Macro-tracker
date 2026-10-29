import SwiftUI
import UniformTypeIdentifiers

private let documentTypes: [UTType] = [.pdf, .image, .plainText, .text, UTType(filenameExtension: "docx") ?? .data]

private enum ProfileSheet: Identifiable {
    case document, manual, calculator, gemini
    case suggestion(MacroTargets)

    var id: String {
        switch self {
        case .document: return "document"
        case .manual: return "manual"
        case .calculator: return "calculator"
        case .gemini: return "gemini"
        case .suggestion: return "suggestion"
        }
    }
}

/// The Perfil tab: name, daily goals (from a document with AI, the calculator or by hand), settings,
/// backups and deleting the groups account.
struct ProfileView: View {
    @Environment(AppModel.self) private var app
    @State private var sheet: ProfileSheet?
    @State private var renaming = false
    @State private var newName = ""
    @State private var documentNote = ""
    @State private var pickingDocument = false
    @State private var analyzing = false
    @State private var error: String?
    @State private var exporting = false
    @State private var exportDocument: BackupDocument?
    @State private var importing = false
    @State private var backupMessage: String?
    @State private var confirmDeleteAccount = false
    @State private var deletingAccount = false

    var body: some View {
        let settings = app.settings
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Button {
                    newName = settings.displayName
                    renaming = true
                } label: {
                    HStack(spacing: 16) {
                        Avatar(name: settings.displayName.isEmpty ? "Yo" : settings.displayName,
                               seed: app.sessionStore.loadSession()?.userId ?? settings.displayName, size: 64)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(settings.displayName.isEmpty ? "Sin nombre" : settings.displayName).font(.title2.bold())
                            Text("Así te ven tus amigos en los grupos. Toca para cambiarlo.").font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                    }
                    .card()
                }
                .buttonStyle(.plain)

                TargetsCard(targets: settings.targets, onUpload: { sheet = .document }, onEdit: { sheet = .manual },
                            onCalculate: { sheet = .calculator })
                if analyzing { BusyRow(text: "Analizando tu documento con IA… puede tardar unos segundos.") }
                if let error { ErrorText(message: error) }

                settingsCard
                backupCard
                if app.serverConfigured { accountCard }
            }
            .padding()
        }
        .screenBackground()
        .navigationTitle("Perfil")
        .alert("Tu nombre", isPresented: $renaming) {
            TextField("Nombre o apodo", text: $newName).textInputAutocapitalization(.words)
            Button("Guardar") { saveName(newName) }
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text("Así te verán tus amigos en los grupos.")
        }
        .sheet(item: $sheet) { sheet in
            switch sheet {
            case .document:
                DocumentSheet(note: $documentNote) { pickingDocument = true }
            case .manual:
                TargetsSheet(title: "Editar objetivos", initial: app.settings.targets, message: nil, confirmLabel: "Guardar") {
                    var targets = $0
                    targets.note = "Ingresado manualmente."
                    app.settings.setTargets(targets)
                    app.sharedSettingsChanged()
                }
            case .calculator:
                CalculatorSheet(initial: app.settings.bodyProfile) { profile, targets in
                    app.settings.setBodyProfile(profile)
                    app.settings.setTargets(targets)
                    app.sharedSettingsChanged()
                }
            case .gemini:
                GeminiSheet(initialKey: app.settings.ownGeminiKey, initialModel: app.settings.geminiModel) {
                    app.settings.setOwnGemini(key: $0, model: $1)
                }
            case .suggestion(let targets):
                TargetsSheet(title: "Objetivos sugeridos", initial: targets, message: targets.note, confirmLabel: "Usar estos objetivos") {
                    app.settings.setTargets($0)
                    app.sharedSettingsChanged()
                }
            }
        }
        .fileImporter(isPresented: $pickingDocument, allowedContentTypes: documentTypes) { result in
            if case .success(let url) = result { analyze(url) }
        }
        .alert("Borrar mi cuenta de grupos", isPresented: $confirmDeleteAccount) {
            Button("Borrar", role: .destructive) { deleteAccount() }
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text("Se borran del servidor tu usuario, lo que compartiste con tus grupos (resúmenes, entrenamientos, peso, comentarios y reacciones) y los grupos que creaste si no tienen más miembros. Tus comidas y entrenamientos en este teléfono no se borran.")
        }
    }

    private var settingsCard: some View {
        let settings = app.settings
        return VStack(alignment: .leading, spacing: 12) {
            Text("Ajustes").font(.headline)
            Toggle(isOn: Binding(get: { settings.shareMeals }, set: {
                settings.setShareMeals($0)
                app.sharedSettingsChanged()
            })) {
                SettingLabel(title: "Compartir mis comidas con mis grupos", detail: "Si lo apagas, tus amigos solo ven tus totales del día.")
            }
            Toggle(isOn: Binding(get: { settings.shareWeight }, set: {
                settings.setWeightGoal(settings.weightGoal, share: $0)
                app.sharedSettingsChanged()
            })) {
                SettingLabel(title: "Compartir mi peso y composición corporal",
                             detail: "Peso, grasa, músculo, masa ósea, tu meta y tu avance. Tus entrenamientos siempre se comparten.")
            }
            Toggle(isOn: Binding(get: { settings.notifyGroupActivity }, set: { enabled in
                settings.setNotifyGroupActivity(enabled)
                if enabled { Task { await app.notifier.requestPermission() } }
                app.notifier.schedule()
            })) {
                SettingLabel(title: "Avisarme de la actividad de mis grupos",
                             detail: "Cuando un amigo registra comida o un entreno, o comenta y reacciona a lo tuyo. iOS decide cada cuánto revisar.")
            }
            Divider()
            Text("Inteligencia artificial").font(.subheadline.weight(.semibold))
            Text(!settings.ownGeminiKey.isEmpty
                 ? "Usando tu propia clave de Gemini (\(settings.geminiModel.isEmpty ? AppModel.geminiModel : settings.geminiModel))."
                 : app.serverConfigured
                 ? "Usando el servidor con la clave de Gemini guardada en GitHub (\(AppModel.geminiModel))."
                 : "No configurada: falta el servidor o una clave propia.")
                .font(.caption).foregroundStyle(.secondary)
            Button(settings.ownGeminiKey.isEmpty ? "Usar mi propia clave (opcional)" : "Cambiar clave propia") { sheet = .gemini }
                .buttonStyle(.bordered)
            Divider()
            Text("Servidor de grupos: " + (app.serverConfigured ? "configurado ✅" : "no configurado")).font(.caption)
            Text("Versión \(appVersion)").font(.caption).foregroundStyle(.secondary)
        }
        .card()
    }

    private var backupCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Respaldo de tus datos").font(.headline)
            Text("Tus comidas se guardan en este teléfono. Cada vez que sales de la app se guarda una copia en Archivos › En mi iPhone › Macro Tracker. Exporta un respaldo de vez en cuando (por ejemplo a iCloud Drive); si reinstalas la app, impórtalo aquí. Sirve también el respaldo de la app de Android.")
                .font(.caption).foregroundStyle(.secondary)
            if let last = app.settings.lastBackupAt {
                Text("Última copia automática: \(dateTime(last))").font(.caption)
            }
            HStack {
                Button { export() } label: { Text("Exportar").frame(maxWidth: .infinity) }
                Button { importing = true } label: { Text("Importar").frame(maxWidth: .infinity) }
            }
            .buttonStyle(.bordered)
            if let backupMessage { Text(backupMessage).font(.callout) }
        }
        .card()
        // Kept on this card: two file importers on the same view do not work.
        .fileExporter(isPresented: $exporting, document: exportDocument, contentType: .json,
                      defaultFilename: "macrotracker-respaldo-\(Day.today.iso).json") { result in
            switch result {
            case .success: backupMessage = "Respaldo guardado."
            case .failure(let failure): backupMessage = friendlyMessage(failure, "No se pudo guardar el respaldo")
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .plainText, .data]) { result in
            if case .success(let url) = result { importBackup(url) }
        }
    }

    private var accountCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Cuenta de grupos").font(.headline)
            Text("Al unirte a un grupo se crea una cuenta anónima en el servidor (sin correo ni contraseña). Puedes borrarla junto con todo lo que compartiste.")
                .font(.caption).foregroundStyle(.secondary)
            Button(role: .destructive) { confirmDeleteAccount = true } label: {
                if deletingAccount { ProgressView() } else { Text("Borrar mi cuenta de grupos") }
            }
            .buttonStyle(.bordered)
            .disabled(deletingAccount)
        }
        .card()
    }

    private var appVersion: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        return "\(version) (\(build))"
    }

    private func saveName(_ name: String) {
        let clean = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(40))
        guard !clean.isEmpty else { return }
        app.settings.setDisplayName(clean, synced: false)
        guard let api = app.groupAPI, app.settings.hasGroups else { return }
        Task {
            // Otherwise uploaded with the next sync.
            if (try? await api.setDisplayName(clean)) != nil { app.settings.setDisplayName(clean, synced: true) }
        }
    }

    private func analyze(_ url: URL) {
        let note = documentNote
        analyzing = true
        error = nil
        Task {
            defer { analyzing = false }
            do {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                let data = try Data(contentsOf: url)
                let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType
                let parts = try DocumentPreparer.prepare(data, mimeType: mime, fileName: url.lastPathComponent)
                let suggestion = try await app.ai.targetsFromDocument(parts, userNote: note)
                sheet = .suggestion(suggestion.toTargets(sourceName: url.lastPathComponent))
            } catch {
                self.error = friendlyMessage(error, "No se pudo analizar el documento")
            }
        }
    }

    private func export() {
        do {
            exportDocument = BackupDocument(data: try app.backup.exportData())
            exporting = true
        } catch {
            backupMessage = friendlyMessage(error, "No se pudo crear el respaldo")
        }
    }

    private func importBackup(_ url: URL) {
        backupMessage = "Procesando…"
        Task {
            do {
                backupMessage = try await app.backup.importFrom(url).message
                app.sharedSettingsChanged()
            } catch {
                backupMessage = friendlyMessage(error, "No se pudo completar el respaldo")
            }
        }
    }

    private func deleteAccount() {
        guard let api = app.groupAPI else { return }
        deletingAccount = true
        Task {
            defer { deletingAccount = false }
            do {
                try await api.deleteMyAccount()
                app.settings.forgetGroups()
                app.progress.markAllUnsynced()
                app.notifier.schedule()
                error = nil
                backupMessage = nil
                app.selectedTab = .profile
            } catch {
                self.error = friendlyMessage(error, "No se pudo borrar la cuenta")
            }
        }
    }
}

private struct SettingLabel: View {
    let title: String
    let detail: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
            Text(detail).font(.caption).foregroundStyle(.secondary)
        }
    }
}

private struct TargetsCard: View {
    let targets: MacroTargets?
    let onUpload: () -> Void
    let onEdit: () -> Void
    let onCalculate: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Objetivos diarios").font(.headline)
            if let targets {
                Text(kcal(targets.calories)).font(.largeTitle.bold())
                HStack(spacing: 18) {
                    MacroTarget(label: "Proteína", grams: targets.protein, kcalPerGram: 4, total: targets.calories)
                    MacroTarget(label: "Carbos", grams: targets.carbs, kcalPerGram: 4, total: targets.calories)
                    MacroTarget(label: "Grasas", grams: targets.fat, kcalPerGram: 9, total: targets.calories)
                }
                if !targets.note.isEmpty { Text(targets.note).font(.caption).foregroundStyle(.secondary) }
            } else {
                Text("Aún no tienes objetivos. Sube el plan de tu nutricionista, un análisis corporal o cualquier documento con tus datos y la IA los configura por ti.")
                    .font(.callout)
            }
            Button(action: onUpload) { Text("📄 Subir documento (IA)").frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
            HStack {
                Button(action: onCalculate) { Text("Calcular").frame(maxWidth: .infinity) }
                Button(action: onEdit) { Text("Editar").frame(maxWidth: .infinity) }
            }
            .buttonStyle(.bordered)
        }
        .card()
    }
}

private struct MacroTarget: View {
    let label: String
    let grams: Double
    let kcalPerGram: Double
    let total: Double

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("\(formatAmount(grams)) g").font(.headline)
            let percent = total > 0 ? Int((grams * kcalPerGram / total * 100).rounded()) : 0
            Text("\(label) · \(percent)%").font(.caption2).foregroundStyle(.secondary)
        }
    }
}

private struct DocumentSheet: View {
    @Binding var note: String
    let onPick: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Elige un PDF, foto, Word o texto: plan de tu nutricionista, dieta, InBody, análisis médico… La IA extrae o calcula tus calorías y macros. Podrás revisarlos antes de guardarlos.")
                        .font(.callout)
                }
                Section("Nota para la IA (opcional)") {
                    TextField("Ej: quiero bajar grasa, entreno 4 veces por semana", text: $note, axis: .vertical)
                        .lineLimit(2...5)
                }
            }
            .navigationTitle("Objetivos desde un documento")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Elegir archivo") {
                        dismiss()
                        // Let the sheet close before showing the file picker.
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { onPick() }
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

private struct TargetsSheet: View {
    let title: String
    let initial: MacroTargets?
    let message: String?
    let confirmLabel: String
    let onConfirm: (MacroTargets) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var calories = ""
    @State private var protein = ""
    @State private var carbs = ""
    @State private var fat = ""

    var body: some View {
        let p = toAmount(protein) ?? 0
        let c = toAmount(carbs) ?? 0
        let f = toAmount(fat) ?? 0
        let implied = Macros.caloriesFrom(protein: p, carbs: c, fat: f)
        let total = toAmount(calories) ?? implied
        let valid = (500.0...10000.0).contains(total)
        NavigationStack {
            Form {
                if let message, !message.isEmpty { Section { Text(message).font(.callout) } }
                Section {
                    HStack {
                        NumberField(label: "Calorías", text: $calories)
                        NumberField(label: "Proteína g", text: $protein)
                    }
                    HStack {
                        NumberField(label: "Carbos g", text: $carbs)
                        NumberField(label: "Grasas g", text: $fat)
                    }
                } footer: {
                    Text("Los macros suman \(formatAmount(implied)) kcal.")
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(confirmLabel) {
                        onConfirm(MacroTargets(calories: total.rounded(), protein: p, carbs: c, fat: f, note: initial?.note ?? ""))
                        dismiss()
                    }
                    .disabled(!valid)
                }
            }
            .keyboardDoneButton()
            .onAppear {
                calories = initial.map { inputAmount($0.calories) } ?? ""
                protein = initial.map { inputAmount($0.protein) } ?? ""
                carbs = initial.map { inputAmount($0.carbs) } ?? ""
                fat = initial.map { inputAmount($0.fat) } ?? ""
            }
        }
        .presentationDetents([.medium, .large])
    }
}

private struct CalculatorSheet: View {
    let initial: BodyProfile?
    let onConfirm: (BodyProfile, MacroTargets) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var sex = Sex.MALE
    @State private var age = ""
    @State private var weight = ""
    @State private var height = ""
    @State private var activity = ActivityLevel.MODERATE
    @State private var goal = Goal.MAINTAIN

    private var profile: BodyProfile? {
        guard let a = Int(age.trimmingCharacters(in: .whitespaces)), let w = toAmount(weight), let h = toAmount(height) else { return nil }
        return BodyProfile(sex: sex, age: a, weightKg: w, heightCm: h, activity: activity, goal: goal)
    }

    var body: some View {
        let current = self.profile
        let result = current.flatMap { try? NutritionCalculator.targets($0) }
        NavigationStack {
            Form {
                Section {
                    Picker("Sexo", selection: $sex) {
                        ForEach(Sex.allCases) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Edad").font(.caption).foregroundStyle(.secondary)
                            TextField("0", text: $age)
                                .keyboardType(.numberPad)
                                .textFieldStyle(.roundedBorder)
                                .onChange(of: age) { _, new in
                                    let digits = String(new.filter(\.isNumber).prefix(3))
                                    if digits != new { age = digits }
                                }
                        }
                        NumberField(label: "Peso kg", text: $weight)
                        NumberField(label: "Altura cm", text: $height)
                    }
                }
                Section("Actividad") {
                    Picker("Actividad", selection: $activity) {
                        ForEach(ActivityLevel.allCases) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                }
                Section("Objetivo") {
                    Picker("Objetivo", selection: $goal) {
                        ForEach(Goal.allCases) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.segmented)
                }
                Section {
                    Text(result.map { "Resultado: \(formatAmount($0.calories)) kcal · P \(formatAmount($0.protein)) g · C \(formatAmount($0.carbs)) g · G \(formatAmount($0.fat)) g" }
                         ?? "Completa edad, peso y altura.")
                        .font(.headline)
                }
            }
            .navigationTitle("Calcular objetivos")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Usar") {
                        guard let current, let result else { return }
                        onConfirm(current, result)
                        dismiss()
                    }
                    .disabled(result == nil)
                }
            }
            .keyboardDoneButton()
            .onAppear {
                guard let initial else { return }
                sex = initial.sex
                age = String(initial.age)
                weight = inputAmount(initial.weightKg)
                height = inputAmount(initial.heightCm)
                activity = initial.activity
                goal = initial.goal
            }
        }
    }
}

private struct GeminiSheet: View {
    let initialKey: String
    let initialModel: String
    let onSave: (String, String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var key = ""
    @State private var model = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Opcional. Normalmente la app usa la clave guardada en GitHub a través del servidor. Si pones una clave aquí, se guarda solo en este teléfono y se usa directamente.")
                        .font(.callout)
                }
                Section {
                    SecureField("API key de Gemini", text: $key)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("Modelo (\(AppModel.geminiModel))", text: $model)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                if !initialKey.isEmpty {
                    Section {
                        Button("Quitar clave propia", role: .destructive) {
                            onSave("", "")
                            dismiss()
                        }
                    }
                }
            }
            .navigationTitle("Clave propia de Gemini")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Guardar") {
                        onSave(key, model)
                        dismiss()
                    }
                }
            }
            .onAppear {
                key = initialKey
                model = initialModel
            }
        }
        .presentationDetents([.medium, .large])
    }
}
