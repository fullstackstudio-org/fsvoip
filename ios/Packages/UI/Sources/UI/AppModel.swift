// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import Foundation
import Pairing
import SwiftUI

/// State of the app shell. Task 5 gives it the real pair flow (`pairAction`), accounts and the SIP engine.
@MainActor
public final class FSVoipAppModel: ObservableObject {
    public enum Screen: Equatable {
        /// No account yet: "Koppel je toestel".
        case onboarding
        /// A pairing link arrived (QR scan, universal link or `fsvoip://`); nothing was claimed yet.
        case pairingLink(PairingLink)
    }

    @Published public private(set) var screen: Screen = .onboarding
    @Published public var isScannerPresented = false
    /// Localised, user-facing.
    @Published public private(set) var errorMessage: String?
    @Published public private(set) var accounts: [StoredAccount] = []

    private let accountStore: AccountStore
    private let logger: FSLogger

    /// Exchanges a link for an account. `nil` in the shell: the pair button then explains it is not available yet.
    public var pairAction: (@Sendable (PairingLink) async throws -> StoredAccount)?

    public init(accountStore: AccountStore = AccountStore(), logger: FSLogger = FSLogger(category: "app")) {
        self.accountStore = accountStore
        self.logger = logger
        reloadAccounts()
    }

    public func reloadAccounts() {
        do {
            accounts = try accountStore.accounts()
        } catch {
            logger.error("Accounts could not be read: \(error)")
            accounts = []
        }
    }

    // MARK: Pairing links

    /// An `onOpenURL` / universal link / `NSUserActivity` URL.
    public func handleIncoming(url: URL) {
        do {
            let link = try PairingLinkParser.parse(url)
            show(link)
        } catch {
            fail(error)
        }
    }

    /// Text from the QR scanner (or the paste field of the placeholder).
    @discardableResult
    public func handleScanned(_ text: String) -> Bool {
        do {
            let link = try PairingLinkParser.parse(scanned: text)
            isScannerPresented = false
            show(link)

            return true
        } catch {
            fail(error)

            return false
        }
    }

    public func discardLink() {
        screen = .onboarding
        errorMessage = nil
    }

    public func dismissError() {
        errorMessage = nil
    }

    public var canPair: Bool {
        pairAction != nil
    }

    public func confirmPairing() async {
        guard case let .pairingLink(link) = screen, let pairAction else {
            return
        }

        do {
            _ = try await pairAction(link)
            reloadAccounts()
            screen = .onboarding
        } catch {
            fail(error)
        }
    }

    private func show(_ link: PairingLink) {
        // Never log the link: the token is a one-time credential.
        logger.notice("Pairing link received")
        errorMessage = nil
        screen = .pairingLink(link)
    }

    private func fail(_ error: Error) {
        logger.notice("Pairing link rejected")

        switch error as? PairingLinkError {
        case .notAPairingLink:
            errorMessage = L10n.string("error.notAPairingLink")
        case .missingToken:
            errorMessage = L10n.string("error.missingToken")
        case .malformedToken:
            errorMessage = L10n.string("error.malformedToken")
        case nil:
            errorMessage = L10n.string("error.generic")
        }
    }
}
