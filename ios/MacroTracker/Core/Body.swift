import Foundation

/// One day's body measurements: weight in kg and, if measured, body fat %, muscle % and bone mass in kg.
struct BodyLog: Hashable {
    let day: String
    let kg: Double
    var fatPercent: Double? = nil
    var musclePercent: Double? = nil
    var boneKg: Double? = nil

    var hasComposition: Bool { fatPercent != nil || musclePercent != nil || boneKg != nil }

    /// Kilos of fat and of everything else, when body fat is known.
    var fatMassKg: Double? { fatPercent.map { kg * $0 / 100 } }
    var leanMassKg: Double? { fatMassKg.map { kg - $0 } }
}

/// The measurements that can be charted, with the range the server accepts.
enum BodyMetric: CaseIterable {
    case weight, fat, muscle, bone

    var label: String {
        switch self {
        case .weight: return "Peso"
        case .fat: return "Grasa"
        case .muscle: return "Músculo"
        case .bone: return "Hueso"
        }
    }

    var unit: String { self == .fat || self == .muscle ? "%" : "kg" }

    var range: ClosedRange<Double> {
        switch self {
        case .weight: return 20...400
        case .fat: return 1...80
        case .muscle: return 5...90
        case .bone: return 0.2...15
        }
    }

    func of(_ log: BodyLog) -> Double? {
        switch self {
        case .weight: return log.kg
        case .fat: return log.fatPercent
        case .muscle: return log.musclePercent
        case .bone: return log.boneKg
        }
    }

    /// Whether going up is good news (more muscle) or bad news (more fat). Nil when neutral.
    var upIsGood: Bool? {
        switch self {
        case .fat: return false
        case .muscle: return true
        default: return nil
        }
    }
}

/// Latest value of a measurement and the one before it.
struct MetricTrend {
    let current: Double
    let previous: Double?
    var change: Double? { previous.map { current - $0 } }
}

enum Body {
    /// Values of [metric] in [logs] (any order), oldest first, skipping days it was not measured.
    static func series(_ logs: [BodyLog], _ metric: BodyMetric) -> [(String, Double)] {
        logs.sorted { $0.day < $1.day }.compactMap { log in metric.of(log).map { (log.day, $0) } }
    }

    static func trend(_ logs: [BodyLog], _ metric: BodyMetric) -> MetricTrend? {
        let values = series(logs, metric)
        guard let last = values.last else { return nil }
        return MetricTrend(current: last.1, previous: values.count >= 2 ? values[values.count - 2].1 : nil)
    }

    /// Metrics with at least [minPoints] measurements, to offer them in the chart.
    static func chartable(_ logs: [BodyLog], minPoints: Int = 2) -> [BodyMetric] {
        BodyMetric.allCases.filter { metric in logs.filter { metric.of($0) != nil }.count >= minPoints }
    }
}
