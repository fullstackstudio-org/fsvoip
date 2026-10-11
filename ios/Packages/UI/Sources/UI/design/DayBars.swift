// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

// MARK: - The week as days with time slots (pure)

/// A week of opening hours: per day (1 = Monday ... 7 = Sunday) up to four time slots, as text (`HH:MM`; `24:00` = until
/// midnight). Equal when it reads the same: a day that is switched off and a day that never had hours are the same.
struct WeekPlan: Equatable {
    static let weekdays = Array(1 ... 7)
    /// The most slots one day can have.
    static let slotsPerDay = 4
    static let defaultSlot = HoursInterval(from: "09:00", to: "17:00")

    var days: [Int: [HoursInterval]]

    init(days: [Int: [HoursInterval]] = [:]) {
        self.days = days
    }

    init(_ week: [HoursWeekEntry]) {
        var days: [Int: [HoursInterval]] = [:]

        for entry in week.sorted(by: { ($0.day, $0.from) < ($1.day, $1.from) }) {
            days[entry.day, default: []].append(HoursInterval(from: entry.from, to: entry.to))
        }

        self.days = days
    }

    /// The week as the API wants it.
    var entries: [HoursWeekEntry] {
        Self.weekdays.flatMap { day in
            (days[day] ?? []).map { HoursWeekEntry(day: day, from: $0.from, to: $0.to) }
        }
    }

    func slots(_ day: Int) -> [HoursInterval] {
        days[day] ?? []
    }

    static func == (lhs: WeekPlan, rhs: WeekPlan) -> Bool {
        lhs.entries == rhs.entries
    }

    // MARK: Presets

    enum Preset: CaseIterable, Identifiable {
        /// Monday to Friday 09:00-17:00.
        case office
        /// Every day all day.
        case always
        /// Closed every day.
        case closed

        var id: Self { self }

        var titleKey: String {
            switch self {
            case .office: return "numbers.hours.preset.office"
            case .always: return "numbers.hours.preset.always"
            case .closed: return "numbers.hours.preset.closed"
            }
        }
    }

    static func preset(_ preset: Preset) -> WeekPlan {
        switch preset {
        case .office:
            return WeekPlan(days: Dictionary(uniqueKeysWithValues: (1 ... 5).map { ($0, [defaultSlot]) }))
        case .always:
            return WeekPlan(days: Dictionary(uniqueKeysWithValues: weekdays.map { ($0, [HoursInterval(from: "00:00", to: "24:00")]) }))
        case .closed:
            return WeekPlan()
        }
    }

    /// The preset this week is, if it is one.
    var matchingPreset: Preset? {
        Preset.allCases.first { Self.preset($0) == self }
    }
}

/// The arithmetic behind a day bar: where the lime blocks go, and whether a slot has a valid shape. Pure, so it can be tested.
enum DayBarsLogic {
    static let minutesPerDay = 24 * 60

    /// `"09:30"` -> 570, `"24:00"` -> 1440. `nil` when it is not `HH:MM` within the day.
    static func minutes(_ text: String) -> Int? {
        let parts = text.split(separator: ":", omittingEmptySubsequences: false)

        guard parts.count == 2, parts[0].count == 2, parts[1].count == 2, let hour = Int(parts[0]), let minute = Int(parts[1]) else {
            return nil
        }

        guard (0 ... 24).contains(hour), (0 ... 59).contains(minute), hour < 24 || minute == 0 else {
            return nil
        }

        return hour * 60 + minute
    }

    /// The shape the server accepts: two valid times, the end after the start. (Overlaps and the rest are the server's call.)
    static func isValid(_ slot: HoursInterval) -> Bool {
        guard let from = minutes(slot.from), let to = minutes(slot.to) else {
            return false
        }

        return from < to && from < minutesPerDay
    }

    /// The blocks of one day as fractions of the bar (0 = 00:00, 1 = 24:00), sorted, invalid slots left out.
    static func segments(_ slots: [HoursInterval]) -> [ClosedRange<Double>] {
        slots.compactMap { slot -> ClosedRange<Double>? in
            guard isValid(slot), let from = minutes(slot.from), let to = minutes(slot.to) else {
                return nil
            }

            return Double(from) / Double(minutesPerDay) ... Double(to) / Double(minutesPerDay)
        }
        .sorted { $0.lowerBound < $1.lowerBound }
    }

    /// What VoiceOver says for a day: "Maandag: 09:00 tot 17:00" or "Maandag: gesloten".
    static func accessibilityValue(_ slots: [HoursInterval]) -> String {
        let valid = slots.filter(isValid)

        guard !valid.isEmpty else {
            return L10n.string("numbers.hours.day.closed")
        }

        return valid.map { String(format: L10n.string("numbers.hours.slot.spoken"), $0.from, $0.to) }.joined(separator: ", ")
    }

    /// A new slot for a day that already has some: after the last one, if there is room; else the default.
    static func nextSlot(after slots: [HoursInterval]) -> HoursInterval {
        guard let last = slots.compactMap({ minutes($0.to) }).max(), last < minutesPerDay - 60 else {
            return WeekPlan.defaultSlot
        }

        let start = last
        let end = min(start + 4 * 60, minutesPerDay)

        return HoursInterval(from: format(start), to: format(end))
    }

    static func format(_ minutes: Int) -> String {
        String(format: "%02d:%02d", minutes / 60, minutes % 60)
    }
}

// MARK: - Views

/// Per day a bar from 00:00 to 24:00 with the opening hours as lime blocks. A tap on a day opens its time slots.
struct DayBars: View {
    let week: WeekPlan
    var isEnabled = true
    let onSelect: (Int) -> Void

    @ScaledMetric(relativeTo: .body) private var dayWidth: CGFloat = 96
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        VStack(spacing: 0) {
            if !typeSize.isAccessibilitySize {
                scale
            }

            ForEach(WeekPlan.weekdays, id: \.self) { day in
                Button {
                    onSelect(day)
                } label: {
                    row(day)
                }
                .buttonStyle(RowButtonStyle())
                .disabled(!isEnabled)
                .accessibilityLabel(PbxVocabulary.weekday(day))
                .accessibilityValue(DayBarsLogic.accessibilityValue(week.slots(day)))
                .accessibilityHint(L10n.string("numbers.hours.day.hint"))
                .accessibilityIdentifier("daybar-\(day)")
            }
        }
    }

    /// 00:00 · 06:00 · 12:00 · 18:00 · 24:00 above the bars.
    private var scale: some View {
        HStack(spacing: Theme.Spacing.m) {
            Color.clear.frame(width: dayWidth, height: 1)
            GeometryReader { proxy in
                ForEach([0, 6, 12, 18, 24], id: \.self) { hour in
                    Text(String(format: "%02d:00", hour))
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(Theme.textTertiary)
                        .fixedSize()
                        .position(x: proxy.size.width * CGFloat(hour) / 24, y: 8)
                }
            }
            .frame(height: 16)
        }
        .padding(.horizontal, Theme.Spacing.l)
        .padding(.bottom, Theme.Spacing.xs)
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private func row(_ day: Int) -> some View {
        let label = Text(PbxVocabulary.weekday(day))
            .font(.body)
            .foregroundStyle(Theme.textPrimary)

        if typeSize.isAccessibilitySize {
            // The largest sizes: the name above a full-width bar, so nothing is cut off.
            VStack(alignment: .leading, spacing: Theme.Spacing.s) {
                label
                bar(day)
            }
            .settingsRowChrome()
        } else {
            HStack(spacing: Theme.Spacing.m) {
                label
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    .frame(width: dayWidth, alignment: .leading)
                bar(day)
            }
            .settingsRowChrome()
        }
    }

    private func bar(_ day: Int) -> some View {
        let segments = DayBarsLogic.segments(week.slots(day))

        return GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Theme.card(6).fill(Theme.separator)

                ForEach(Array(segments.enumerated()), id: \.offset) { _, segment in
                    Theme.card(6)
                        .fill(Theme.accent.opacity(isEnabled ? 0.85 : 0.35))
                        .frame(width: max(2, proxy.size.width * (segment.upperBound - segment.lowerBound)))
                        .offset(x: proxy.size.width * segment.lowerBound)
                }

                // Hairlines at 06:00, 12:00 and 18:00.
                ForEach([0.25, 0.5, 0.75], id: \.self) { mark in
                    Rectangle()
                        .fill(Theme.ink.opacity(0.35))
                        .frame(width: 1)
                        .offset(x: proxy.size.width * mark)
                }
            }
        }
        .frame(height: 28)
    }
}

/// The slots of one day: up to four `from`-`to` pairs, a quick "closed" and the presets for the whole week.
struct DaySlotsEditor: View {
    let day: Int
    @Binding var slots: [HoursInterval]
    var isEnabled = true

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            SettingsGroup(title: PbxVocabulary.weekday(day), footer: L10n.string("numbers.hours.day.footer")) {
                if slots.isEmpty {
                    Text(L10n.string("numbers.hours.day.closed"))
                        .foregroundStyle(Theme.textSecondary)
                        .settingsRowChrome()
                }

                ForEach($slots) { $slot in
                    HStack(spacing: Theme.Spacing.s) {
                        TimeField(label: L10n.string("pbx.hours.from"), text: $slot.from, isEnd: false)
                        Text("–").foregroundStyle(Theme.textSecondary).accessibilityHidden(true)
                        TimeField(label: L10n.string("pbx.hours.to"), text: $slot.to, isEnd: true)

                        if !DayBarsLogic.isValid(slot) {
                            Image(systemName: "exclamationmark.circle.fill")
                                .foregroundStyle(Theme.danger)
                                .accessibilityLabel(L10n.string("numbers.hours.slot.invalid"))
                        }

                        Spacer(minLength: Theme.Spacing.s)

                        Button(role: .destructive) {
                            slots.removeAll { $0.id == slot.id }
                        } label: {
                            Image(systemName: "minus.circle.fill")
                                .font(.title3)
                                .foregroundStyle(Theme.danger)
                                .frame(width: Theme.minimumTarget, height: Theme.minimumTarget)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(L10n.string("pbx.hours.removeSlot"))
                    }
                    .settingsRowChrome()
                }

                if slots.count < WeekPlan.slotsPerDay {
                    Button {
                        slots.append(DayBarsLogic.nextSlot(after: slots))
                    } label: {
                        SettingsRow(symbol: "plus", title: L10n.string("pbx.hours.addSlot"), showsChevron: false)
                    }
                    .buttonStyle(RowButtonStyle())
                    .accessibilityIdentifier("dayslots-add")
                }
            }
            .disabled(!isEnabled)
        }
    }
}
