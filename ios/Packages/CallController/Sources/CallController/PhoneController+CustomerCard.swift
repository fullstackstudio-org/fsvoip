// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The customer card of an incoming call. Strictly best effort: the call is already reported to the system and ringing when this
// starts, a slow or failed lookup changes nothing, and an answer that comes after the call ended is dropped.

import Core
import Foundation

extension PhoneController {
    /// Ask for the card of the caller of `uuid`. Once per call. Does nothing without `lookupCaller`, for an outgoing call, an anonymous
    /// caller or an internal number (a colleague is not a customer).
    func startCallerLookup(_ uuid: UUID) {
        guard let lookup = lookupCaller,
              callerLookups[uuid] == nil,
              let session = session(uuid),
              session.direction == .incoming,
              let number = Self.nonEmpty(session.remoteNumber),
              Self.looksLikeExternalNumber(number),
              let account = accounts[session.accountId.rawValue]
        else {
            return
        }

        let timeout = callerLookupTimeout

        callerLookups[uuid] = Task { [weak self] in
            let context: CallerContext? = await CallerLookupTimeout.run(seconds: timeout) {
                await lookup(number, account)
            }

            guard let self, !Task.isCancelled else {
                return
            }

            self.applyCallerContext(context, to: uuid)
            self.callerLookups[uuid] = nil
        }
    }

    /// For tests: wait until the lookup of a call is done.
    func finishCallerLookup(_ uuid: UUID) async {
        await callerLookups[uuid]?.value
    }

    private func applyCallerContext(_ context: CallerContext?, to uuid: UUID) {
        guard let context, let current = session(uuid), !current.phase.isEnded, let account = accounts[current.accountId.rawValue] else {
            return
        }

        // A name from the contacts of the phone or the address book wins over the card; the card fills the gap (and replaces the bare
        // caller-id text of the PBX).
        let localName = current.remoteNumber.flatMap(lookupName)
        let newName = localName ?? context.displayName ?? current.remoteName
        let nameChanged = newName != current.remoteName

        update(uuid) {
            $0.callerContext = context
            $0.remoteName = newName
        }

        if nameChanged {
            system.reportCallUpdated(uuid: uuid, displayName: callScreenText(name: newName, number: current.remoteNumber, account: account))
        }
    }

    /// Internal extension numbers (2-4 digits) are colleagues; the website knows customers.
    static func looksLikeExternalNumber(_ number: String) -> Bool {
        number.filter(\.isNumber).count >= 6
    }
}
