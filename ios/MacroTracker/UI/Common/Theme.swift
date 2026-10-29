import SwiftUI
import UIKit

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: opacity
        )
    }

    /// A color that follows light and dark mode.
    init(light: UInt32, dark: UInt32) {
        self.init(UIColor { traits in
            let hex = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(
                red: CGFloat((hex >> 16) & 0xFF) / 255,
                green: CGFloat((hex >> 8) & 0xFF) / 255,
                blue: CGFloat(hex & 0xFF) / 255,
                alpha: 1
            )
        })
    }
}

/// Same palette as the Android app.
enum AppColor {
    static let background = Color(light: 0xF4F6F8, dark: 0x0E1013)
    static let card = Color(light: 0xFFFFFF, dark: 0x171A1F)
    static let cardHigh = Color(light: 0xEDF0F3, dark: 0x22262D)
    static let track = Color(light: 0xE2E7EC, dark: 0x2A2F37)
    static let primary = Color(light: 0x059669, dark: 0x34D399)
    static let primarySoft = Color(light: 0xD1FAE5, dark: 0x0E3B2E)
    static let error = Color(light: 0xDC2626, dark: 0xF87171)
}

/// Fixed colors so each macro looks the same everywhere.
enum MacroColor {
    static let protein = Color(hex: 0xF87171)
    static let carbs = Color(hex: 0x60A5FA)
    static let fat = Color(hex: 0xFBBF24)
}

enum Brand {
    static let emerald = Color(hex: 0x34D399)
    static let sky = Color(hex: 0x60A5FA)
    static let pink = Color(hex: 0xF472B6)
    static let amber = Color(hex: 0xFBBF24)
    static let violet = Color(hex: 0xA78BFA)
    /// Gold for personal records.
    static let record = Color(hex: 0xF59E0B)

    static let hero = LinearGradient(colors: [Color(hex: 0x10B981), Color(hex: 0x3B82F6)], startPoint: .topLeading, endPoint: .bottomTrailing)
    static let gym = LinearGradient(colors: [Color(hex: 0xF472B6), Color(hex: 0x8B5CF6)], startPoint: .topLeading, endPoint: .bottomTrailing)
    /// Ring around members who reached their goal of the day, like a story.
    static let storyRing = AngularGradient(colors: [emerald, sky, violet, pink, amber, emerald], center: .center)
}

extension View {
    /// The rounded card used across the app.
    func card(padding: CGFloat = 16) -> some View {
        self.padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(AppColor.card, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    /// Screen background behind cards.
    func screenBackground() -> some View {
        self.scrollContentBackground(.hidden).background(AppColor.background.ignoresSafeArea())
    }
}

/// Closes the keyboard (number pads have no return key).
@MainActor
func hideKeyboard() {
    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
}

extension View {
    /// Adds a "Listo" button above the keyboard to close it.
    func keyboardDoneButton() -> some View {
        toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("Listo") { hideKeyboard() }
            }
        }
    }
}
