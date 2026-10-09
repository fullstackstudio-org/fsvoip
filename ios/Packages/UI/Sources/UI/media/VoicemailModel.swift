// SPDX-License-Identifier: AGPL-3.0-or-later
import Combine
import Core
import Foundation

/// The "Voicemail" screen of ONE paired account.
///
/// - A `user` pairing (`access.voicemail == .own`) sees its own box only; nothing here asks for Face ID.
/// - An admin starts on the own box (found by the extension number). "Alle voicemail" and the box of a colleague are open only
///   after the local check (`LocalAccessGate`, five minutes); when it expires, the screen falls back to the own box and stops the
///   sound of a colleague's message.
/// - Deleting is possible in the own box (everyone) and, after the check, in the others (admin); a frozen phone system blocks it.
@MainActor
final class VoicemailModel: ObservableObject {
    enum Scope: Hashable {
        case own
        case all
        case box(String)
    }

    let account: StoredAccount

    @Published private(set) var page: VoicemailPage?
    @Published private(set) var scope: Scope = .own
    @Published private(set) var isLoading = false
    @Published private(set) var isDeleting = false
    @Published private(set) var failure: MediaFailure?
    @Published private(set) var othersUnlocked = false
    @Published private(set) var nowPlaying: NowPlaying?
    /// Messages the server said are past their retention.
    @Published private(set) var goneIds: Set<String> = []
    @Published private(set) var noPasscode = false
    @Published var banner: MediaBanner?

    private let hub: MediaHub
    private let service: MediaServicing
    private let gate: LocalAccessGate
    private let player: AudioStreamer
    private var serverReadOnly = false
    private var observers: Set<AnyCancellable> = []
    private let authReason: () -> String

    init(account: StoredAccount, hub: MediaHub, authReason: @escaping () -> String = { L10n.string("media.lock.reason.voicemail") }) {
        self.account = account
        self.hub = hub
        service = hub.service
        gate = hub.gate
        player = hub.player
        self.authReason = authReason

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
        hub.$access
            .sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &observers)
    }

    // MARK: Rights

    var access: MediaHub.Access {
        hub.access(for: account.id) ?? MediaHub.Access(voicemail: .noAccess, recordings: false, readOnly: true)
    }

    /// An admin sees every box and can choose.
    var canChooseBox: Bool {
        access.voicemail == .all
    }

    var isReadOnly: Bool {
        access.readOnly || serverReadOnly
    }

    // MARK: Reading

    var boxes: [VoicemailBox] {
        page?.boxes ?? []
    }

    /// The box of the extension of this phone (an admin's page lists every box; the number tells which one is ours).
    var ownBoxId: String? {
        guard let page else { return nil }

        if access.voicemail != .all {
            return page.boxes.first?.id
        }

        guard let number = account.extensionNumber else { return nil }

        return page.boxes.first { !$0.shared && $0.number == number }?.id
    }

    func isOwn(_ message: VoicemailMessage) -> Bool {
        access.voicemail != .all || message.boxId == ownBoxId
    }

    /// The messages the screen may show right now (never a colleague's while locked).
    var messages: [VoicemailMessage] {
        guard let page else { return [] }

        let sorted = page.messages.sorted { ($0.receivedSort ?? "") > ($1.receivedSort ?? "") }

        guard access.voicemail == .all else {
            return sorted
        }

        switch scope {
        case .own:
            return sorted.filter(isOwn)
        case .all:
            return othersUnlocked ? sorted : []
        case let .box(id):
            return othersUnlocked ? sorted.filter { $0.boxId == id } : []
        }
    }

    /// The admin asked for more than the own box and has not unlocked (yet): show the lock instead of a list.
    var needsUnlock: Bool {
        canChooseBox && scope != .own && !othersUnlocked
    }

    var scopeTitle: String {
        switch scope {
        case .own:
            return L10n.string("media.voicemail.scope.own")
        case .all:
            return L10n.string("media.voicemail.scope.all")
        case let .box(id):
            return boxes.first { $0.id == id }.map(boxTitle) ?? L10n.string("media.voicemail.scope.all")
        }
    }

    func boxTitle(_ box: VoicemailBox) -> String {
        [box.name, box.number].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
    }

    /// The page says the phone system did not answer: a notice, not an empty list.
    var isUnavailable: Bool {
        page?.available == false
    }

    func title(for message: VoicemailMessage) -> String {
        if let name = message.callerName, !name.isEmpty { return name }

        if let caller = message.caller, !caller.isEmpty {
            return hub.nameLookup(caller) ?? caller
        }

        return L10n.string("media.voicemail.anonymous")
    }

    func subtitle(for message: VoicemailMessage) -> String {
        var parts = [message.receivedLabel]

        if !message.durationLabel.isEmpty { parts.append(message.durationLabel) }

        if canChooseBox, !isOwn(message) || scope != .own { parts.append(message.boxName) }

        return parts.filter { !$0.isEmpty }.joined(separator: " · ")
    }

    func isGone(_ message: VoicemailMessage) -> Bool {
        goneIds.contains(message.id) || message.daysLeft.map { $0 < 0 } == true
    }

    var nowPlayingId: String? {
        nowPlaying?.id
    }

    // MARK: Loading

    func load() async {
        isLoading = page == nil

        do {
            let result = try await service.voicemail(for: account, box: nil)
            page = result
            failure = nil
            serverReadOnly = false

            // An admin without a box of the own extension has only the locked "all" to show.
            if canChooseBox, ownBoxId == nil, scope == .own {
                scope = .all
            }

            dropVanishedSelection()
        } catch {
            handle(error)
        }

        isLoading = false
    }

    func reload() async {
        refreshLock()
        await load()
    }

    private func dropVanishedSelection() {
        if case let .box(id) = scope, !boxes.contains(where: { $0.id == id }) {
            scope = ownBoxId != nil ? .own : .all
        }
    }

    // MARK: The local check

    /// Choose another box; anything but the own box asks for Face ID / the passcode first.
    func select(_ newScope: Scope) async {
        banner = nil

        guard newScope != .own else {
            scope = .own

            return
        }

        guard canChooseBox else { return }

        switch await gate.ensureUnlocked(reason: authReason()) {
        case .unlocked:
            othersUnlocked = true
            noPasscode = false
            scope = newScope
        case .cancelled, .failed:
            break
        case .unavailable:
            noPasscode = true
        }
    }

    /// The lock card asks to open what is chosen.
    func unlock() async {
        await select(scope == .own ? .all : scope)
    }

    /// The five minutes are over (or the app came back): fall back to the own box and stop a colleague's message.
    func refreshLock() {
        guard othersUnlocked, !gate.isUnlocked else { return }

        othersUnlocked = false

        if scope != .own {
            scope = ownBoxId != nil ? .own : .all
        }

        if let id = nowPlaying?.id, let message = page?.messages.first(where: { $0.id == id }), !isOwn(message) {
            player.stop()
        }
    }

    // MARK: Playing

    func play(_ message: VoicemailMessage) {
        banner = nil

        guard messages.contains(where: { $0.id == message.id }), !isGone(message) else { return }

        if !isOwn(message) { gate.touch() }

        do {
            let source = try service.voicemailSource(for: account, boxId: message.boxId, ref: message.ref)
            let service = service
            let account = account
            nowPlaying = NowPlaying(id: message.id, title: title(for: message), subtitle: subtitle(for: message))
            player.play(id: message.id, source: source) { request in
                try await service.download(request, for: account)
            }
        } catch {
            handle(error)
        }
    }

    func retryPlaying() {
        guard let id = nowPlaying?.id, let message = page?.messages.first(where: { $0.id == id }) else { return }

        play(message)
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
            hub.partDenied(.voicemail, accountId: account.id)
        case .revoked:
            hub.onRevoked?()
        default:
            break
        }
    }

    // MARK: Deleting

    func delete(_ message: VoicemailMessage) async {
        banner = nil

        guard !isReadOnly else {
            banner = MediaBanner(text: L10n.string("media.delete.readOnly"), isError: true)

            return
        }

        if !isOwn(message) {
            // A colleague's message: the check has to be open now.
            guard gate.isUnlocked else {
                refreshLock()
                await select(.all)

                return
            }

            gate.touch()
        }

        if nowPlaying?.id == message.id { player.stop() }

        isDeleting = true
        defer { isDeleting = false }

        do {
            try await service.deleteVoicemail(for: account, boxId: message.boxId, ref: message.ref)
            remove(message)
        } catch {
            switch MediaFailure.classify(error) {
            case .notFound:
                // Gone already (deleted on another phone): the list was out of date.
                remove(message)
            case .readOnly:
                serverReadOnly = true
                banner = MediaBanner(text: L10n.string("media.delete.readOnly"), isError: true)
            default:
                handle(error)
            }
        }
    }

    private func remove(_ message: VoicemailMessage) {
        page?.messages.removeAll { $0.id == message.id }
    }

    // MARK: Failures

    private func handle(_ error: Error) {
        // Cancelled by the screen or by SwiftUI: not a failure, never "no connection".
        if APIError.isCancellation(error) { return }

        let result = MediaFailure.classify(error)

        switch result {
        case .accessDenied:
            hub.partDenied(.voicemail, accountId: account.id)
        case .revoked:
            hub.onRevoked?()
        default:
            break
        }

        if page != nil, result != .accessDenied, result != .revoked {
            // The list on screen is still good: say what went wrong without taking it away.
            banner = MediaBanner(text: result.message(for: .voicemail), isError: true)
        } else {
            failure = result
        }
    }
}
