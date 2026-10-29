import SwiftUI

/// Accepted weights, same range the server checks.
let weightRange = BodyMetric.weight.range

private func round1(_ value: Double) -> Double { (value * 10).rounded() / 10 }

func kg(_ value: Double) -> String { "\(Gym.formatKg(round1(value))) kg" }

/// "18.5%" or "3.1 kg" depending on the measurement.
func metricText(_ metric: BodyMetric, _ value: Double) -> String {
    metric.unit == "%" ? "\(Gym.formatKg(round1(value)))%" : kg(value)
}

func metricColor(_ metric: BodyMetric) -> Color {
    switch metric {
    case .weight: return Brand.amber
    case .fat: return Brand.pink
    case .muscle: return Brand.emerald
    case .bone: return Brand.sky
    }
}

/// "Grasa 18.5% · Músculo 41% · Hueso 3.1 kg" for the measurements taken.
func compositionLine(_ log: BodyLog) -> String {
    [BodyMetric.fat, .muscle, .bone]
        .compactMap { metric in metric.of(log).map { "\(metric.label) \(metricText(metric, $0))" } }
        .joined(separator: " · ")
}

/// "▼ 0.4%" in green when the change is good news (less fat, more muscle, weight toward the goal),
/// amber when it is not. Nothing for changes too small to matter.
struct TrendPill: View {
    let metric: BodyMetric
    let change: Double?
    var goalLosing: Bool? = nil

    var body: some View {
        if let change, abs(change) >= 0.05 {
            let good: Bool? = {
                if let up = metric.upIsGood { return up ? change > 0 : change < 0 }
                if metric == .weight, let goalLosing { return (change < 0) == goalLosing }
                return nil
            }()
            Pill(
                text: (change < 0 ? "▼ " : "▲ ") + metricText(metric, abs(change)),
                tint: good.map { ($0 ? Brand.emerald : Brand.amber).opacity(0.22) }
            )
        }
    }
}

/// Latest body fat, muscle and bone mass with their change since the previous measurement.
struct CompositionRow: View {
    let logs: [BodyLog]

    var body: some View {
        let metrics = [BodyMetric.fat, .muscle, .bone].compactMap { metric in BodyStats.trend(logs, metric).map { (metric, $0) } }
        if !metrics.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .top) {
                    ForEach(metrics, id: \.0) { item in
                        VStack(spacing: 4) {
                            StatTile(value: metricText(item.0, item.1.current), label: item.0.label, color: metricColor(item.0))
                            TrendPill(metric: item.0, change: item.1.change)
                        }
                        .frame(maxWidth: .infinity)
                    }
                }
                if let log = logs.filter({ $0.fatPercent != nil }).max(by: { $0.day < $1.day }),
                   let fat = log.fatMassKg, let lean = log.leanMassKg {
                    Text("Masa grasa \(kg(fat)) · masa magra \(kg(lean))").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }
}

/// Chart of the last 180 days, with chips to switch between weight, fat, muscle and bone.
struct BodyChart: View {
    let logs: [BodyLog]
    let goalKg: Double?
    @State private var chosen = BodyMetric.weight

    var body: some View {
        let since = Day.today.adding(days: -180).iso
        let recent = logs.filter { $0.day >= since }
        let metrics = BodyStats.chartable(recent)
        if !metrics.isEmpty {
            let metric = metrics.contains(chosen) ? chosen : metrics[0]
            let series = BodyStats.series(recent, metric)
            VStack(alignment: .leading, spacing: 6) {
                if metrics.count > 1 {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(metrics, id: \.self) { option in
                                Pill(text: option.label, selected: option == metric) { chosen = option }
                            }
                        }
                    }
                }
                LineChartView(values: series.map(\.1), reference: metric == .weight ? goalKg : nil, color: metricColor(metric))
                if let first = series.first, let last = series.last {
                    let change = last.1 - first.1
                    Text((change <= 0 ? "▼ " : "▲ ") + metricText(metric, abs(change)) + " desde " + (Day(iso: first.0).map { dayLabel($0) } ?? first.0))
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }
}

/// Current weight, goal progress, body composition and the chart.
struct WeightCard: View {
    let weights: [WeightEntry]
    let goal: WeightGoal?
    let onLog: () -> Void
    let onGoal: () -> Void
    let onHistory: () -> Void

    var body: some View {
        let logs = weights.map(\.log)
        let current = weights.last
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("⚖️ Peso y composición").font(.headline)
                    Text(current.map { "Último registro: \(dayLabel($0.date))" } ?? "Registra tu peso para ver tu progreso")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 4) {
                    Text(current.map { kg($0.kg) } ?? "—").font(.title.bold())
                    TrendPill(metric: .weight, change: BodyStats.trend(logs, .weight)?.change, goalLosing: goal?.losing)
                }
            }
            if let goal {
                let now = current?.kg ?? goal.start
                let remaining = goal.remaining(now)
                HStack {
                    StatTile(value: kg(goal.start), label: "Inicio")
                    StatTile(value: kg(goal.goal), label: "Meta")
                    StatTile(value: remaining < 0.05 ? "¡Lograda!" : kg(remaining), label: goal.losing ? "Por bajar" : "Por subir")
                }
                let progress = goal.progress(now)
                ProgressBar(value: progress, color: Brand.amber, height: 10)
                Text("\(Int(progress * 100))% del camino a tu meta").font(.caption).foregroundStyle(.secondary)
            }
            if logs.contains(where: \.hasComposition) {
                Divider()
                CompositionRow(logs: logs)
            }
            BodyChart(logs: logs, goalKg: goal?.goal)
            HStack {
                Button(action: onLog) { Text("Registrar").frame(maxWidth: .infinity) }
                Button(action: onGoal) { Text(goal == nil ? "Poner meta" : "Editar meta").frame(maxWidth: .infinity) }
            }
            .buttonStyle(.bordered)
            if !weights.isEmpty {
                Button("Ver mis registros (\(weights.count))", action: onHistory).frame(maxWidth: .infinity)
            }
        }
        .card()
    }
}

/// Logs (or edits) a day's weight and, optionally, body fat %, muscle % and bone mass. Without
/// [fixedDate] it offers today or yesterday and loads what that day already has.
struct BodyLogSheet: View {
    let entries: [WeightEntry]
    var fixedDate: Day? = nil
    var onDelete: ((Day) -> Void)? = nil
    let onSave: (WeightEntry) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var yesterday = false
    @State private var weight = ""
    @State private var fat = ""
    @State private var muscle = ""
    @State private var bone = ""

    private var date: Day { fixedDate ?? Day.today.adding(days: yesterday ? -1 : 0) }
    private var existing: WeightEntry? { entries.first { $0.date == date } }
    // Earlier values are shown as hints, not filled in, so an old measurement is not saved by mistake.
    private var previous: WeightEntry? { entries.last { $0.date < date } }

    private var problems: [String] {
        var result: [String] = []
        if !(toAmount(weight).map { weightRange.contains($0) } ?? false) {
            result.append("El peso debe estar entre 20 y 400 kg.")
        }
        for (metric, text) in [(BodyMetric.fat, fat), (.muscle, muscle), (.bone, bone)] where !text.isEmpty {
            if let value = toAmount(text), metric.range.contains(value) { continue }
            result.append("\(metric.label): entre \(Gym.formatKg(metric.range.lowerBound)) y \(Gym.formatKg(metric.range.upperBound)) \(metric.unit).")
        }
        return result
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if fixedDate == nil {
                        Picker("Día", selection: $yesterday) {
                            Text("Hoy").tag(false)
                            Text("Ayer").tag(true)
                        }
                        .pickerStyle(.segmented)
                    } else {
                        Text(dayLabel(date)).font(.headline)
                    }
                    NumberField(label: "Peso (kg)", text: $weight)
                }
                Section {
                    HStack {
                        NumberField(label: "Grasa %", text: $fat, placeholder: previous?.fatPercent.map(inputAmount))
                        NumberField(label: "Músculo %", text: $muscle, placeholder: previous?.musclePercent.map(inputAmount))
                    }
                    NumberField(label: "Masa ósea (kg)", text: $bone, placeholder: previous?.boneKg.map(inputAmount))
                } header: {
                    Text("Composición corporal (opcional)")
                } footer: {
                    Text("Anota los valores de tu báscula o análisis (InBody, bioimpedancia…). Pésate a la misma hora, idealmente en la mañana, para comparar mejor.")
                }
                if !weight.isEmpty, !problems.isEmpty {
                    Section {
                        ForEach(problems, id: \.self) { ErrorText(message: $0) }
                    }
                }
                if existing != nil, let onDelete {
                    Section {
                        Button("Borrar registro", role: .destructive) {
                            onDelete(date)
                            dismiss()
                        }
                    }
                }
            }
            .navigationTitle(existing != nil ? "Editar registro" : "Registrar peso")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Guardar") {
                        guard let value = toAmount(weight) else { return }
                        onSave(WeightEntry(date: date, kg: value, fatPercent: toAmount(fat), musclePercent: toAmount(muscle), boneKg: toAmount(bone)))
                        dismiss()
                    }
                    .disabled(!problems.isEmpty)
                }
            }
            .keyboardDoneButton()
            .onAppear(perform: load)
            .onChange(of: yesterday) { _, _ in load() }
        }
        .presentationDetents([.large])
    }

    private func load() {
        weight = (existing?.kg ?? previous?.kg).map(inputAmount) ?? ""
        fat = existing?.fatPercent.map(inputAmount) ?? ""
        muscle = existing?.musclePercent.map(inputAmount) ?? ""
        bone = existing?.boneKg.map(inputAmount) ?? ""
    }
}

/// Every weigh-in, newest first; tapping one opens it for editing.
struct BodyHistorySheet: View {
    let entries: [WeightEntry]
    let onEdit: (WeightEntry) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(entries.sorted { $0.date > $1.date }) { entry in
                Button { onEdit(entry) } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(dayLabel(entry.date))
                            let composition = compositionLine(entry.log)
                            if !composition.isEmpty { Text(composition).font(.caption).foregroundStyle(.secondary) }
                        }
                        Spacer()
                        Text(kg(entry.kg)).font(.headline)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .navigationTitle("Mis registros")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Cerrar") { dismiss() } } }
        }
    }
}

struct WeightGoalSheet: View {
    let goal: WeightGoal?
    let currentKg: Double?
    let share: Bool
    let onSave: (WeightGoal?, Bool) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var start = ""
    @State private var target = ""
    @State private var shared = true

    var body: some View {
        let startKg = toAmount(start)
        let targetKg = toAmount(target)
        let valid = startKg.map { weightRange.contains($0) } == true && targetKg.map { weightRange.contains($0) } == true
        NavigationStack {
            Form {
                Section {
                    HStack {
                        NumberField(label: "Peso inicial", text: $start)
                        NumberField(label: "Peso meta", text: $target)
                    }
                }
                Section {
                    Toggle(isOn: $shared) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Mostrarlo en mis grupos")
                            Text("Tus amigos verán tu peso, tu composición corporal, tu meta y tu avance.")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                if goal != nil {
                    Section {
                        Button("Quitar meta", role: .destructive) {
                            onSave(nil, shared)
                            dismiss()
                        }
                    }
                }
            }
            .navigationTitle("Meta de peso")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Guardar") {
                        guard let startKg, let targetKg else { return }
                        onSave(WeightGoal(start: startKg, goal: targetKg), shared)
                        dismiss()
                    }
                    .disabled(!valid)
                }
            }
            .keyboardDoneButton()
            .onAppear {
                start = (goal?.start ?? currentKg).map(inputAmount) ?? ""
                target = goal.map { inputAmount($0.goal) } ?? ""
                shared = share
            }
        }
        .presentationDetents([.medium, .large])
    }
}
