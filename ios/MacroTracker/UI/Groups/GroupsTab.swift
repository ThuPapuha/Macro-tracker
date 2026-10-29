import SwiftUI

/// Text for an error shown to the user.
func friendlyMessage(_ error: Error, _ fallback: String) -> String {
    if error is URLError { return "Sin conexión. Revisa tu internet e inténtalo de nuevo." }
    if let error = error as? LocalizedError, let text = error.errorDescription, !text.isEmpty { return text }
    return fallback
}

/// The group list and the actions that change it.
@MainActor
@Observable
final class GroupsModel {
    var groups: [GroupInfo] = []
    /// True once the group list has been fetched at least once.
    var loaded = false
    var loading = false
    var error: String?

    func refresh(_ app: AppModel) async {
        await run(app) { api in
            self.groups = try await api.myGroups()
            self.loaded = true
            app.settings.setHasGroups(!self.groups.isEmpty)
            // Forget a group we no longer belong to.
            let last = app.settings.lastGroupId
            if !last.isEmpty, !self.groups.contains(where: { $0.id == last }) {
                app.settings.setLastGroup(self.groups.first?.id ?? "")
            }
        }
    }

    func saveName(_ app: AppModel, _ name: String) async {
        let clean = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(40))
        guard !clean.isEmpty else { return }
        app.settings.setDisplayName(clean, synced: false)
        guard app.settings.hasGroups || !groups.isEmpty else { return }
        await run(app) { api in
            try await api.setDisplayName(clean)
            app.settings.setDisplayName(clean, synced: true)
        }
    }

    func create(_ app: AppModel, _ name: String) async {
        let clean = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(60))
        guard !clean.isEmpty else { return }
        await run(app) { api in
            try await app.sync.ensureName(api)
            try await self.afterMembershipChange(app, api, try await api.createGroup(clean))
        }
    }

    func join(_ app: AppModel, _ code: String) async {
        await run(app) { api in
            try await app.sync.ensureName(api)
            try await self.afterMembershipChange(app, api, try await api.joinGroup(code))
        }
    }

    private func afterMembershipChange(_ app: AppModel, _ api: GroupAPI, _ group: GroupInfo) async throws {
        app.settings.setHasGroups(true)
        app.settings.setLastGroup(group.id)
        groups = try await api.myGroups()
        loaded = true
        app.sync.requestRecentFood()
        app.sync.requestProgress()
    }

    private func run(_ app: AppModel, _ block: (GroupAPI) async throws -> Void) async {
        guard let api = app.groupAPI else { return }
        loading = true
        error = nil
        do {
            try await block(api)
        } catch {
            self.error = friendlyMessage(error, "Algo salió mal")
        }
        loading = false
    }
}

enum GroupDialog: Identifiable {
    case name, create, join
    var id: Self { self }
}

/// The Grupos tab: opens straight into the last group viewed (or the only one); without groups it
/// offers to create or join one.
struct GroupsTab: View {
    @Environment(AppModel.self) private var app
    @State private var model = GroupsModel()
    @State private var dialog: GroupDialog?
    @State private var input = ""

    var body: some View {
        let settings = app.settings
        let last = settings.lastGroupId
        let selectedId = (!last.isEmpty && (!model.loaded || model.groups.contains { $0.id == last })) ? last : model.groups.first?.id
        Group {
            if !app.serverConfigured {
                NotConfiguredView()
            } else if let selectedId {
                GroupDetailView(
                    groupId: selectedId,
                    groups: model.groups,
                    onSelectGroup: { settings.setLastGroup($0) },
                    onDialog: open,
                    onLeft: { Task { await model.refresh(app) } }
                )
                .id(selectedId)
            } else if !model.loaded && model.error == nil {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity).screenBackground()
            } else {
                NoGroupsView(model: model, onDialog: open)
            }
        }
        .task { await model.refresh(app) }
        .task(id: selectedId) {
            if selectedId != nil && settings.notifyGroupActivity && !settings.notificationPermissionAsked {
                await app.notifier.requestPermission()
            }
        }
        .alert(title(dialog), isPresented: Binding(get: { dialog != nil }, set: { if !$0 { dialog = nil } })) {
            TextField(placeholder(dialog), text: $input)
                .textInputAutocapitalization(dialog == .join ? TextInputAutocapitalization.characters : .words)
                .autocorrectionDisabled(dialog == .join)
            Button(dialog == .create ? "Crear" : dialog == .join ? "Unirme" : "Guardar") { confirm() }
            Button("Cancelar", role: .cancel) { dialog = nil }
        } message: {
            switch dialog {
            case .name: Text("Así te verán tus amigos en los grupos.")
            case .join: Text("Pídele el código de 6 caracteres a quien creó el grupo.")
            default: EmptyView()
            }
        }
    }

    private func open(_ which: GroupDialog) {
        input = which == .name ? app.settings.displayName : ""
        dialog = which
    }

    private func title(_ which: GroupDialog?) -> String {
        switch which {
        case .name: return "Tu nombre"
        case .create: return "Crear grupo"
        case .join: return "Unirme a un grupo"
        case nil: return ""
        }
    }

    private func placeholder(_ which: GroupDialog?) -> String {
        switch which {
        case .name: return "Nombre o apodo"
        case .create: return "Nombre del grupo"
        default: return "Código de invitación"
        }
    }

    private func confirm() {
        let which = dialog
        let text = input
        dialog = nil
        Task {
            switch which {
            case .name: await model.saveName(app, text)
            case .create: await model.create(app, text)
            case .join:
                let code = String(text.uppercased().filter { $0.isLetter || $0.isNumber }.prefix(6))
                await model.join(app, code)
            case nil: break
            }
        }
    }
}

private struct NotConfiguredView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 6) {
                Text("Grupos no disponibles").font(.headline)
                Text("Esta versión se compiló sin servidor. Configura Supabase en Config/App.xcconfig (ver ios/README.md) y vuelve a compilar para activar los grupos y la IA compartida.")
                    .font(.callout)
            }
            .card()
            .padding()
        }
        .screenBackground()
        .navigationTitle("Grupos")
    }
}

private struct NoGroupsView: View {
    @Environment(AppModel.self) private var app
    let model: GroupsModel
    let onDialog: (GroupDialog) -> Void

    var body: some View {
        let name = app.settings.displayName
        let hasName = !name.trimmingCharacters(in: .whitespaces).isEmpty
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                GradientCard(gradient: Brand.hero) {
                    Text("Ponte en forma con tus amigos").font(.title3.bold())
                    Text("🍽️ Mira cómo comen frente a sus objetivos\n🏋️ Comparte tus entrenos y récords\n⚖️ Sigue sus metas de peso\n💬 Comenta y reacciona a sus publicaciones")
                        .font(.callout)
                }
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Tu nombre en los grupos").font(.caption).foregroundStyle(.secondary)
                        Text(hasName ? name : "Sin nombre").font(.headline)
                    }
                    Spacer()
                    Button(hasName ? "Cambiar" : "Elegir") { onDialog(.name) }
                }
                .card()
                HStack {
                    Button { onDialog(.create) } label: { Text("Crear grupo").frame(maxWidth: .infinity) }
                        .buttonStyle(.borderedProminent)
                    Button { onDialog(.join) } label: { Text("Unirme").frame(maxWidth: .infinity) }
                        .buttonStyle(.bordered)
                }
                .controlSize(.large)
                .disabled(!hasName || model.loading)
                if !hasName {
                    Text("Primero elige tu nombre para que tus amigos te reconozcan.").font(.footnote).foregroundStyle(.secondary)
                }
                if model.loading { ProgressView().frame(maxWidth: .infinity) }
                if let error = model.error {
                    ErrorText(message: error)
                } else {
                    Text("Aún no estás en ningún grupo. Crea uno y comparte el código con tus amigos, o únete al de un amigo con su código.")
                        .font(.callout).foregroundStyle(.secondary)
                }
            }
            .padding()
        }
        .screenBackground()
        .navigationTitle("Grupos")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { Task { await model.refresh(app) } } label: { Image(systemName: "arrow.clockwise") }
                    .accessibilityLabel("Actualizar")
            }
        }
        .refreshable { await model.refresh(app) }
    }
}
