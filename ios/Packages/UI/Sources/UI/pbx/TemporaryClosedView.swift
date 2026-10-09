// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// "Tijdelijk dicht": a few days (holiday, building holiday, a day off) on which callers get what they get outside the
/// opening hours. They are own dates in the holiday list; the dates already there stay, and each one can be removed again.
struct TemporaryClosedView: View {
    @ObservedObject var model: PbxSectionModel
    let hoursId: String

    @State private var from = Calendar.current.startOfDay(for: Date())
    @State private var until = Calendar.current.startOfDay(for: Date())
    @State private var reason = ""
    @State private var failure: PbxFailure?
    @State private var planError: TemporaryClosure.PlanError?
    @State private var removing: String?
    @State private var justClosed = false

    private var hours: PbxHours? {
        model.hours?.hours.first { $0.id == hoursId }
    }

    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: hours?.timezone ?? "Europe/Amsterdam") ?? .current

        return calendar
    }

    private var ownDays: [PbxHoliday] {
        (hours?.holidays ?? []).filter { $0.date != nil }.sorted { ($0.date ?? "") < ($1.date ?? "") }
    }

    var body: some View {
        Form {
            PbxNoticesSection(model: model)

            PbxFormError(failure: failure)

            if let planError {
                Section {
                    PbxNotice(symbol: "exclamationmark.circle.fill", tint: Brand.hangUp, title: planMessage(planError), message: nil)
                }
            }

            Section {
                DatePicker(L10n.string("pbx.closed.from"), selection: $from, displayedComponents: .date)
                    .onChange(of: from) { value in
                        if until < value { until = value }
                    }
                DatePicker(L10n.string("pbx.closed.until"), selection: $until, in: from..., displayedComponents: .date)
                TextField(L10n.string("pbx.closed.reason"), text: $reason)
                    .textInputAutocapitalization(.sentences)

                Button {
                    close()
                } label: {
                    HStack {
                        Spacer()
                        if model.isSaving && removing == nil {
                            ProgressView()
                        } else {
                            Text(L10n.string("pbx.closed.action"))
                                .bold()
                        }
                        Spacer()
                    }
                }
                .disabled(model.isReadOnly || model.isSaving || hours == nil)
                .accessibilityIdentifier("pbx-close-action")
            } header: {
                Text(L10n.string("pbx.closed.header"))
            } footer: {
                Text(L10n.string("pbx.closed.footer"))
            }

            if justClosed {
                Section {
                    PbxNotice(symbol: "checkmark.circle.fill", tint: .green, title: L10n.string("pbx.closed.done"), message: nil)
                }
            }

            if !ownDays.isEmpty {
                Section {
                    ForEach(ownDays, id: \.date) { holiday in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(display(holiday.date))
                                Text(holiday.name)
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                            .accessibilityElement(children: .combine)
                            Spacer()
                            Button {
                                reopen(holiday)
                            } label: {
                                if removing == holiday.date {
                                    ProgressView()
                                } else {
                                    Text(L10n.string("pbx.closed.reopen"))
                                }
                            }
                            .buttonStyle(.borderless)
                            .disabled(model.isReadOnly || model.isSaving)
                            .accessibilityLabel(String(format: L10n.string("pbx.closed.reopen.label"), display(holiday.date)))
                        }
                    }
                } header: {
                    Text(L10n.string("pbx.closed.current"))
                } footer: {
                    Text(String(format: L10n.string("pbx.closed.limit"), TemporaryClosure.customDateLimit))
                }
            }
        }
        .navigationTitle(L10n.string("pbx.closed.title"))
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.loadIfNeeded(.hours) }
        .accessibilityIdentifier("pbx-closed")
    }

    private func close() {
        guard let hours else {
            return
        }

        let dates = TemporaryClosure.dates(from: from, to: until, calendar: calendar)

        switch TemporaryClosure.closing(hours, name: reason, dates: dates) {
        case let .failure(error):
            planError = error
            failure = nil
        case let .success(holidays):
            planError = nil
            failure = nil

            Task {
                // The newest version of the hours, not the one the screen was opened with.
                guard let current = self.hours else { return }

                let outcome = await model.saveHolidays(holidays, original: current)
                handle(outcome)
                justClosed = outcome == .saved
            }
        }
    }

    private func reopen(_ holiday: PbxHoliday) {
        guard let hours, let date = holiday.date else {
            return
        }

        removing = date
        justClosed = false

        Task {
            let outcome = await model.saveHolidays(TemporaryClosure.reopening(hours, date: date), original: hours)
            removing = nil
            handle(outcome)
        }
    }

    private func handle(_ outcome: PbxSaveOutcome) {
        if case let .failed(reason) = outcome {
            failure = reason
        } else {
            failure = nil
        }
    }

    private func planMessage(_ error: TemporaryClosure.PlanError) -> String {
        switch error {
        case .emptyRange:
            return L10n.string("pbx.closed.error.range")
        case let .tooMany(room):
            return String(format: L10n.string("pbx.closed.error.tooMany"), TemporaryClosure.customDateLimit, room)
        }
    }

    private func display(_ date: String?) -> String {
        guard let date else {
            return ""
        }

        let parts = date.split(separator: "-").compactMap { Int($0) }

        guard parts.count == 3, let value = calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2])) else {
            return date
        }

        return value.formatted(Date.FormatStyle(date: .complete, time: .omitted, locale: .current, calendar: calendar, timeZone: calendar.timeZone))
    }
}
