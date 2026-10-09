// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

struct HoursView: View {
    @ObservedObject var model: PbxSectionModel

    var body: some View {
        List {
            PbxNoticesSection(model: model)

            if let response = model.hours {
                if response.hours.isEmpty {
                    Section {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(L10n.string("pbx.hours.empty.title"))
                                .font(.headline)
                            Text(L10n.string("pbx.hours.empty.message"))
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 6)
                        .accessibilityElement(children: .combine)
                    }
                }

                ForEach(response.hours) { hours in
                    Section {
                        NavigationLink {
                            HoursEditView(model: model, hours: hours)
                        } label: {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(PbxVocabulary.hoursSummary(hours.week))
                                    .font(.subheadline)
                                Text(String(format: L10n.string("pbx.hours.holidays"), hours.holidays.count))
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                                PbxSyncBadge(sync: hours.sync)
                            }
                            .accessibilityElement(children: .combine)
                        }
                        .accessibilityIdentifier("pbx-hours-row")

                        NavigationLink {
                            TemporaryClosedView(model: model, hoursId: hours.id)
                        } label: {
                            Label(L10n.string("pbx.closed.title"), systemImage: "calendar.badge.exclamationmark")
                        }
                        .accessibilityIdentifier("pbx-closed-link")
                    } header: {
                        Text(hours.name)
                            .textCase(nil)
                            .font(.headline)
                            .foregroundStyle(.primary)
                    }
                }
            } else if model.isLoading {
                ProgressView()
                    .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle(L10n.string("pbx.hours.title"))
        .refreshable { await model.refresh(.hours) }
        .task { await model.loadIfNeeded(.hours) }
        .accessibilityIdentifier("pbx-hours")
    }
}

/// The week of the opening hours and where callers go inside and outside them.
struct HoursEditView: View {
    @ObservedObject var model: PbxSectionModel
    @State private var original: PbxHours
    @State private var draft: HoursDraft
    @State private var failure: PbxFailure?
    @State private var editingDay: Int?
    @Environment(\.dismiss) private var dismiss

    init(model: PbxSectionModel, hours: PbxHours) {
        self.model = model
        _original = State(initialValue: hours)
        _draft = State(initialValue: HoursDraft(hours))
    }

    private var options: [PbxTargetOption] {
        TargetChoice.excluding(original.id, from: model.hours?.targets ?? [])
    }

    private var hasChanges: Bool {
        draft != HoursDraft(original)
    }

    var body: some View {
        Form {
            PbxNoticesSection(model: model)

            PbxFormError(failure: failure)

            Group {
                Section {
                    DayBars(week: WeekPlan(days: draft.days), isEnabled: !model.isReadOnly) { day in
                        editingDay = day
                    }
                    .listRowInsets(EdgeInsets())
                } header: {
                    Text(L10n.string("numbers.hours.tab.week"))
                } footer: {
                    Text(L10n.string("numbers.hours.bars.footer"))
                }

                Section {
                    TargetRow(title: L10n.string("pbx.hours.open.then"), target: $draft.openTarget, options: options)
                    TargetRow(title: L10n.string("pbx.hours.closed.then"), target: $draft.closedTarget, options: options)
                } header: {
                    Text(L10n.string("pbx.hours.targets"))
                } footer: {
                    Text(L10n.string("pbx.hours.targets.footer"))
                }
            }
            .disabled(model.isReadOnly)
        }
        .navigationTitle(original.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                PbxSaveButton(isSaving: model.isSaving, isEnabled: hasChanges && !model.isReadOnly, action: save)
            }
        }
        .navigationDestination(isPresented: Binding(get: { editingDay != nil }, set: { if !$0 { editingDay = nil } })) {
            if let day = editingDay {
                ChainSubPage(title: original.name) {
                    DaySlotsEditor(day: day, slots: Binding(get: { draft.days[day] ?? [] }, set: { draft.days[day] = $0 }), isEnabled: !model.isReadOnly)
                }
            }
        }
        .accessibilityIdentifier("pbx-hours-edit")
    }

    private func save() {
        Task {
            let outcome = await model.saveHours(draft, original: original)

            if outcome.closesForm {
                dismiss()
            } else if case let .failed(reason) = outcome {
                failure = reason
            }
        }
    }
}

/// `HH:MM` as a compact time picker. Midnight at the end of a day is `24:00` on the wire and shows as 23:59; an untouched
/// value is never rewritten.
struct TimeField: View {
    let label: String
    @Binding var text: String
    let isEnd: Bool

    private static func calendar() -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC") ?? .current

        return calendar
    }

    private var date: Date {
        let parts = text.split(separator: ":").compactMap { Int($0) }
        var hour = parts.first ?? 0
        var minute = parts.count > 1 ? parts[1] : 0

        if hour >= 24 {
            hour = 23
            minute = 59
        }

        return Self.calendar().date(from: DateComponents(year: 2000, month: 1, day: 1, hour: hour, minute: minute)) ?? Date(timeIntervalSince1970: 946_684_800)
    }

    var body: some View {
        DatePicker(label, selection: Binding(
            get: { date },
            set: { value in
                let parts = Self.calendar().dateComponents([.hour, .minute], from: value)
                let hour = parts.hour ?? 0
                let minute = parts.minute ?? 0

                text = isEnd && hour == 23 && minute == 59 ? "24:00" : String(format: "%02d:%02d", hour, minute)
            }
        ), displayedComponents: .hourAndMinute)
        .labelsHidden()
        .environment(\.timeZone, TimeZone(identifier: "UTC") ?? .current)
    }
}
