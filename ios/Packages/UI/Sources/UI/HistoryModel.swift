// SPDX-License-Identifier: AGPL-3.0-or-later
import Combine
import Core
import Foundation

/// One line of "Geschiedenis": a call of the PBX (the team history) or, while the server cannot say, one of this phone.
struct HistoryEntry: Identifiable, Equatable {
    enum Kind: Equatable {
        case incoming
        case outgoing
        case `internal`
    }

    var id: String
    var accountId: String
    var kind: Kind
    /// The other party's number; `nil` = anonymous.
    var number: String?
    /// A name that came with the call itself (the phone's own record).
    var knownName: String?
    /// Incoming and nobody picked up (a voicemail counts: the caller did not reach anyone).
    var isMissed: Bool
    var startedAt: Date?
    var whenLabel: String
    var durationLabel: String
    /// Who took (or made) the call: shown as the initials in the row.
    var extensionName: String?
    /// Our number that was called or used.
    var ourNumber: String?
    var countryName: String?
    var hasRecording: Bool
    var recordingExpired: Bool
    /// Came from this phone, not from the PBX.
    var isLocal: Bool

    /// The filter of the screen.
    enum Filter: Equatable {
        case all
        case missed
    }
}

extension HistoryEntry {
    init(item: CallItem, accountId: String) {
        let kind: Kind

        switch item.direction {
        case .outbound: kind = .outgoing
        case .internal: kind = .internal
        case .inbound, .unknown: kind = .incoming
        }

        self.init(
            id: "\(accountId)/\(item.id)",
            accountId: accountId,
            kind: kind,
            number: item.number.flatMap { $0.isEmpty ? nil : $0 },
            knownName: nil,
            isMissed: item.direction == .inbound && (item.outcome == .missed || item.outcome == .voicemail),
            startedAt: item.startedSort.flatMap { FSVoipJSON.parseTimestamp($0) },
            whenLabel: item.startedLabel,
            durationLabel: item.outcome == .answered ? item.durationLabel : "",
            extensionName: item.extensionName,
            ourNumber: item.ourNumber,
            countryName: item.countryName,
            hasRecording: item.hasRecording,
            recordingExpired: item.recordingExpired,
            isLocal: false
        )
    }

    init(recent call: RecentCall, extensionName: String?) {
        self.init(
            id: "local/\(call.id)",
            accountId: call.accountId,
            kind: call.direction == .incoming ? .incoming : .outgoing,
            number: call.number.isEmpty ? nil : call.number,
            knownName: call.name,
            isMissed: call.direction == .incoming && call.outcome == .missed,
            startedAt: call.startedAt,
            whenLabel: HistoryFormat.when(call.startedAt),
            durationLabel: call.outcome == .answered ? HistoryFormat.duration(call.duration) : "",
            extensionName: extensionName,
            ourNumber: nil,
            countryName: nil,
            hasRecording: false,
            recordingExpired: false,
            isLocal: true
        )
    }
}

/// The calls of the PBX per account, newest first, a month at a time; and the recording that plays from a detail sheet.
///
/// What a pairing sees is decided by the server (`capabilities.calls`: an admin everything, a `user` the team without recordings).
/// This model never widens it. Without an answer from the server the list is the calls made on this phone.
@MainActor
final class HistoryModel: ObservableObject {
    enum LoadState: Equatable {
        case idle
        case loading
        case loaded
        /// The server did not answer (offline, a hiccup): the local calls are shown.
        case unavailable
    }

    @Published private(set) var pages: [String: [CallsPage]] = [:]
    @Published private(set) var states: [String: LoadState] = [:]
    @Published private(set) var isLoadingMore = false
    @Published private(set) var nowPlaying: NowPlaying?
    @Published var banner: MediaBanner?
    /// Recordings the server said are past their retention.
    @Published private(set) var goneIds: Set<String> = []

    /// A name for a number (contacts, colleagues).
    var nameLookup: (String) -> String? = { _ in nil }

    private let media: MediaHub?
    private var observers: Set<AnyCancellable> = []

    init(media: MediaHub?) {
        self.media = media

        guard let player = media?.player else { return }

        player.$currentId
            .removeDuplicates()
            .sink { [weak self] id in
                if id == nil { self?.nowPlaying = nil }
            }
            .store(in: &observers)
        player.$state
            .removeDuplicates()
            .sink { [weak self] state in self?.playerStateChanged(state) }
            .store(in: &observers)
    }

    // MARK: Reading

    func state(for accountId: String) -> LoadState {
        states[accountId] ?? .idle
    }

    /// Everything the screen lists for this account: the PBX's calls, plus the calls of this phone the PBX has not caught up with.
    func entries(for accountId: String, local: [RecentCall], extensionName: String?) -> [HistoryEntry] {
        let server = (pages[accountId] ?? [])
            .flatMap(\.calls)
            .map { HistoryEntry(item: $0, accountId: accountId) }
            .sorted { Self.isNewer($0, $1) }

        let newest = server.compactMap(\.startedAt).max()

        let phone = local
            .filter { call in call.accountId == accountId && newest.map { call.startedAt > $0 } != false }
            .map { HistoryEntry(recent: $0, extensionName: extensionName) }

        // Without anything from the server, every call of this phone is shown.
        return (phone + server).sorted { Self.isNewer($0, $1) }
    }

    private static func isNewer(_ a: HistoryEntry, _ b: HistoryEntry) -> Bool {
        (a.startedAt ?? .distantPast) > (b.startedAt ?? .distantPast)
    }

    static func filtered(_ entries: [HistoryEntry], filter: HistoryEntry.Filter, kind: HistoryEntry.Kind? = nil) -> [HistoryEntry] {
        entries.filter { entry in
            (filter == .all || entry.isMissed) && (kind == nil || entry.kind == kind)
        }
    }

    func title(for entry: HistoryEntry) -> String {
        if let number = entry.number {
            return nameLookup(number) ?? entry.knownName ?? number
        }

        return entry.knownName ?? L10n.string("call.anonymous")
    }

    /// The month after the ones shown, if the server lists one.
    func nextMonth(for accountId: String) -> String? {
        guard let loaded = pages[accountId], let months = loaded.first?.months else { return nil }

        let shown = Set(loaded.map(\.month))

        return months.first { !shown.contains($0) }
    }

    func isTruncated(_ accountId: String) -> Bool {
        pages[accountId]?.contains { $0.truncated } == true
    }

    // MARK: Loading

    func load(_ account: StoredAccount) async {
        guard let media else {
            states[account.id] = .unavailable

            return
        }

        if pages[account.id] == nil {
            states[account.id] = .loading
        }

        do {
            let page = try await media.service.calls(for: account, month: nil)
            // A refresh replaces the newest month and keeps the older ones the user already opened.
            let older = (pages[account.id] ?? []).filter { $0.month != page.month }
            pages[account.id] = [page] + older
            states[account.id] = .loaded
        } catch {
            if MediaFailure.classify(error) == .revoked { media.onRevoked?() }

            states[account.id] = pages[account.id] == nil ? .unavailable : .loaded
        }
    }

    func loadMore(_ account: StoredAccount) async {
        guard let media, let month = nextMonth(for: account.id), !isLoadingMore else { return }

        isLoadingMore = true
        defer { isLoadingMore = false }

        do {
            let page = try await media.service.calls(for: account, month: month)
            pages[account.id, default: []].append(page)
        } catch {
            banner = MediaBanner(text: MediaFailure.classify(error).message(for: .recording), isError: true)
        }
    }

    func forget(accountId: String) {
        pages[accountId] = nil
        states[accountId] = nil
    }

    // MARK: Recordings (admin)

    /// Only an admin pairing hears recordings; the hub knows (`recordings` comes from `capabilities.recordings` AND the admin role).
    func canPlayRecording(_ entry: HistoryEntry) -> Bool {
        !entry.isLocal && entry.hasRecording && media?.hasRecordings(entry.accountId) == true
    }

    func isRecordingGone(_ entry: HistoryEntry) -> Bool {
        entry.recordingExpired || goneIds.contains(entry.id)
    }

    /// Plays the recording after the local check (Face ID / passcode), like the "Opnames" screen.
    func play(_ entry: HistoryEntry, account: StoredAccount) async {
        banner = nil

        guard let media, canPlayRecording(entry), !isRecordingGone(entry) else { return }

        switch await media.gate.ensureUnlocked(reason: L10n.string("media.lock.reason.recordings")) {
        case .unlocked:
            break
        case .cancelled, .failed:
            return
        case .unavailable:
            banner = MediaBanner(text: L10n.string("media.lock.noPasscode"), isError: true)

            return
        }

        let callId = String(entry.id.dropFirst(account.id.count + 1))

        do {
            let service = media.service
            let source = try service.recordingSource(for: account, callId: callId)
            nowPlaying = NowPlaying(id: entry.id, title: title(for: entry), subtitle: entry.whenLabel)
            media.player.play(id: entry.id, source: source) { request in
                try await service.download(request, for: account)
            }
        } catch {
            banner = MediaBanner(text: MediaFailure.classify(error).message(for: .recording), isError: true)
        }
    }

    func retryPlaying(account: StoredAccount, entries: [HistoryEntry]) {
        guard let id = nowPlaying?.id, let entry = entries.first(where: { $0.id == id }) else { return }

        Task { await play(entry, account: account) }
    }

    func stopPlayer() {
        media?.player.stop()
    }

    private func playerStateChanged(_ state: AudioStreamer.State) {
        guard case let .failed(failure) = state, let id = nowPlaying?.id else { return }

        switch failure {
        case .gone, .notFound:
            goneIds.insert(id)
        case .accessDenied:
            if let accountId = id.split(separator: "/").first { media?.partDenied(.recordings, accountId: String(accountId)) }
        case .revoked:
            media?.onRevoked?()
        default:
            break
        }
    }
}
