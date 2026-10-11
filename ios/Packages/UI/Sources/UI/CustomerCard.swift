// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The customer card: the line on the call screen ("Klant in website: 2 open bestellingen, 1 open verzoek") and the timeline of a contact.

import Core
import SwiftUI

/// The text on the call screen. Pure, so it is unit tested; only what the server counted is shown.
enum CallerLine {
    /// `nil` = nothing to say. A recognised contact without open items still says "Klant in website".
    static func text(_ context: CallerContext?, string: (String) -> String = { L10n.string($0) }) -> String? {
        guard let context else {
            return nil
        }

        var parts: [String] = []

        if context.openOrders > 0 {
            parts.append(String(format: string(context.openOrders == 1 ? "callcard.orders.one" : "callcard.orders.other"), context.openOrders))
        }

        if context.openRequests > 0 {
            parts.append(String(format: string(context.openRequests == 1 ? "callcard.requests.one" : "callcard.requests.other"), context.openRequests))
        }

        guard !parts.isEmpty else {
            return string("callcard.known")
        }

        return String(format: string("callcard.prefix"), parts.joined(separator: ", "))
    }
}

/// The paged timeline of one contact. The first page loads once; "Meer laden" follows the cursor. A failure keeps what is on screen.
@MainActor
final class TimelineModel: ObservableObject {
    enum Phase: Equatable {
        case idle
        case loading
        case failed
    }

    @Published private(set) var items: [TimelineItem] = []
    @Published private(set) var phase: Phase = .idle
    @Published private(set) var nextCursor: String?
    @Published private(set) var hasLoaded = false

    private let service: CustomerCardServicing
    private let account: StoredAccount
    private let contactId: String
    private let pageSize: Int

    init(service: CustomerCardServicing, account: StoredAccount, contactId: String, pageSize: Int = 20) {
        self.service = service
        self.account = account
        self.contactId = contactId
        self.pageSize = pageSize
    }

    var canLoadMore: Bool {
        nextCursor != nil && phase != .loading
    }

    func loadFirstPage() async {
        guard !hasLoaded, phase != .loading else {
            return
        }

        await load(cursor: nil)
    }

    func loadMore() async {
        guard let nextCursor, phase != .loading else {
            return
        }

        await load(cursor: nextCursor)
    }

    private func load(cursor: String?) async {
        phase = .loading

        do {
            let page = try await service.timeline(contactId: contactId, cursor: cursor, limit: pageSize, for: account)
            var seen = Set(items.map(\.id))

            // The cursor can overlap a little: an id is shown once.
            items.append(contentsOf: page.items.filter { seen.insert($0.id).inserted })
            nextCursor = page.nextCursor
            hasLoaded = true
            phase = .idle
        } catch {
            if APIError.isCancellation(error) {
                phase = .idle
            } else {
                phase = .failed
            }
        }
    }
}

/// "Activiteit" on the contact screen.
struct ContactTimelineSection: View {
    @StateObject private var timeline: TimelineModel

    init(service: CustomerCardServicing, account: StoredAccount, contactId: String) {
        _timeline = StateObject(wrappedValue: TimelineModel(service: service, account: account, contactId: contactId))
    }

    var body: some View {
        SettingsGroup(title: L10n.string("timeline.title")) {
            if timeline.items.isEmpty {
                if timeline.phase == .loading || (!timeline.hasLoaded && timeline.phase != .failed) {
                    SettingsRow(title: L10n.string("timeline.loading"), showsChevron: false)
                } else if timeline.phase == .failed {
                    retry
                } else {
                    SettingsRow(title: L10n.string("timeline.empty"), showsChevron: false)
                }
            }

            ForEach(timeline.items) { item in
                row(item)
            }

            if !timeline.items.isEmpty, timeline.phase == .failed {
                retry
            }

            if timeline.canLoadMore, timeline.phase != .failed {
                Button {
                    Task { await timeline.loadMore() }
                } label: {
                    SettingsRow(symbol: "arrow.down.circle", title: L10n.string("timeline.more"), showsChevron: false)
                }
                .buttonStyle(RowButtonStyle())
                .accessibilityIdentifier("timeline-more")
            }
        }
        .task { await timeline.loadFirstPage() }
        .accessibilityIdentifier("contact-timeline")
    }

    private var retry: some View {
        Button {
            Task {
                if timeline.hasLoaded { await timeline.loadMore() } else { await timeline.loadFirstPage() }
            }
        } label: {
            SettingsRow(symbol: "arrow.clockwise", title: L10n.string("timeline.error"), showsChevron: false)
        }
        .buttonStyle(RowButtonStyle())
    }

    private func row(_ item: TimelineItem) -> some View {
        HStack(alignment: .top, spacing: Theme.Spacing.m) {
            Image(systemName: Self.symbol(for: item.kind))
                .font(.footnote.weight(.bold))
                .foregroundStyle(Theme.textSecondary)
                .frame(width: 20)
                .padding(.top, 3)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(item.title)
                    .font(.body)
                    .foregroundStyle(Theme.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)

                if let summary = item.summary, !summary.isEmpty {
                    Text(summary)
                        .font(.subheadline)
                        .foregroundStyle(Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Text(Self.subtitle(item))
                    .font(.footnote)
                    .foregroundStyle(Theme.textTertiary)
            }

            Spacer(minLength: Theme.Spacing.s)
        }
        .settingsRowChrome()
        .accessibilityElement(children: .combine)
    }

    static func subtitle(_ item: TimelineItem) -> String {
        let when = item.occurredAtDate.map { $0.formatted(date: .abbreviated, time: .shortened) }

        return [item.ref, when].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
    }

    /// An icon per kind the server may send; an unknown kind gets a neutral dot.
    static func symbol(for kind: String) -> String {
        let key = kind.lowercased()

        if key.contains("order") { return "bag" }
        if key.contains("ticket") || key.contains("request") { return "bubble.left" }
        if key.contains("call") || key.contains("voice") { return "phone" }
        if key.contains("appointment") { return "calendar" }
        if key.contains("form") || key.contains("submission") { return "doc.text" }
        if key.contains("mail") || key.contains("email") { return "envelope" }
        if key.contains("sms") { return "message" }
        if key.contains("invoice") || key.contains("payment") { return "eurosign.circle" }

        return "circle.fill"
    }
}
