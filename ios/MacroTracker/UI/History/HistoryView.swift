import Charts
import SwiftUI

/// Calories per day over the last 7, 14 or 30 days, averages, the weekly budget of the last weeks
/// and the logged days (tap one to see or change it).
struct HistoryView: View {
    @Environment(AppModel.self) private var app
    @AppStorage("historyRange") private var range = 7

    var body: some View {
        let today = Day.today
        let from = today.adding(days: -(range - 1))
        let byDay = Dictionary(uniqueKeysWithValues: app.food.intakes(from: from, to: today).map { ($0.date, $0) })
        let days = (0..<range).map { offset -> DayIntake in
            let day = from.adding(days: offset)
            return byDay[day] ?? DayIntake(date: day, totals: .zero, entries: 0)
        }
        let logged = days.filter { $0.entries > 0 }
        let average = logged.isEmpty ? Macros.zero : Macros.sum(logged.map(\.totals)) * (1 / Double(logged.count))
        let targets = app.settings.targets
        let onTarget = targets.map { t in logged.filter { t.isOnTarget($0.totals) }.count }
        let weeks: [WeekSummary] = {
            guard let targets, targets.calories > 0 else { return [] }
            let start = WeeklyBudget.weekStart(today).adding(days: -21)
            return WeeklyBudget.lastWeeks(app.food.intakes(from: start, to: today), daily: targets, today: today, count: 4)
        }()

        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 8) {
                    ForEach([7, 14, 30], id: \.self) { length in
                        Pill(text: "\(length) días", selected: range == length) { range = length }
                    }
                }
                VStack(alignment: .leading, spacing: 8) {
                    Text("Calorías por día").font(.headline)
                    CaloriesChart(days: days, target: targets?.calories)
                    if let targets {
                        Text("Línea punteada: tu objetivo de \(formatAmount(targets.calories)) kcal").font(.caption2).foregroundStyle(.secondary)
                    }
                }
                .card()
                VStack(alignment: .leading, spacing: 8) {
                    Text("Promedio diario").font(.headline)
                    Text(logged.isEmpty ? "Sin registros en este periodo"
                         : "\(formatAmount(average.calories)) kcal · \(logged.count) de \(days.count) días registrados")
                    if let onTarget, !logged.isEmpty {
                        Text("🎯 \(onTarget) días dentro de ±10% de tu objetivo")
                    }
                    MacroBars(totals: average, targets: targets)
                }
                .card()
                if !weeks.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Por semana").font(.headline)
                        Text("Tu meta semanal es tu meta diaria × 7. En verde, lo que quedó por debajo; en la semana actual, el margen que puedes usar.")
                            .font(.caption).foregroundStyle(.secondary)
                        ForEach(weeks, id: \.start) { week in WeekRow(week: week) }
                    }
                    .card()
                }
                ForEach(logged.reversed(), id: \.date) { day in
                    NavigationLink(value: day.date) {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(dayLabel(day.date)).foregroundStyle(Color.primary)
                                Text(macroLine(day.totals)).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(kcal(day.totals.calories)).font(.subheadline.weight(.semibold)).foregroundStyle(Color.primary)
                            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                        }
                        .card(padding: 14)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding()
        }
        .screenBackground()
        .navigationTitle("Historial")
        .navigationDestination(for: Day.self) { day in TodayView(initialDate: day) }
    }
}

private struct CaloriesChart: View {
    let days: [DayIntake]
    let target: Double?

    var body: some View {
        Chart {
            ForEach(days, id: \.date) { day in
                BarMark(x: .value("Día", day.date.date, unit: .day), y: .value("kcal", day.totals.calories))
                    .foregroundStyle(target.map { day.totals.calories > $0 * 1.1 } == true ? AppColor.error : AppColor.primary)
                    .cornerRadius(4)
            }
            if let target, target > 0 {
                RuleMark(y: .value("Objetivo", target))
                    .foregroundStyle(Color.secondary)
                    .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [6, 4]))
            }
        }
        .chartXAxis {
            AxisMarks(values: .stride(by: .day, count: days.count <= 7 ? 1 : days.count <= 14 ? 2 : 5)) { _ in
                AxisValueLabel(format: .dateTime.day().month(.abbreviated), centered: true)
            }
        }
        .environment(\.locale, spanish)
        .frame(height: 170)
    }
}
