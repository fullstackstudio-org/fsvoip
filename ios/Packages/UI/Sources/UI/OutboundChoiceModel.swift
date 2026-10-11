// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import Foundation
import SipEngine

/// The numbers of the PBX an extension may call out with (`GET /me/extension`).
public struct OutboundNumbers: Equatable, Sendable {
    public var numbers: [SelfNumber]
    /// The number the extension calls out with when nothing is chosen.
    public var defaultNumber: String?

    public init(numbers: [SelfNumber], defaultNumber: String?) {
        self.numbers = numbers
        self.defaultNumber = defaultNumber
    }
}

public protocol OutboundNumbersServicing: Sendable {
    func numbers(for account: StoredAccount) async throws -> OutboundNumbers
}

public struct LiveOutboundNumbersService: OutboundNumbersServicing {
    private let api: FSVoipAPIClient

    public init(api: FSVoipAPIClient = FSVoipAPIClient()) {
        self.api = api
    }

    public func numbers(for account: StoredAccount) async throws -> OutboundNumbers {
        let state = try await api.authenticated(with: account.deviceToken).selfExtension()

        return OutboundNumbers(numbers: state.numbers, defaultNumber: state.defaultNumber)
    }
}

/// "Uitbellen via": which number of the PBX the next call goes out with (plan `fsvoip-app-v2`, D5).
///
/// - The choice is local, per account (`AccountPreferences.outboundNumber`). Switching it never touches the network.
/// - It only exists when the server says the PBX understands `X-FSS-From` (`AppCapabilities.callerChoice`). Without that there is
///   no chooser, no chevron and no header: the call goes out with the number of the extension.
/// - It becomes `CallOptions.fromNumber` of the NEXT outgoing call, and of nothing else.
///
/// TODO(Task 0): "anonymous for exactly one call" would be one more flag here, switched off by `consumeOneShot()` after the call.
@MainActor
public final class OutboundChoiceModel: ObservableObject {
    @Published public private(set) var loaded: [String: OutboundNumbers] = [:]

    /// What the server said this pairing may do. Set by the app model.
    var capabilities: (String) -> AppCapabilities? = { _ in nil }

    private let service: OutboundNumbersServicing?
    private let preferences: PreferencesStore
    private var loading: Set<String> = []

    init(service: OutboundNumbersServicing?, preferences: PreferencesStore) {
        self.service = service
        self.preferences = preferences
    }

    // MARK: Reading

    func offersChoice(_ accountId: String) -> Bool {
        capabilities(accountId)?.callerChoice == true
    }

    func numbers(for accountId: String) -> [SelfNumber] {
        offersChoice(accountId) ? loaded[accountId]?.numbers ?? [] : []
    }

    /// The chooser is only worth opening with something to choose from.
    func canChoose(_ accountId: String) -> Bool {
        numbers(for: accountId).count > 1
    }

    /// What the user chose and the PBX still lists.
    private func chosen(_ accountId: String) -> String? {
        guard let stored = preferences.preferences(for: accountId).outboundNumber, numbers(for: accountId).contains(where: { $0.number == stored }) else {
            return nil
        }

        return stored
    }

    /// The number shown as selected: the choice, else the default of the extension, else none.
    func selected(_ accountId: String) -> SelfNumber? {
        let list = numbers(for: accountId)

        if let chosen = chosen(accountId), let number = list.first(where: { $0.number == chosen }) {
            return number
        }

        let defaultNumber = loaded[accountId]?.defaultNumber

        return list.first { $0.number == defaultNumber } ?? list.first { $0.isDefault }
    }

    /// "Hoofdnummer · 085 060 7848", or "Standaardnummer" when there is nothing to show.
    func label(_ accountId: String) -> String {
        guard let number = selected(accountId) else {
            return L10n.string("outbound.default")
        }

        return Self.title(for: number)
    }

    static func title(for number: SelfNumber) -> String {
        let formatted = PbxVocabulary.formatNumber(number.number)

        guard let name = number.name?.trimmingCharacters(in: .whitespaces), !name.isEmpty else {
            return formatted
        }

        return "\(name) · \(formatted)"
    }

    // MARK: Choosing and calling

    /// Remember the choice. No request: the number rides along with the next call.
    func select(_ number: String, accountId: String) {
        guard numbers(for: accountId).contains(where: { $0.number == number }) else {
            return
        }

        var value = preferences.preferences(for: accountId)
        value.outboundNumber = number
        preferences.setPreferences(value, for: accountId)
        objectWillChange.send()
    }

    /// The options of the next outgoing call of this account. Empty unless the PBX supports the choice AND the user chose a number
    /// it lists.
    func options(for accountId: String) -> CallOptions {
        let headers = CallerChoice.headers(choosing: chosen(accountId), capabilities: capabilities(accountId), numbers: loaded[accountId]?.numbers ?? [])

        return CallOptions(fromNumber: headers[CallerChoice.headerName])
    }

    // MARK: Loading

    func load(_ account: StoredAccount) async {
        guard let service, offersChoice(account.id), !loading.contains(account.id) else {
            return
        }

        loading.insert(account.id)
        defer { loading.remove(account.id) }

        // A failure keeps what is known: the chooser then simply stays as it was.
        guard let result = try? await service.numbers(for: account) else {
            return
        }

        loaded[account.id] = result
    }

    func forget(accountId: String) {
        loaded[accountId] = nil
    }
}
