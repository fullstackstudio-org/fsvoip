// SPDX-License-Identifier: AGPL-3.0-or-later
import Combine
import Core
import Foundation

/// The "Opnames" screen of ONE paired account (an admin only; the whole screen sits behind the local check).
/// The calls of one month come from `GET /calls?month=`; the ones with `hasRecording` can be played.
@MainActor
final class RecordingsModel: ObservableObject {
    let account: StoredAccount

    @Published private(set) var page: CallsPage?
    @Published private(set) var month: String?
    @Published private(set) var isLoading = false
    @Published private(set) var failure: MediaFailure?
    @Published private(set) var nowPlaying: NowPlaying?
    @Published private(set) var goneIds: Set<String> = []
    @Published var banner: MediaBanner?

    private let hub: MediaHub
    private let service: MediaServicing
    private let gate: LocalAccessGate
    private let player: AudioStreamer
    private var observers: Set<AnyCancellable> = []
    private var loadGeneration = 0

    init(account: StoredAccount, hub: MediaHub) {
        self.account = account
        self.hub = hub
        service = hub.service
        gate = hub.gate
        player = hub.player

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

    /// The calls that have (or had) a recording, newest first.
    var rows: [CallItem] {
        (page?.calls ?? [])
            .filter { $0.hasRecording || $0.recordingExpired }
            .sorted { ($0.startedSort ?? "") > ($1.startedSort ?? "") }
    }

    /// The months to choose from; the shown month is always in the list.
    var months: [String] {
        var list = page?.months ?? []

        if let current = page?.month, !list.contains(current) { list.insert(current, at: 0) }

        return list
    }

    var monthTitle: String {
        (page?.month ?? month).map { MediaFormat.month($0) } ?? ""
    }

    var isTruncated: Bool {
        page?.truncated == true
    }

    var nowPlayingId: String? {
        nowPlaying?.id
    }

    func isGone(_ call: CallItem) -> Bool {
        !call.hasRecording || goneIds.contains(call.id)
    }

    func title(for call: CallItem) -> String {
        if let number = call.number, !number.isEmpty {
            return hub.nameLookup(number) ?? number
        }

        if let name = call.extensionName, !name.isEmpty { return name }

        return L10n.string("media.voicemail.anonymous")
    }

    func subtitle(for call: CallItem) -> String {
        var parts = [call.startedLabel]

        if !call.durationLabel.isEmpty { parts.append(call.durationLabel) }

        switch call.direction {
        case .inbound: parts.append(L10n.string("media.direction.inbound"))
        case .outbound: parts.append(L10n.string("media.direction.outbound"))
        case .internal: parts.append(L10n.string("media.direction.internal"))
        case .unknown: break
        }

        if let name = call.extensionName, !name.isEmpty, call.number != nil { parts.append(name) }

        return parts.filter { !$0.isEmpty }.joined(separator: " · ")
    }

    // MARK: Loading

    func load(month: String? = nil) async {
        let wanted = month ?? self.month
        loadGeneration += 1
        let token = loadGeneration
        isLoading = page == nil || month != nil

        do {
            let result = try await service.calls(for: account, month: wanted)

            guard token == loadGeneration else { return }

            page = result
            self.month = result.month
            failure = nil
        } catch {
            guard token == loadGeneration else { return }

            handle(error)
        }

        isLoading = false
    }

    func select(month: String) async {
        guard month != page?.month else { return }

        player.stop()
        gate.touch()
        await load(month: month)
    }

    // MARK: Playing

    func play(_ call: CallItem) {
        banner = nil

        guard call.hasRecording, !goneIds.contains(call.id) else { return }

        gate.touch()

        do {
            let source = try service.recordingSource(for: account, callId: call.id)
            let service = service
            let account = account
            nowPlaying = NowPlaying(id: call.id, title: title(for: call), subtitle: subtitle(for: call))
            player.play(id: call.id, source: source) { request in
                try await service.download(request, for: account)
            }
        } catch {
            handle(error)
        }
    }

    func retryPlaying() {
        guard let id = nowPlaying?.id, let call = page?.calls.first(where: { $0.id == id }) else { return }

        play(call)
    }

    func closePlayer() {
        player.stop()
    }

    private func playerStateChanged(_ state: AudioStreamer.State) {
        guard case let .failed(failure) = state, let id = nowPlaying?.id else { return }

        switch failure {
        case .gone, .notFound:
            goneIds.insert(id)
        case .accessDenied:
            hub.partDenied(.recordings, accountId: account.id)
        case .revoked:
            hub.onRevoked?()
        default:
            break
        }
    }

    private func handle(_ error: Error) {
        // The screen or SwiftUI gave up on the request: nothing went wrong, so nothing to say (never "no connection").
        if APIError.isCancellation(error) { return }

        let result = MediaFailure.classify(error)

        switch result {
        case .accessDenied:
            hub.partDenied(.recordings, accountId: account.id)
        case .revoked:
            hub.onRevoked?()
        default:
            break
        }

        if page != nil, result != .accessDenied, result != .revoked {
            banner = MediaBanner(text: result.message(for: .recording), isError: true)
        } else {
            failure = result
        }
    }
}
