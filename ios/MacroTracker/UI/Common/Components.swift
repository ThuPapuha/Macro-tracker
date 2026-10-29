import Charts
import SwiftUI

/// Calories eaten against the goal: a 270° ring like on Android.
struct CalorieRing: View {
    let consumed: Double
    let target: Double?
    var diameter: CGFloat = 124

    var body: some View {
        let over = target.map { consumed > $0 * 1.1 } ?? false
        let progress = target.map { $0 > 0 ? min(max(consumed / $0, 0), 1) : 0 } ?? 0
        ZStack {
            Circle()
                .trim(from: 0, to: 0.75)
                .stroke(AppColor.track, style: StrokeStyle(lineWidth: 12, lineCap: .round))
                .rotationEffect(.degrees(135))
            Circle()
                .trim(from: 0, to: 0.75 * progress)
                .stroke(over ? AppColor.error : AppColor.primary, style: StrokeStyle(lineWidth: 12, lineCap: .round))
                .rotationEffect(.degrees(135))
                .animation(.easeOut, value: progress)
            VStack(spacing: 2) {
                Text(formatAmount(consumed)).font(.title2.bold())
                Text(target.map { "de \(formatAmount($0)) kcal" } ?? "kcal").font(.caption2).foregroundStyle(.secondary)
                if let target {
                    let left = target - consumed
                    Text(left >= 0 ? "Quedan \(formatAmount(left))" : "+\(formatAmount(-left))")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(over ? AppColor.error : AppColor.primary)
                }
            }
        }
        .frame(width: diameter, height: diameter)
    }
}

struct MacroBar: View {
    let label: String
    let consumed: Double
    let target: Double?
    let color: Color

    var body: some View {
        let hasTarget = (target ?? 0) > 0
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(label).font(.subheadline.weight(.semibold))
                Spacer()
                Text(hasTarget ? "\(formatAmount(consumed)) / \(formatAmount(target!)) g" : "\(formatAmount(consumed)) g")
                    .font(.caption).foregroundStyle(.secondary)
            }
            ProgressBar(value: hasTarget ? consumed / target! : 0, color: color)
        }
    }
}

/// A rounded horizontal bar filled to [value] (0…1).
struct ProgressBar: View {
    let value: Double
    var color: Color = AppColor.primary
    var height: CGFloat = 8

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(color.opacity(0.18))
                Capsule().fill(color).frame(width: proxy.size.width * min(max(value, 0), 1))
            }
        }
        .frame(height: height)
    }
}

struct MacroBars: View {
    let totals: Macros
    let targets: MacroTargets?

    var body: some View {
        VStack(spacing: 10) {
            MacroBar(label: "Proteína", consumed: totals.protein, target: targets?.protein, color: MacroColor.protein)
            MacroBar(label: "Carbohidratos", consumed: totals.carbs, target: targets?.carbs, color: MacroColor.carbs)
            MacroBar(label: "Grasas", consumed: totals.fat, target: targets?.fat, color: MacroColor.fat)
        }
    }
}

struct MacroSummaryCard: View {
    let totals: Macros
    let targets: MacroTargets?

    var body: some View {
        HStack(spacing: 16) {
            CalorieRing(consumed: totals.calories, target: (targets?.calories ?? 0) > 0 ? targets?.calories : nil)
            MacroBars(totals: totals, targets: targets)
        }
        .card()
    }
}

/// Up to two initials; works with names that start with an emoji.
func initials(_ name: String) -> String {
    let letters = name.split(whereSeparator: \.isWhitespace).prefix(2).compactMap { $0.first.map { String($0).uppercased() } }
    return letters.isEmpty ? "?" : letters.joined()
}

/// Java's String.hashCode, so a person gets the same avatar colors on Android and iOS.
private func javaHash(_ text: String) -> Int32 {
    var hash: Int32 = 0
    for unit in text.utf16 { hash = hash &* 31 &+ Int32(unit) }
    return hash
}

private let avatarPalette: [(UInt32, UInt32)] = [
    (0x10B981, 0x3B82F6), (0xF472B6, 0x8B5CF6), (0xF59E0B, 0xEF4444), (0x06B6D4, 0x6366F1),
    (0x84CC16, 0x10B981), (0xEC4899, 0xF59E0B), (0x8B5CF6, 0x06B6D4), (0xEF4444, 0xF472B6),
]

/// A colorful circle with the person's initials; the colors depend on [seed] (their user id).
struct Avatar: View {
    let name: String
    let seed: String
    var size: CGFloat = 40

    var body: some View {
        let (from, to) = avatarPalette[Int(javaHash(seed) & Int32.max) % avatarPalette.count]
        Circle()
            .fill(LinearGradient(colors: [Color(hex: from), Color(hex: to)], startPoint: .topLeading, endPoint: .bottomTrailing))
            .overlay(Text(initials(name)).font(.system(size: size * 0.36, weight: .bold)).foregroundStyle(.white).lineLimit(1))
            .frame(width: size, height: size)
    }
}

/// Avatar with a ring like a story: it fills with today's [progress] and turns into a rainbow when
/// [highlight] (goal reached).
struct StoryAvatar: View {
    let name: String
    let seed: String
    let progress: Double?
    let highlight: Bool
    var size: CGFloat = 64

    var body: some View {
        ZStack {
            if highlight {
                Circle().stroke(Brand.storyRing, lineWidth: 3)
            } else {
                Circle().stroke(AppColor.track, lineWidth: 3)
                if let progress, progress > 0 {
                    Circle()
                        .trim(from: 0, to: min(progress, 1))
                        .stroke(AppColor.primary, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                }
            }
            Avatar(name: name, seed: seed, size: size - 12)
        }
        .frame(width: size, height: size)
    }
}

/// A small rounded label; tappable when [action] is set.
struct Pill: View {
    let text: String
    var selected = false
    var tint: Color? = nil
    var action: (() -> Void)? = nil

    var body: some View {
        let label = Text(text)
            .font(.subheadline.weight(.semibold))
            .lineLimit(1)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .foregroundStyle(selected ? AppColor.primary : Color.primary)
            .background(tint ?? (selected ? AppColor.primarySoft : AppColor.cardHigh), in: Capsule())
        if let action {
            Button(action: action) { label }.buttonStyle(.plain)
        } else {
            label
        }
    }
}

/// Emojis offered to react to a post.
let reactionEmojis = ["👍", "🔥", "💪", "👏", "😂"]

/// Reaction counts, a button to react and the comments button, under each post.
struct ReactionBar: View {
    let reactions: [String: Int]
    let mine: String?
    let commentCount: Int
    let onReact: (String) -> Void
    let onComments: () -> Void

    var body: some View {
        HStack(spacing: 6) {
            ForEach(Array(reactions.sorted { ($0.value, $1.key) > ($1.value, $0.key) }.prefix(4)), id: \.key) { item in
                Pill(text: "\(item.key) \(item.value)", selected: item.key == mine) { onReact(item.key) }
            }
            Menu {
                ForEach(reactionEmojis, id: \.self) { emoji in
                    Button(emoji == mine ? "\(emoji) ✓" : emoji) { onReact(emoji) }
                }
            } label: {
                Text(reactions.isEmpty ? "🙂 Reaccionar" : "🙂+")
                    .font(.subheadline.weight(.semibold))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(AppColor.cardHigh, in: Capsule())
                    .foregroundStyle(Color.primary)
            }
            Spacer()
            Button(action: onComments) {
                Text(commentCount == 0 ? "💬 Comentar" : "💬 \(commentCount)").font(.subheadline.weight(.semibold))
            }
            .buttonStyle(.borderless)
        }
    }
}

/// Applies my reaction toggle to the counts shown, before the server answers.
func toggledReactions(_ reactions: [String: Int], mine: String?, emoji: String) -> ([String: Int], String?) {
    var counts = reactions
    if let mine {
        let left = (counts[mine] ?? 1) - 1
        counts[mine] = left <= 0 ? nil : left
    }
    if mine == emoji { return (counts, nil) }
    counts[emoji, default: 0] += 1
    return (counts, emoji)
}

/// Header of a section, with an optional action on the right.
struct SectionHeader: View {
    let title: String
    var action: String? = nil
    var onAction: (() -> Void)? = nil

    var body: some View {
        HStack {
            Text(title).font(.title2.bold())
            Spacer()
            if let action, let onAction { Button(action, action: onAction) }
        }
        .padding(.top, 8)
    }
}

/// A rounded card filled with a brand gradient and white content.
struct GradientCard<Content: View>: View {
    let gradient: LinearGradient
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 6) { content }
            .foregroundStyle(.white)
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(gradient, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
    }
}

/// Big number with a caption, for stats rows.
struct StatTile: View {
    let value: String
    let label: String
    var color: Color? = nil

    var body: some View {
        VStack(spacing: 2) {
            Text(value).font(.title3.bold()).foregroundStyle(color ?? Color.primary).lineLimit(1).minimumScaleFactor(0.7)
            Text(label).font(.caption2).foregroundStyle(color.map { $0.opacity(0.85) } ?? Color.secondary).lineLimit(1)
        }
        .frame(maxWidth: .infinity)
    }
}

/// Simple line chart of [values] (oldest first) with an optional dashed [reference] line.
struct LineChartView: View {
    let values: [Double]
    var reference: Double? = nil
    var color: Color = AppColor.primary

    var body: some View {
        let all = values + (reference.map { [$0] } ?? [])
        let low = all.min() ?? 0
        let high = all.max() ?? 1
        let pad = max((high - low) * 0.1, 0.5)
        Chart {
            ForEach(Array(values.enumerated()), id: \.offset) { index, value in
                LineMark(x: .value("Día", index), y: .value("Valor", value))
                    .foregroundStyle(color)
                    .lineStyle(StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
                    .interpolationMethod(.monotone)
            }
            if let last = values.last {
                PointMark(x: .value("Día", values.count - 1), y: .value("Valor", last)).foregroundStyle(color)
            }
            if let reference {
                RuleMark(y: .value("Meta", reference))
                    .foregroundStyle(Color.secondary)
                    .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [6, 4]))
            }
        }
        .chartXAxis(.hidden)
        .chartYScale(domain: (low - pad)...(high + pad))
        .frame(height: 140)
    }
}

/// Text field for numbers (accepts "12,5" or "12.5").
struct NumberField: View {
    let label: String
    @Binding var text: String
    var placeholder: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if !label.isEmpty { Text(label).font(.caption).foregroundStyle(.secondary) }
            TextField(placeholder ?? "0", text: $text)
                .keyboardType(.decimalPad)
                .textFieldStyle(.roundedBorder)
                .onChange(of: text) { _, new in
                    let filtered = String(new.filter { $0.isNumber || $0 == "." || $0 == "," }.prefix(7))
                    if filtered != new { text = filtered }
                }
        }
    }
}

struct ErrorText: View {
    let message: String
    var body: some View { Text(message).font(.callout).foregroundStyle(AppColor.error) }
}

/// "Guardando…" style spinner row.
struct BusyRow: View {
    let text: String
    var body: some View {
        HStack(spacing: 12) {
            ProgressView()
            Text(text).font(.callout)
        }
        .card()
    }
}

/// Lays its children in rows, wrapping to the next row when one is full (for chips).
struct FlowLayout: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        let rows = arrange(subviews, width: width)
        let height = rows.last.map { $0.y + $0.height } ?? 0
        let used = rows.map(\.width).max() ?? 0
        return CGSize(width: proposal.width ?? used, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for row in arrange(subviews, width: bounds.width) {
            var x = bounds.minX
            for index in row.items {
                let size = subviews[index].sizeThatFits(.unspecified)
                subviews[index].place(at: CGPoint(x: x, y: bounds.minY + row.y), proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
        }
    }

    private struct Row {
        var items: [Int] = []
        var y: CGFloat = 0
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func arrange(_ subviews: Subviews, width: CGFloat) -> [Row] {
        var rows: [Row] = []
        var current = Row()
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            let extra = current.items.isEmpty ? size.width : current.width + spacing + size.width
            if extra > width, !current.items.isEmpty {
                rows.append(current)
                current = Row(y: current.y + current.height + spacing)
            }
            current.width = current.items.isEmpty ? size.width : current.width + spacing + size.width
            current.height = max(current.height, size.height)
            current.items.append(index)
        }
        if !current.items.isEmpty { rows.append(current) }
        return rows
    }
}

/// Chips in a horizontal scroll, one selected.
struct ChipPicker<Value: Hashable>: View {
    let options: [(Value, String)]
    @Binding var selection: Value

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(options, id: \.0) { option in
                    Pill(text: option.1, selected: option.0 == selection) { selection = option.0 }
                }
            }
        }
    }
}
