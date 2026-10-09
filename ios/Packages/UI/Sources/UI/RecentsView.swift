// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

struct RecentsView: View {
    @ObservedObject var model: FSVoipAppModel
    @State private var confirmsClear = false

    var body: some View {
        Group {
            if model.recents.isEmpty {
                VStack(spacing: 10) {
                    Image(systemName: "clock")
                        .font(.system(size: 34))
                        .foregroundStyle(.tertiary)
                        .accessibilityHidden(true)
                    L10n.text("recents.empty.title")
                        .font(.headline)
                    L10n.text("recents.empty.body")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding(32)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    ForEach(model.recents) { call in
                        Button {
                            let accountId = model.account(id: call.accountId) != nil ? call.accountId : nil
                            model.call(call.number, from: accountId)
                        } label: {
                            RecentRow(call: call, name: model.name(forNumber: call.number) ?? call.name, showsLine: model.accounts.count > 1)
                        }
                        .disabled(call.number.isEmpty)
                    }
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle(L10n.string("tab.recents"))
        .toolbar {
            if !model.recents.isEmpty {
                Button(L10n.string("recents.clear")) { confirmsClear = true }
            }
        }
        .confirmationDialog(L10n.string("recents.clear.title"), isPresented: $confirmsClear, titleVisibility: .visible) {
            Button(L10n.string("recents.clear.confirm"), role: .destructive) { model.clearRecents() }
        }
    }
}

struct RecentRow: View {
    let call: RecentCall
    /// The name now known for this number (the address book may have learned it since the call), otherwise the one stored with the call.
    let name: String?
    let showsLine: Bool

    private var isMissed: Bool {
        call.direction == .incoming && (call.outcome == .missed)
    }

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: symbol)
                .font(.footnote.weight(.bold))
                .foregroundStyle(isMissed ? Brand.hangUp : Color.secondary)
                .frame(width: 18)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 3) {
                Text(name ?? (call.number.isEmpty ? L10n.string("call.anonymous") : call.number))
                    .font(.body.weight(.medium))
                    .foregroundStyle(isMissed ? Brand.hangUp : Color.primary)
                    .lineLimit(1)

                Text(subtitle)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: 8)

            Text(Self.when(call.startedAt))
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }

    private var symbol: String {
        call.direction == .outgoing ? "arrow.up.right" : "arrow.down.left"
    }

    private var subtitle: String {
        var parts: [String] = []

        if name != nil, !call.number.isEmpty {
            parts.append(call.number)
        }

        switch call.outcome {
        case .answered:
            parts.append(Self.duration(call.duration))
        case .missed:
            parts.append(L10n.string("recents.outcome.missed"))
        case .declined:
            parts.append(L10n.string("recents.outcome.declined"))
        case .notAnswered:
            parts.append(L10n.string("recents.outcome.notAnswered"))
        case .failed:
            parts.append(L10n.string("recents.outcome.failed"))
        }

        if showsLine {
            parts.append(call.accountLabel)
        }

        return parts.joined(separator: " · ")
    }

    /// `0:42`, `5:12`, `1:02:03`.
    static func duration(_ seconds: TimeInterval) -> String {
        let total = max(0, Int(seconds.rounded()))
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        let rest = total % 60

        return hours > 0 ? String(format: "%d:%02d:%02d", hours, minutes, rest) : String(format: "%d:%02d", minutes, rest)
    }

    static func when(_ date: Date) -> String {
        let calendar = Calendar.current

        if calendar.isDateInToday(date) {
            return date.formatted(date: .omitted, time: .shortened)
        }

        if calendar.isDateInYesterday(date) {
            return L10n.string("recents.yesterday")
        }

        return date.formatted(.dateTime.day().month(.abbreviated))
    }
}
