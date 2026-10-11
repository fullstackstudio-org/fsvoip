// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Incoming calls with the app in the background or closed (plan D1, D9, D10, D16).
//
//   PBX push gate ─► FSS ─► APNs VoIP push ─► handleVoipPush (INSIDE the PushKit callback)
//     1. report the call to CallKit at once (Apple: every VoIP push must, even an unusable one)
//     2. wake the pushed account: a fresh REGISTER (Expires 120, `;fss-dev=<installId>`) that the gate waits for
//     3. the INVITE arrives with `X-FSS-Call: <callRef>` and joins the reported call (attachInvite)
//     4. no INVITE before the deadline (the caller hung up, the gate gave up): the call ends as unanswered
//   Answering on the lock screen before the INVITE is there: the answer is remembered and sent when it arrives; the
//   audio only starts in `provider(_:didActivate:)`. Declining first: the late INVITE gets a 603.

import Core
import Foundation
import SipEngine

/// What `handleVoipPush` did with a push.
public enum IncomingPushOutcome: Equatable, Sendable {
    /// Reported as a ringing call with this CallKit UUID; waiting for (or already joined to) its INVITE.
    case ringing(UUID)
    /// Reported and ended at once.
    case rejected(RingPushRejection)
}

extension PhoneController {
    /// Handle the payload of a PushKit push (`PKPushPayload.dictionaryPayload`). MUST be called synchronously inside
    /// the PushKit callback: the call is reported to the system before this returns.
    @discardableResult
    public func handleVoipPush(payload: [AnyHashable: Any]) -> IncomingPushOutcome {
        let message: PushMessage?

        do {
            message = try PushMessage.decode(apnsDictionary: payload)
        } catch {
            logger.notice("VoIP push without a readable payload: \(error)")
            message = nil
        }

        return handleVoipPush(message)
    }

    /// Same, for a decoded message (`nil` = unreadable).
    @discardableResult
    public func handleVoipPush(_ message: PushMessage?) -> IncomingPushOutcome {
        // The engine runs already (the app starts it at launch); make sure, and prepare the audio session before the
        // call is reported (D16).
        start()
        engine.audio.configure()

        let decision = pushPolicy.decide(
            message,
            now: now(),
            knownAccounts: Set(accounts.keys),
            busy: !sessions.isEmpty,
            isHandled: { [unowned self] ref in
                sessions.contains { $0.fssCallRef == ref } || closedCallRefs[ref] != nil
            }
        )

        switch decision {
        case let .ring(ring, deadline):
            return ringFromPush(ring, inviteDeadline: deadline)
        case let .reject(rejection, ring):
            rejectPush(rejection, ring: ring)
            return .rejected(rejection)
        }
    }

    // MARK: Ringing

    private func ringFromPush(_ ring: RingPush, inviteDeadline: Date) -> IncomingPushOutcome {
        guard let account = accounts[ring.accountId], let uuid = IncomingPushPolicy.callUUID(for: ring.callRef) else {
            rejectPush(.invalidPayload, ring: ring)
            return .rejected(.invalidPayload)
        }

        let number = Self.nonEmpty(ring.from.number)
        let name = number.flatMap(lookupName) ?? Self.nonEmpty(ring.from.name)
        var session = CallSession(
            id: uuid,
            engineCallID: nil,
            direction: .incoming,
            accountId: SipAccountID(account.id),
            accountLabel: account.displayLabel,
            remoteNumber: number,
            remoteName: name,
            phase: .incoming,
            createdAt: now()
        )
        session.fssCallRef = ring.callRef
        session.awaitingInvite = true
        sessions.append(session)

        logger.notice("Push for call \(ring.callRef) on account \(account.id): ringing")

        system.reportIncomingCall(uuid: uuid, handle: number ?? anonymousCallerText, displayName: callScreenText(name: name, number: number, account: account)) { [weak self] error in
            guard let self, error != nil else {
                return
            }

            // The system refused (Do Not Disturb, a blocked number, a cellular call): the INVITE gets rejected.
            self.logger.notice("The system refused the call from push \(ring.callRef)")
            self.markEndedByUser(uuid)
            self.finish(uuid, reason: .declined, tellSystem: false)
        }

        // Refused synchronously (a test system; CallKit answers asynchronously): nothing to wait for.
        guard self.session(uuid) != nil else {
            return .rejected(.refusedBySystem)
        }

        // The PBX waits for a FRESH registration of this account before it sends the INVITE.
        wakeRegistration(of: account.id)
        startCallerLookup(uuid)

        let delay = max(0, inviteDeadline.timeIntervalSince(now()))
        inviteTimers[uuid] = schedule(delay) { [weak self] in
            self?.inviteDidNotArrive(uuid)
        }

        return .ringing(uuid)
    }

    private func inviteDidNotArrive(_ uuid: UUID) {
        inviteTimers[uuid] = nil

        guard let session = session(uuid), session.awaitingInvite else {
            return
        }

        logger.notice("No INVITE for call \(session.fssCallRef ?? "?") in time: ended as missed")
        // Answered on the lock screen but the call never came: that failed. Otherwise: the caller hung up (missed).
        finish(uuid, reason: session.answerPending ? .failed("no INVITE") : .unanswered, tellSystem: true)
    }

    // MARK: Rejecting

    private func rejectPush(_ rejection: RingPushRejection, ring: RingPush?) {
        let account = ring.flatMap { accounts[$0.accountId] }

        // Apple's rule: report anyway. A call that is already on the screen keeps its UUID (CallKit answers "already
        // exists", nothing changes); everything else gets a fresh UUID and ends straight away.
        if rejection == .alreadyHandled, let live = sessions.first(where: { $0.fssCallRef == ring?.callRef }) {
            logger.notice("Repeated push for call \(live.fssCallRef ?? "?")")
            system.reportIncomingCall(uuid: live.id, handle: live.remoteNumber ?? anonymousCallerText, displayName: live.remoteTitle ?? anonymousCallerText) { _ in }
            return
        }

        logger.notice("VoIP push rejected: \(rejection)")

        let uuid = UUID()
        let number = Self.nonEmpty(ring?.from.number)
        let name = number.flatMap(lookupName) ?? Self.nonEmpty(ring?.from.name)
        let text = account.map { callScreenText(name: name, number: number, account: $0) } ?? (name ?? number ?? anonymousCallerText)

        system.reportIncomingCall(uuid: uuid, handle: number ?? anonymousCallerText, displayName: text) { [weak self] error in
            guard error == nil else {
                return
            }

            self?.system.reportCallEnded(uuid: uuid, reason: rejection.systemReason)
        }

        if let ring {
            switch rejection {
            case .busy, .expired:
                // A real call that could not ring here: it belongs in the recents as missed.
                if let account {
                    onCallFinished?(RecentCall(number: number ?? "", name: name, accountId: account.id, accountLabel: account.displayLabel, direction: .incoming, outcome: .missed, startedAt: now(), duration: 0))
                }

                // Its INVITE may still come (busy: the PBX did not wait for us): reject it, do not ring.
                rememberClosed(ring.callRef, reject: .busy)
            case .unknownAccount:
                rememberClosed(ring.callRef, reject: .busy)
            case .invalidPayload, .alreadyHandled, .refusedBySystem:
                break
            }
        }
    }

    // MARK: The INVITE

    /// Join an INVITE to the call its push reported. `true` when it was joined (or rejected because the user had
    /// declined in the meantime).
    func attachInvite(_ call: IncomingCall) -> Bool {
        let waiting = sessions.first { session in
            guard session.awaitingInvite, session.engineCallID == nil else {
                return false
            }

            if let ref = call.fssCallRef {
                return session.fssCallRef == ref
            }

            // No X-FSS-Call header (an older PBX module, a forwarded call): the same account and caller within the
            // waiting window (D16 fallback).
            return session.accountId == call.accountId && IncomingPushPolicy.callersMatch(session.remoteNumber, call.from)
        }

        guard let session = waiting else {
            return false
        }

        let uuid = session.id
        inviteTimers.removeValue(forKey: uuid)?()

        update(uuid) {
            $0.engineCallID = call.id
            $0.awaitingInvite = false

            if $0.remoteNumber == nil {
                $0.remoteNumber = call.from
            }
        }

        // The push had no number (anonymous or unreadable) but the INVITE has one: ask for the card now.
        if session.remoteNumber == nil {
            startCallerLookup(uuid)
        }

        logger.notice("INVITE joined to call \(session.fssCallRef ?? "?")")

        // The push had no caller name, the INVITE has one: update the call screen.
        if session.remoteName == nil, let name = call.from.flatMap(lookupName) ?? call.displayName, let account = accounts[session.accountId.rawValue] {
            update(uuid) { $0.remoteName = name }
            system.reportCallUpdated(uuid: uuid, displayName: callScreenText(name: name, number: call.from, account: account))
        }

        if session.answerPending {
            engine.audio.configure()

            do {
                try engine.answer(call.id)
            } catch {
                logger.error("Answer of the joined INVITE failed: \(error)")
                finish(uuid, reason: .failed("answer"), tellSystem: true)
            }
        }

        return true
    }

    // MARK: Helpers

    func rememberClosed(_ ref: String, reject: DeclineReason) {
        let current = now()
        closedCallRefs = closedCallRefs.filter { current.timeIntervalSince($0.value.at) < 120 }
        closedCallRefs[ref] = (current, reject)
    }

    /// The text on the system call screen (D10): `<caller>` or `<caller> → <account>`.
    func callScreenText(name: String?, number: String?, account: StoredAccount) -> String {
        CallDisplay.callerText(
            callerName: name,
            callerNumber: number,
            accountLabel: account.displayLabel,
            showAccount: CallDisplay.shouldShowAccount(setting: preferences.preferences(for: account.id).showCalledAccount, accountCount: accounts.count),
            anonymous: anonymousCallerText
        )
    }

    static func nonEmpty(_ text: String?) -> String? {
        guard let text = text?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else {
            return nil
        }

        return text
    }
}
