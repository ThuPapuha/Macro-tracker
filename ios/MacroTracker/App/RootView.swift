import SwiftUI

/// The five tabs, each with its own navigation.
struct RootView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        @Bindable var app = app
        TabView(selection: $app.selectedTab) {
            NavigationStack { TodayView() }
                .tabItem { Label("Hoy", systemImage: "house.fill") }
                .tag(AppTab.today)
            NavigationStack { HistoryView() }
                .tabItem { Label("Historial", systemImage: "calendar") }
                .tag(AppTab.history)
            NavigationStack { ProgressTab() }
                .tabItem { Label("Progreso", systemImage: "figure.strengthtraining.traditional") }
                .tag(AppTab.progress)
            NavigationStack { GroupsTab() }
                .tabItem { Label("Grupos", systemImage: "person.3.fill") }
                .tag(AppTab.groups)
            NavigationStack { ProfileView() }
                .tabItem { Label("Perfil", systemImage: "person.crop.circle.fill") }
                .tag(AppTab.profile)
        }
        .tint(AppColor.primary)
    }
}
