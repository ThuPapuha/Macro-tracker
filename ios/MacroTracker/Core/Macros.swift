import Foundation

/// Calories in kcal; protein, carbs and fat in grams.
struct Macros: Codable, Hashable {
    var calories: Double = 0
    var protein: Double = 0
    var carbs: Double = 0
    var fat: Double = 0

    static let zero = Macros()

    static func + (lhs: Macros, rhs: Macros) -> Macros {
        Macros(
            calories: lhs.calories + rhs.calories,
            protein: lhs.protein + rhs.protein,
            carbs: lhs.carbs + rhs.carbs,
            fat: lhs.fat + rhs.fat
        )
    }

    static func * (lhs: Macros, factor: Double) -> Macros {
        Macros(calories: lhs.calories * factor, protein: lhs.protein * factor, carbs: lhs.carbs * factor, fat: lhs.fat * factor)
    }

    static func sum<S: Sequence>(_ items: S) -> Macros where S.Element == Macros {
        items.reduce(.zero, +)
    }

    /// Energy implied by the macros using the 4/4/9 kcal-per-gram rule.
    static func caloriesFrom(protein: Double, carbs: Double, fat: Double) -> Double {
        protein * 4 + carbs * 4 + fat * 9
    }

    init(calories: Double = 0, protein: Double = 0, carbs: Double = 0, fat: Double = 0) {
        self.calories = calories
        self.protein = protein
        self.carbs = carbs
        self.fat = fat
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        calories = c.value(.calories, 0)
        protein = c.value(.protein, 0)
        carbs = c.value(.carbs, 0)
        fat = c.value(.fat, 0)
    }
}

/// Daily goals. [note] explains where they came from (document, calculator or manual).
struct MacroTargets: Codable, Hashable {
    var calories: Double
    var protein: Double
    var carbs: Double
    var fat: Double
    var note: String = ""

    var macros: Macros { Macros(calories: calories, protein: protein, carbs: carbs, fat: fat) }

    /// A day counts as "on target" when calories land within ±10% of the goal.
    func isOnTarget(_ consumed: Macros) -> Bool {
        calories > 0 && consumed.calories >= calories * 0.9 && consumed.calories <= calories * 1.1
    }

    init(calories: Double, protein: Double, carbs: Double, fat: Double, note: String = "") {
        self.calories = calories
        self.protein = protein
        self.carbs = carbs
        self.fat = fat
        self.note = note
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        calories = c.value(.calories, 0)
        protein = c.value(.protein, 0)
        carbs = c.value(.carbs, 0)
        fat = c.value(.fat, 0)
        note = c.value(.note, "")
    }
}

enum MealType: String, CaseIterable, Codable, Comparable, Identifiable {
    case BREAKFAST, LUNCH, DINNER, SNACK

    var id: String { rawValue }

    var label: String {
        switch self {
        case .BREAKFAST: return "Desayuno"
        case .LUNCH: return "Almuerzo"
        case .DINNER: return "Cena"
        case .SNACK: return "Snacks"
        }
    }

    var emoji: String {
        switch self {
        case .BREAKFAST: return "🍳"
        case .LUNCH: return "🍲"
        case .DINNER: return "🌙"
        case .SNACK: return "🍎"
        }
    }

    static func from(_ name: String?) -> MealType { MealType(rawValue: name ?? "") ?? .SNACK }

    static func forHour(_ hour: Int) -> MealType {
        switch hour {
        case 5...10: return .BREAKFAST
        case 11...15: return .LUNCH
        case 19...23: return .DINNER
        default: return .SNACK
        }
    }

    static func < (lhs: MealType, rhs: MealType) -> Bool {
        allCases.firstIndex(of: lhs)! < allCases.firstIndex(of: rhs)!
    }
}

/// Fraction of [target] reached, clamped to 0…1.5 so overshoots stay drawable.
func progressOf(_ consumed: Double, _ target: Double) -> Double {
    target <= 0 ? 0 : min(max(consumed / target, 0), 1.5)
}
