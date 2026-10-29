import Foundation

enum Sex: String, CaseIterable, Codable, Identifiable {
    case MALE, FEMALE
    var id: String { rawValue }
    var label: String { self == .MALE ? "Hombre" : "Mujer" }
}

enum ActivityLevel: String, CaseIterable, Codable, Identifiable {
    case SEDENTARY, LIGHT, MODERATE, HIGH, VERY_HIGH
    var id: String { rawValue }

    var label: String {
        switch self {
        case .SEDENTARY: return "Sedentario (poco o nada de ejercicio)"
        case .LIGHT: return "Ligero (1-3 días por semana)"
        case .MODERATE: return "Moderado (3-5 días por semana)"
        case .HIGH: return "Alto (6-7 días por semana)"
        case .VERY_HIGH: return "Muy alto (doble sesión o trabajo físico)"
        }
    }

    var factor: Double {
        switch self {
        case .SEDENTARY: return 1.2
        case .LIGHT: return 1.375
        case .MODERATE: return 1.55
        case .HIGH: return 1.725
        case .VERY_HIGH: return 1.9
        }
    }
}

enum Goal: String, CaseIterable, Codable, Identifiable {
    case LOSE, MAINTAIN, GAIN
    var id: String { rawValue }

    var label: String {
        switch self {
        case .LOSE: return "Perder grasa"
        case .MAINTAIN: return "Mantener peso"
        case .GAIN: return "Ganar músculo"
        }
    }

    var calorieFactor: Double {
        switch self {
        case .LOSE: return 0.8
        case .MAINTAIN: return 1.0
        case .GAIN: return 1.1
        }
    }

    var proteinPerKg: Double {
        switch self {
        case .LOSE: return 2.0
        case .MAINTAIN: return 1.6
        case .GAIN: return 1.8
        }
    }
}

struct BodyProfile: Codable, Hashable {
    var sex: Sex
    var age: Int
    var weightKg: Double
    var heightCm: Double
    var activity: ActivityLevel
    var goal: Goal
}

struct CalculatorError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

enum NutritionCalculator {
    /// Basal metabolic rate (Mifflin-St Jeor), kcal/day.
    static func bmr(_ p: BodyProfile) -> Double {
        let base = 10 * p.weightKg + 6.25 * p.heightCm - 5 * Double(p.age)
        return base + (p.sex == .MALE ? 5 : -161)
    }

    static func tdee(_ p: BodyProfile) -> Double { bmr(p) * p.activity.factor }

    static func targets(_ p: BodyProfile) throws -> MacroTargets {
        guard (10...100).contains(p.age) else { throw CalculatorError(message: "Edad fuera de rango") }
        guard (25.0...350.0).contains(p.weightKg) else { throw CalculatorError(message: "Peso fuera de rango") }
        guard (100.0...250.0).contains(p.heightCm) else { throw CalculatorError(message: "Altura fuera de rango") }

        let bmr = bmr(p)
        let tdee = tdee(p)
        let calories = Int((tdee * p.goal.calorieFactor).rounded())
        let protein = Int((p.weightKg * p.goal.proteinPerKg).rounded())
        // At least 25% of energy from fat, and never below 0.6 g/kg.
        let fat = Int(max(Double(calories) * 0.25 / 9, p.weightKg * 0.6).rounded())
        let carbs = max(0, Int((Double(calories - protein * 4 - fat * 9) / 4).rounded()))

        return MacroTargets(
            calories: Double(calories),
            protein: Double(protein),
            carbs: Double(carbs),
            fat: Double(fat),
            note: "Calculado con Mifflin-St Jeor: metabolismo basal \(Int(bmr.rounded())) kcal, " +
                "gasto diario \(Int(tdee.rounded())) kcal (\(p.activity.label.lowercased())). " +
                "Objetivo: \(p.goal.label.lowercased())."
        )
    }
}
