// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// "Openingstijden" of one number: the week as day bars, the exceptions (holidays, own closed days), and what happens when it
/// is closed.
struct NumberHoursView: View {
    enum Tab: Hashable {
        case week
        case exceptions
    }

    @ObservedObject var model: PbxSectionModel
    let numberId: String

    @State private var draft: NumberHoursDraft
    @State private var baseline: NumberHoursDraft
    @State private var message: ChainEditorMessage?
    @State private var tab = Tab.week
    @Environment(\.dismiss) private var dismiss

    init(model: PbxSectionModel, chain: NumberChain) {
        self.model = model
        numberId = chain.id
        _draft = State(initialValue: NumberHoursDraft(chain))
        _baseline = State(initialValue: NumberHoursDraft(chain))
    }

    private var chain: NumberChain? { model.chains[numberId] }
    private var options: ChainOptions { chain?.options ?? ChainOptions() }

    var body: some View {
        ChainEditorScaffold(model: model, title: L10n.string("numbers.hours.title"), isDirty: draft != baseline, canSave: draft.isWellFormed, message: message, onSave: save) {
            VStack(alignment: .leading, spacing: Theme.Spacing.l) {
                SegmentedBar(options: [
                    .init(value: Tab.week, title: L10n.string("numbers.hours.tab.week")),
                    .init(value: Tab.exceptions, title: L10n.string("numbers.hours.tab.exceptions")),
                ], selection: $tab)
                .accessibilityIdentifier("hours-tabs")

                SharedNote(names: chain?.hours?.sharedWith ?? [])

                switch tab {
                case .week: week
                case .exceptions: NumberExceptionsTab(draft: $draft, options: options)
                }
            }
        }
    }

    private var week: some View {
        VStack(alignment: .leading, spacing: 0) {
            SettingsGroup {
                ToggleRow(title: L10n.string("numbers.hours.toggle"), explanation: L10n.string("numbers.hours.explanation"), isOn: $draft.enabled)
                    .accessibilityIdentifier("hours-toggle")
            }

            if draft.enabled {
                presets

                SettingsGroup(footer: L10n.string("numbers.hours.bars.footer")) {
                    NavigationDayBars(week: $draft.week)
                }

                SettingsGroup(title: L10n.string("numbers.hours.closedSettings")) {
                    NumberFallbackRow(title: L10n.string("numbers.hours.closed"), symbol: "moon", fallback: $draft.closed, options: options)
                }
            } else {
                Text(L10n.string("numbers.hours.off.message"))
                    .font(.footnote)
                    .foregroundStyle(Theme.textSecondary)
                    .padding(.horizontal, Theme.Spacing.l)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var presets: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: Theme.Spacing.s) { presetButtons }
            VStack(alignment: .leading, spacing: Theme.Spacing.s) { presetButtons }
        }
        .padding(.bottom, Theme.Spacing.l)
    }

    @ViewBuilder
    private var presetButtons: some View {
        ForEach(WeekPlan.Preset.allCases) { preset in
            let isCurrent = draft.week.matchingPreset == preset

            Button {
                draft.week = WeekPlan.preset(preset)
            } label: {
                Text(L10n.string(preset.titleKey))
                    .font(.subheadline.weight(isCurrent ? .semibold : .regular))
                    .foregroundStyle(isCurrent ? Theme.accentText : Theme.textPrimary)
                    .padding(.horizontal, Theme.Spacing.m)
                    .frame(minHeight: 36)
                    .background(isCurrent ? Theme.segmentSelected : Theme.raised, in: Capsule())
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(isCurrent ? .isSelected : [])
            .accessibilityIdentifier("hours-preset-\(preset)")
        }
    }

    private func save() {
        guard let chain else { return }

        Task {
            let outcome = await model.saveChainStep(numberId: numberId, draft.step(baseline: baseline, chain: chain))

            if ChainOutcomeHandler.apply(outcome, fresh: model.chains[numberId], draft: &draft, baseline: &baseline, message: &message) {
                dismiss()
            }
        }
    }
}

/// The day bars, each day opening its slots on a page of the sheet.
private struct NavigationDayBars: View {
    @Binding var week: WeekPlan
    @State private var editingDay: Int?

    var body: some View {
        DayBars(week: week) { day in
            editingDay = day
        }
        .navigationDestination(isPresented: Binding(get: { editingDay != nil }, set: { if !$0 { editingDay = nil } })) {
            if let day = editingDay {
                ChainSubPage(title: L10n.string("numbers.hours.title")) {
                    DaySlotsEditor(day: day, slots: Binding(get: { week.days[day] ?? [] }, set: { week.days[day] = $0 }))
                }
            }
        }
    }
}

/// "Uitzonderingen": national holidays, own closed days, and what happens on such a day.
struct NumberExceptionsTab: View {
    @Binding var draft: NumberHoursDraft
    let options: ChainOptions

    @State private var from = Calendar.current.startOfDay(for: Date())
    @State private var until = Calendar.current.startOfDay(for: Date())
    @State private var reason = ""
    @State private var tooMany = false

    private static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Amsterdam") ?? .current

        return calendar
    }

    var body: some View {
        if draft.enabled {
            VStack(alignment: .leading, spacing: 0) {
                SettingsGroup {
                    ToggleRow(title: L10n.string("numbers.hours.national"), explanation: L10n.string("numbers.hours.national.explanation"), isOn: $draft.national)
                        .accessibilityIdentifier("hours-national")
                    NumberOptionalFallbackRow(title: L10n.string("numbers.hours.onHoliday"), symbol: "calendar", fallback: $draft.holiday, noneTitle: L10n.string("numbers.hours.onHoliday.sameAsClosed"), options: options)
                }

                SettingsGroup(title: L10n.string("pbx.closed.header"), footer: String(format: L10n.string("pbx.closed.limit"), NumberHoursDraft.ownDateLimit)) {
                    DatePicker(L10n.string("pbx.closed.from"), selection: $from, displayedComponents: .date)
                        .foregroundStyle(Theme.textPrimary)
                        .settingsRowChrome()
                        .onChange(of: from) { value in
                            if until < value { until = value }
                        }
                    DatePicker(L10n.string("pbx.closed.until"), selection: $until, in: from..., displayedComponents: .date)
                        .foregroundStyle(Theme.textPrimary)
                        .settingsRowChrome()
                    TextField(L10n.string("pbx.closed.reason"), text: $reason)
                        .textInputAutocapitalization(.sentences)
                        .foregroundStyle(Theme.textPrimary)
                        .settingsRowChrome()

                    Button {
                        add()
                    } label: {
                        SettingsRow(symbol: "plus", title: L10n.string("numbers.hours.addClosed"), showsChevron: false)
                    }
                    .buttonStyle(RowButtonStyle())
                    .accessibilityIdentifier("hours-add-closed")
                }

                if tooMany {
                    NoticeCard(symbol: "exclamationmark.circle.fill", tint: Theme.danger, title: String(format: L10n.string("numbers.hours.tooMany"), NumberHoursDraft.ownDateLimit))
                }

                if !draft.dates.isEmpty {
                    SettingsGroup(title: L10n.string("pbx.closed.current")) {
                        ForEach(draft.dates, id: \.date) { date in
                            HStack(spacing: Theme.Spacing.m) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(display(date.date)).foregroundStyle(Theme.textPrimary)
                                    Text(date.name).font(.footnote).foregroundStyle(Theme.textSecondary)
                                }
                                .accessibilityElement(children: .combine)

                                Spacer(minLength: Theme.Spacing.s)

                                Button(L10n.string("pbx.closed.reopen")) {
                                    draft.dates.removeAll { $0.date == date.date }
                                }
                                .font(.body.weight(.semibold))
                                .foregroundStyle(Theme.accentText)
                                .accessibilityLabel(String(format: L10n.string("pbx.closed.reopen.label"), display(date.date)))
                            }
                            .settingsRowChrome()
                        }
                    }
                }
            }
        } else {
            EmptyState(symbol: "calendar.badge.exclamationmark", title: L10n.string("numbers.hours.exceptions.off.title"), message: L10n.string("numbers.hours.exceptions.off.message"))
        }
    }

    private func add() {
        let days = TemporaryClosure.dates(from: from, to: until, calendar: Self.calendar)

        guard let dates = draft.adding(dates: days, name: reason) else {
            tooMany = true
            return
        }

        tooMany = false
        draft.dates = dates
        reason = ""
    }

    private func display(_ date: String) -> String {
        let parts = date.split(separator: "-").compactMap { Int($0) }
        let calendar = Self.calendar

        guard parts.count == 3, let value = calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2])) else {
            return date
        }

        return value.formatted(Date.FormatStyle(date: .complete, time: .omitted, locale: .current, calendar: calendar, timeZone: calendar.timeZone))
    }
}
