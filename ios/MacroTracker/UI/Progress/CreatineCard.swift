import SwiftUI

/// Daily check-in with an animated, illustrative view of the supplemental creatine level.
struct CreatineCard: View {
    let takenDays: Set<Day>
    let dosesByDay: [Day: Int]
    let doseGrams: Int
    let onToggle: (Day, Bool) -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var weekOffset = 0

    var body: some View {
        let today = Day.today
        let level = Creatine.estimatedLevel(takenDays: takenDays, dosesByDay: dosesByDay, on: today)
        let takenToday = takenDays.contains(today)
        let lastDay = today.adding(days: -weekOffset * 7)
        let firstDay = lastDay.adding(days: -6)

        VStack(alignment: .leading, spacing: 14) {
            Text("Creatina").font(.headline)
            HStack(spacing: 18) {
                CreatineFigure(level: level)
                    .frame(width: 92, height: 158)
                    .animation(reduceMotion ? nil : .easeInOut(duration: 0.8), value: level)
                    .accessibilityLabel("Nivel adicional estimado: \(Int((level * 100).rounded())) por ciento")

                VStack(alignment: .leading, spacing: 8) {
                    Text("\(Int((level * 100).rounded()))%")
                        .font(.system(size: 38, weight: .bold, design: .rounded))
                        .foregroundStyle(Brand.emerald)
                    Text("Nivel adicional estimado")
                        .font(.subheadline.weight(.medium))
                    Text(takenToday ? "Toma de hoy: \(dosesByDay[today] ?? 5) g" : "Hoy aún no hay toma registrada")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Button(takenToday ? "✓ Tomada hoy" : "Marcar toma de hoy") {
                onToggle(today, !takenToday)
            }
            .buttonStyle(.borderedProminent)
            .tint(Brand.emerald)
            .frame(maxWidth: .infinity)

            HStack {
                Button { weekOffset += 1 } label: {
                    Image(systemName: "chevron.left").frame(width: 44, height: 44)
                }
                .accessibilityLabel("Semana anterior")
                Text("\(shortDate(firstDay)) – \(shortDate(lastDay))")
                    .font(.subheadline.weight(.medium))
                    .frame(maxWidth: .infinity)
                Button { weekOffset -= 1 } label: {
                    Image(systemName: "chevron.right").frame(width: 44, height: 44)
                }
                .accessibilityLabel("Semana siguiente")
                .disabled(weekOffset == 0)
            }
            .tint(Brand.emerald)

            HStack(spacing: 5) {
                ForEach((0..<7).reversed(), id: \.self) { offset in
                    let day = lastDay.adding(days: -offset)
                    let taken = takenDays.contains(day)
                    Button { onToggle(day, !taken) } label: {
                        VStack(spacing: 4) {
                            Text(["L", "M", "X", "J", "V", "S", "D"][day.weekdayIndex])
                                .font(.caption2.weight(.semibold))
                            Text(taken ? "✓" : "·").font(.headline)
                            Text("\(day.day)").font(.caption2)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 5)
                        .background(taken ? Brand.emerald.opacity(0.2) : AppColor.cardHigh,
                                    in: RoundedRectangle(cornerRadius: 10))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(day.iso): \(taken ? "tomada, \(dosesByDay[day] ?? 5) gramos" : "sin registrar")")
                }
            }

            Text("Próximas tomas: \(doseGrams) g. Los días ya registrados conservan su dosis; los anteriores a esta función se estiman con 5 g. La figura es ilustrativa y no mide la creatina muscular.")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .card()
    }
}

private struct CreatineFigure: View {
    let level: Double

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                BodySilhouette().fill(AppColor.track)
                BodySilhouette()
                    .fill(LinearGradient(colors: [Brand.sky, Brand.emerald], startPoint: .top, endPoint: .bottom))
                    .mask {
                        VStack(spacing: 0) {
                            Spacer(minLength: 0)
                            Rectangle().frame(height: geometry.size.height * level)
                        }
                    }
                    .shadow(color: Brand.emerald.opacity(level * 0.45), radius: 10)
            }
        }
    }
}

private struct BodySilhouette: Shape {
    func path(in rect: CGRect) -> Path {
        let x = rect.width / 100
        let y = rect.height / 180
        var path = Path()
        path.addEllipse(in: CGRect(x: 37*x, y: 2*y, width: 26*x, height: 27*y))
        path.addRoundedRect(in: CGRect(x: 29*x, y: 32*y, width: 42*x, height: 69*y), cornerSize: CGSize(width: 17*x, height: 17*y))
        path.addRoundedRect(in: CGRect(x: 15*x, y: 37*y, width: 15*x, height: 74*y), cornerSize: CGSize(width: 8*x, height: 8*y))
        path.addRoundedRect(in: CGRect(x: 70*x, y: 37*y, width: 15*x, height: 74*y), cornerSize: CGSize(width: 8*x, height: 8*y))
        path.addRoundedRect(in: CGRect(x: 31*x, y: 94*y, width: 17*x, height: 83*y), cornerSize: CGSize(width: 8*x, height: 8*y))
        path.addRoundedRect(in: CGRect(x: 52*x, y: 94*y, width: 17*x, height: 83*y), cornerSize: CGSize(width: 8*x, height: 8*y))
        return path
    }
}
