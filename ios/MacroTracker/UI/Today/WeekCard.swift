import SwiftUI

/// "20 – 26 oct"
func weekRange(_ week: WeekSummary) -> String {
    week.start.month == week.end.month
        ? "\(week.start.day) – \(shortDate(week.end))"
        : "\(shortDate(week.start)) – \(shortDate(week.end))"
}

/// The week's calorie budget (daily goal × 7) with each day, the margin left by days under the goal
/// (or taken by days over it), what that leaves for today and the days left, and the weekly macros.
struct WeekCard: View {
    let week: WeekSummary

    var body: some View {
        let current = week.today >= week.start && !week.isOver
        let consumed = week.consumed.calories
        let budget = week.weekly.calories
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(current ? "📅 Esta semana" : "📅 Semana").font(.headline)
                Spacer()
                Text(weekRange(week)).font(.subheadline).foregroundStyle(.secondary)
            }
            HStack(alignment: .lastTextBaseline, spacing: 4) {
                Text(formatAmount(consumed)).font(.title2.bold())
                Text("/ \(kcal(budget))").font(.callout).foregroundStyle(.secondary)
            }
            ProgressBar(value: consumed / budget, color: consumed > budget ? AppColor.error : AppColor.primary)
            DayStrip(week: week)
            MarginText(week: week)
            let skipped = week.skippedDays.count
            if skipped > 0 {
                Text(skipped == 1
                    ? "Un día con muy pocas calorías no cuenta para el margen (¿registro incompleto?)."
                    : "\(skipped) días con muy pocas calorías no cuentan para el margen (¿registros incompletos?).")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Divider()
            Text("Macros de la semana").font(.subheadline.weight(.semibold))
            MacroBars(totals: week.consumed, targets: week.weekly)
        }
        .card()
    }
}

private struct MarginText: View {
    let week: WeekSummary

    var body: some View {
        let margin = week.margin
        let small = abs(margin) < 1
        VStack(alignment: .leading, spacing: 6) {
            if week.isOver {
                Text(small ? "Semana cerrada justo en tu meta. 🎯"
                    : margin > 0 ? "Semana cerrada \(kcal(margin)) por debajo de tu meta."
                    : "Semana cerrada \(kcal(-margin)) por encima de tu meta.")
            } else if small {
                Text("Sin margen acumulado: vas justo con tu meta semanal.").foregroundStyle(.secondary)
            } else {
                let positive = margin > 0
                Pill(text: positive ? "Margen: +\(kcal(margin))" : "Te pasaste \(kcal(-margin))",
                     tint: (positive ? Brand.emerald : Brand.amber).opacity(0.22))
                let rest = week.daysLeft == 1 ? "hoy" : "los \(week.daysLeft) días que quedan"
                Text(positive
                    ? "Te sobran calorías de días anteriores. Si quieres usarlas, hoy puedes llegar a \(kcal(week.todayWithMargin)) o repartirlas: ~\(kcal(week.paceForRest ?? 0)) al día \(rest)."
                    : "Para cerrar la semana en tu meta, apunta a ~\(kcal(week.paceForRest ?? 0)) al día \(rest).")
            }
        }
        .font(.callout)
    }
}

/// One mini bar per day: calories against the daily goal, today highlighted.
private struct DayStrip: View {
    let week: WeekSummary
    private let letters = ["L", "M", "X", "J", "V", "S", "D"]

    var body: some View {
        let goal = week.daily.calories
        let maxValue = max(goal * 1.3, week.days.map(\.totals.calories).max() ?? 0)
        VStack(spacing: 2) {
            HStack(alignment: .bottom, spacing: 0) {
                ForEach(Array(week.days.enumerated()), id: \.offset) { _, day in
                    let calories = day.totals.calories
                    ZStack(alignment: .bottom) {
                        RoundedRectangle(cornerRadius: 3).fill(AppColor.track).frame(height: 44 * goal / maxValue)
                        if calories > 0 {
                            RoundedRectangle(cornerRadius: 3)
                                .fill(day.status == .incomplete ? Color.gray : calories > goal * 1.1 ? AppColor.error : AppColor.primary)
                                .frame(height: 44 * calories / maxValue)
                        }
                    }
                    .frame(width: 14, height: 44, alignment: .bottom)
                    .frame(maxWidth: .infinity)
                }
            }
            HStack(spacing: 0) {
                ForEach(Array(week.days.enumerated()), id: \.offset) { index, day in
                    Text(letters[index])
                        .font(.caption2.weight(day.date == week.today ? .bold : .regular))
                        .foregroundStyle(day.date == week.today ? AppColor.primary : Color.secondary)
                        .frame(maxWidth: .infinity)
                }
            }
        }
    }
}

/// One line per week for Historial: eaten against the budget and the difference.
struct WeekRow: View {
    let week: WeekSummary

    var body: some View {
        let diff = week.weekly.calories - week.consumed.calories
        let current = week.today >= week.start && !week.isOver
        HStack {
            VStack(alignment: .leading) {
                Text(weekRange(week) + (current ? " (esta semana)" : ""))
                Text("\(formatAmount(week.consumed.calories)) de \(kcal(week.weekly.calories))").font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if current {
                Pill(text: (week.margin >= 0 ? "Margen +" : "Margen −") + kcal(abs(week.margin)),
                     tint: (week.margin >= 0 ? Brand.emerald : Brand.amber).opacity(0.22))
            } else if week.consumed.calories > 0 {
                Pill(text: (diff >= 0 ? "−" : "+") + kcal(abs(diff)), tint: (diff >= 0 ? Brand.emerald : Brand.amber).opacity(0.22))
            }
        }
    }
}
