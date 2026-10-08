// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The phone: registers the paired accounts with the SIP engine and runs every call through the system call framework
// (Telecom, self-managed; plan D16). Platform logic, not UI: the screens only observe it and ask it to do things.
// A Kotlin port of the iOS `PhoneController`; the rules are the same.
//
// Outgoing:  startCall → system.requestStartCall → performStartCall → engine.call → state changes → report…/ended
// Incoming:  engine.onIncomingCall → system.reportIncomingCall → performAnswerCall → engine.answer
// App closed: FCM `ring` → handleRingPush (reports at once) → wake the account → INVITE with X-FSS-Call joins the call
//
// Everything runs on the main thread. Engine callbacks are posted there through [MainExecutor].

package nl.fullstackstudio.fsvoip.callcontroller

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.fullstackstudio.fsvoip.core.FsLogger
import nl.fullstackstudio.fsvoip.core.PreferencesStore
import nl.fullstackstudio.fsvoip.core.PushMessage
import nl.fullstackstudio.fsvoip.core.RecentCall
import nl.fullstackstudio.fsvoip.core.RingPush
import nl.fullstackstudio.fsvoip.core.StoredAccount
import nl.fullstackstudio.fsvoip.sipengine.CallDirection
import nl.fullstackstudio.fsvoip.sipengine.CallEndReason
import nl.fullstackstudio.fsvoip.sipengine.CallInfo
import nl.fullstackstudio.fsvoip.sipengine.CallState
import nl.fullstackstudio.fsvoip.sipengine.DeclineReason
import nl.fullstackstudio.fsvoip.sipengine.DtmfDigit
import nl.fullstackstudio.fsvoip.sipengine.IncomingCall
import nl.fullstackstudio.fsvoip.sipengine.RegistrationFailure
import nl.fullstackstudio.fsvoip.sipengine.RegistrationState
import nl.fullstackstudio.fsvoip.sipengine.SipAccountConfig
import nl.fullstackstudio.fsvoip.sipengine.SipAccountId
import nl.fullstackstudio.fsvoip.sipengine.SipEngine
import nl.fullstackstudio.fsvoip.sipengine.SipEngineListener

/** What [PhoneController.handleRingPush] did with a push. */
sealed interface IncomingPushOutcome {
    /** Reported as a ringing call with this id; waiting for (or already joined to) its INVITE. */
    data class Ringing(val uuid: UUID) : IncomingPushOutcome

    /** Not rung (the reason says why; a real call that could not ring is in the recents as missed). */
    data class Rejected(val rejection: RingPushRejection) : IncomingPushOutcome
}

class PhoneController(
    private val engine: SipEngine,
    private val system: CallSystem,
    private val audioRouting: AudioRouting,
    private val preferences: PreferencesStore,
    private val logger: FsLogger = FsLogger("phone"),
    /** How long the ended call stays on screen. */
    private val endedLingerMillis: Long = 1500,
    private val now: () -> Instant = Instant::now,
    private val pushPolicy: IncomingPushPolicy = IncomingPushPolicy(),
    private val scheduler: CallScheduler,
    private val mainExecutor: MainExecutor,
) : CallSystemActionHandler {
    private val _registrations = MutableStateFlow<Map<String, RegistrationState>>(emptyMap())
    private val _sessions = MutableStateFlow<List<CallSession>>(emptyList())
    private val _lastEnded = MutableStateFlow<CallSession?>(null)
    private val _isSpeakerOn = MutableStateFlow(false)
    private val _engineStarted = MutableStateFlow(false)

    /** Registration state per account id. */
    val registrations: StateFlow<Map<String, RegistrationState>> = _registrations.asStateFlow()

    /** Calls that are not ended, oldest first (this version has at most one). */
    val sessions: StateFlow<List<CallSession>> = _sessions.asStateFlow()

    /** The call that just ended (kept briefly so the call screen can say so), or `null`. */
    val lastEnded: StateFlow<CallSession?> = _lastEnded.asStateFlow()
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()
    val engineStarted: StateFlow<Boolean> = _engineStarted.asStateFlow()

    /** A finished call, for the local recents. */
    var onCallFinished: ((RecentCall) -> Unit)? = null

    /** Name for a number from the local contact sources. `null` = unknown. */
    var lookupName: (String) -> String? = { null }

    /** Text for an anonymous caller (localised by the app). */
    var anonymousCallerText = "Onbekend"

    private var accounts: Map<String, StoredAccount> = emptyMap()
    private val registeredConfigs = mutableMapOf<String, SipAccountConfig>()

    /** The app is in the background (or was started there by a push). */
    var isInBackground = false
        private set

    /** In the background without a call the accounts are not registered: the PBX push gate wakes the app (D1/D4). */
    var registrationsSuspended = false
        private set

    /** Accounts woken by a push while the others stay suspended. */
    private val awakeAccounts = mutableSetOf<String>()

    /** Cancels the "no INVITE came" timer of a call reported from a push. */
    private val inviteTimers = mutableMapOf<UUID, () -> Unit>()

    /** Calls (by `callRef`) that already ended here, and how a late INVITE for them is rejected. */
    private val closedCallRefs = mutableMapOf<String, Pair<Instant, DeclineReason>>()

    init {
        engine.listener = object : SipEngineListener {
            override fun onRegistrationChanged(engine: SipEngine, state: RegistrationState, account: SipAccountId) =
                mainExecutor.execute { engineRegistrationChanged(state, account) }

            override fun onIncomingCall(engine: SipEngine, call: IncomingCall) = mainExecutor.execute { engineIncomingCall(call) }

            override fun onCallChanged(engine: SipEngine, call: CallInfo) = mainExecutor.execute { engineCallChanged(call) }
        }
        system.handler = this
    }

    val activeSession: CallSession?
        get() = _sessions.value.lastOrNull()

    // MARK: Lifecycle

    fun start() {
        if (_engineStarted.value) {
            return
        }

        try {
            engine.start()
            _engineStarted.value = true
        } catch (error: Exception) {
            logger.error("SIP engine did not start: ${error.message}")
        }
    }

    /**
     * The app went to the background (or was started there by a push). Without a call the accounts un-register: the
     * push gate wakes the app for a call instead, and a dead contact on the PBX would make calls fail.
     */
    fun enterBackground() {
        isInBackground = true

        if (_sessions.value.isEmpty()) {
            suspendRegistrations()
        }

        engine.enterBackground()
    }

    fun enterForeground() {
        isInBackground = false
        resumeRegistrations()
        engine.enterForeground()
        engine.refreshRegistrations()
    }

    private fun suspendRegistrations() {
        // Already suspended: only the accounts a push woke are still registered.
        val toStop = if (registrationsSuspended) awakeAccounts.toSet() else registeredConfigs.keys.toSet()
        registrationsSuspended = true
        awakeAccounts.clear()

        for (id in toStop) {
            engine.setRegistrationEnabled(false, SipAccountId(id))
        }
    }

    private fun resumeRegistrations() {
        if (!registrationsSuspended) {
            return
        }

        registrationsSuspended = false
        awakeAccounts.clear()

        for (id in registeredConfigs.keys) {
            engine.setRegistrationEnabled(true, SipAccountId(id))
        }
    }

    /** Register this account now, also when the others stay suspended (a push for it arrived). */
    private fun wakeRegistration(accountId: String) {
        if (registrationsSuspended) {
            awakeAccounts += accountId
            engine.setRegistrationEnabled(true, SipAccountId(accountId))
        }

        engine.refreshRegistration(SipAccountId(accountId))
    }

    fun refreshRegistrations() = engine.refreshRegistrations()

    // MARK: Accounts

    /** Make the engine's accounts match the paired accounts: register new or changed ones, drop removed ones. */
    fun sync(list: List<StoredAccount>) {
        start()

        val wanted = list.associateBy { it.id }
        accounts = wanted

        for (id in registeredConfigs.keys.toList()) {
            if (id !in wanted) {
                engine.unregister(SipAccountId(id))
                registeredConfigs.remove(id)
                _registrations.value = _registrations.value - id
            }
        }

        if (!_engineStarted.value) {
            return
        }

        for (account in list) {
            val config = SipAccountMapping.config(account)

            if (registeredConfigs[account.id] == config) {
                continue
            }

            if (registrationsSuspended && account.id !in awakeAccounts) {
                // Added in the background (e.g. the app was started by a push): quiet until a push wakes it.
                engine.setRegistrationEnabled(false, config.id)
            }

            try {
                engine.register(config)
                registeredConfigs[account.id] = config
                _registrations.value = _registrations.value + (account.id to RegistrationState.Registering)
            } catch (error: Exception) {
                logger.error("Account ${account.id} could not be registered: ${error.message}")
                _registrations.value = _registrations.value + (account.id to RegistrationState.Failed(RegistrationFailure.Other(error.message ?: "error")))
            }
        }
    }

    fun registrationState(accountId: String): RegistrationState = _registrations.value[accountId] ?: RegistrationState.Unregistered

    // MARK: Calls (asked by the UI)

    /** Start an outgoing call through the system call framework. */
    fun startCall(rawNumber: String, accountId: String) {
        val number = DialNumber.sanitize(rawNumber)

        if (!DialNumber.isDialable(number)) throw PhoneException.InvalidNumber()
        val account = accounts[accountId] ?: throw PhoneException.UnknownAccount()
        if (registrationState(accountId) != RegistrationState.Registered) throw PhoneException.LineNotConnected()
        if (_sessions.value.isNotEmpty()) throw PhoneException.CallInProgress()

        val uuid = UUID.randomUUID()
        val name = lookupName(number)
        append(
            CallSession(
                id = uuid,
                engineCallId = null,
                direction = CallDirection.OUTGOING,
                accountId = SipAccountId(accountId),
                accountLabel = account.displayLabel,
                remoteNumber = number,
                remoteName = name,
                phase = CallSession.Phase.Starting,
                createdAt = now(),
            ),
        )

        system.requestStartCall(uuid, number, name) { error ->
            if (error != null) {
                logger.notice("The system refused the outgoing call")
                val session = session(uuid)

                if (session != null && !session.phase.isEnded) {
                    finish(uuid, CallEndReason.Failed("refused"), tellSystem = false)
                }
            }
        }
    }

    fun answer(uuid: UUID) = system.requestAnswerCall(uuid) {}

    fun hangUp(uuid: UUID) = system.requestEndCall(uuid) { error ->
        // If the system no longer knows the call, end it on our side anyway.
        if (error != null && session(uuid) != null) {
            performEndCall(uuid)
        }
    }

    fun setMuted(uuid: UUID, muted: Boolean) = system.requestSetMuted(uuid, muted) {}

    fun setHeld(uuid: UUID, held: Boolean) = system.requestSetHeld(uuid, held) {}

    fun sendDtmf(uuid: UUID, digits: String) = system.requestPlayDtmf(uuid, digits) {}

    fun setSpeaker(on: Boolean) {
        try {
            audioRouting.setSpeaker(on)
            _isSpeakerOn.value = on
        } catch (error: Exception) {
            logger.error("Speaker could not be switched: ${error.message}")
            _isSpeakerOn.value = audioRouting.isSpeakerActive
        }
    }

    // MARK: Push (`ring` from FCM)

    /** Handle a `ring` (or unreadable) push: report the call at once, wake the account, wait for the INVITE. */
    fun handleRingPush(message: PushMessage?): IncomingPushOutcome {
        start()
        engine.audio.configure()

        val decision = pushPolicy.decide(
            message,
            now = now(),
            knownAccounts = accounts.keys,
            busy = _sessions.value.isNotEmpty(),
            isHandled = { ref -> _sessions.value.any { it.fssCallRef == ref } || closedCallRefs.containsKey(ref) },
        )

        return when (decision) {
            is RingPushDecision.Ring -> ringFromPush(decision.ring, decision.inviteDeadline)
            is RingPushDecision.Reject -> {
                rejectPush(decision.rejection, decision.ring)
                IncomingPushOutcome.Rejected(decision.rejection)
            }
        }
    }

    private fun ringFromPush(ring: RingPush, inviteDeadline: Instant): IncomingPushOutcome {
        val account = accounts[ring.accountId]
        val uuid = IncomingPushPolicy.callUuid(ring.callRef)

        if (account == null || uuid == null) {
            rejectPush(RingPushRejection.INVALID_PAYLOAD, ring)
            return IncomingPushOutcome.Rejected(RingPushRejection.INVALID_PAYLOAD)
        }

        val number = nonEmpty(ring.from.number)
        val name = number?.let(lookupName) ?: nonEmpty(ring.from.name)
        append(
            CallSession(
                id = uuid,
                engineCallId = null,
                direction = CallDirection.INCOMING,
                accountId = SipAccountId(account.id),
                accountLabel = account.displayLabel,
                remoteNumber = number,
                remoteName = name,
                phase = CallSession.Phase.Incoming,
                createdAt = now(),
                fssCallRef = ring.callRef,
                awaitingInvite = true,
            ),
        )

        logger.notice("Push for call ${ring.callRef} on account ${account.id}: ringing")

        system.reportIncomingCall(uuid, display(name, number, account)) { error ->
            if (error != null) {
                // The system refused: the INVITE gets rejected.
                logger.notice("The system refused the call from push ${ring.callRef}")
                markEndedByUser(uuid)
                finish(uuid, CallEndReason.Declined, tellSystem = false)
            }
        }

        // Refused synchronously: nothing to wait for.
        if (session(uuid) == null) {
            return IncomingPushOutcome.Rejected(RingPushRejection.REFUSED_BY_SYSTEM)
        }

        // The PBX waits for a FRESH registration of this account before it sends the INVITE.
        wakeRegistration(account.id)

        val delay = Duration.between(now(), inviteDeadline).toMillis().coerceAtLeast(0)
        inviteTimers[uuid] = scheduler.schedule(delay) { inviteDidNotArrive(uuid) }

        return IncomingPushOutcome.Ringing(uuid)
    }

    private fun inviteDidNotArrive(uuid: UUID) {
        inviteTimers.remove(uuid)
        val session = session(uuid) ?: return

        if (!session.awaitingInvite) {
            return
        }

        logger.notice("No INVITE for call ${session.fssCallRef ?: "?"} in time: ended as missed")
        // Answered before the call came, but it never came: that failed. Otherwise: the caller hung up (missed).
        finish(uuid, if (session.answerPending) CallEndReason.Failed("no INVITE") else CallEndReason.Unanswered, tellSystem = true)
    }

    private fun rejectPush(rejection: RingPushRejection, ring: RingPush?) {
        // Android has no "report every push" rule (unlike PushKit): an unusable push is only logged and remembered.
        logger.notice("Ring push not rung: $rejection")

        if (ring == null) {
            return
        }

        val account = accounts[ring.accountId]
        val number = nonEmpty(ring.from.number)
        val name = number?.let(lookupName) ?: nonEmpty(ring.from.name)

        when (rejection) {
            RingPushRejection.BUSY, RingPushRejection.EXPIRED -> {
                // A real call that could not ring here: it belongs in the recents as missed.
                if (account != null) {
                    onCallFinished?.invoke(
                        RecentCall(number = number ?: "", name = name, accountId = account.id, accountLabel = account.displayLabel, direction = RecentCall.Direction.INCOMING, outcome = RecentCall.Outcome.MISSED, startedAtMillis = now().toEpochMilli(), durationSeconds = 0),
                    )
                }

                // Its INVITE may still come: reject it, do not ring.
                rememberClosed(ring.callRef, DeclineReason.BUSY)
            }
            RingPushRejection.UNKNOWN_ACCOUNT -> rememberClosed(ring.callRef, DeclineReason.BUSY)
            RingPushRejection.INVALID_PAYLOAD, RingPushRejection.ALREADY_HANDLED, RingPushRejection.REFUSED_BY_SYSTEM -> Unit
        }
    }

    /** Join an INVITE to the call its push reported. `true` when it was joined. */
    private fun attachInvite(call: IncomingCall): Boolean {
        val waiting = _sessions.value.firstOrNull { session ->
            if (!session.awaitingInvite || session.engineCallId != null) {
                false
            } else if (call.fssCallRef != null) {
                session.fssCallRef == call.fssCallRef
            } else {
                // No X-FSS-Call header (an older PBX module, a forwarded call): same account and caller (D16 fallback).
                session.accountId == call.accountId && IncomingPushPolicy.callersMatch(session.remoteNumber, call.from)
            }
        } ?: return false

        val uuid = waiting.id
        inviteTimers.remove(uuid)?.invoke()
        update(uuid) { it.copy(engineCallId = call.id, awaitingInvite = false, remoteNumber = it.remoteNumber ?: call.from) }

        logger.notice("INVITE joined to call ${waiting.fssCallRef ?: "?"}")

        // The push had no caller name, the INVITE has one: update the call screen.
        val account = accounts[waiting.accountId.raw]
        val name = call.from?.let(lookupName) ?: call.displayName

        if (waiting.remoteName == null && name != null && account != null) {
            update(uuid) { it.copy(remoteName = name) }
            system.reportCallUpdated(uuid, display(name, call.from, account))
        }

        if (waiting.answerPending) {
            engine.audio.configure()

            try {
                engine.answer(call.id)
            } catch (error: Exception) {
                logger.error("Answer of the joined INVITE failed: ${error.message}")
                finish(uuid, CallEndReason.Failed("answer"), tellSystem = true)
            }
        }

        return true
    }

    private fun rememberClosed(ref: String, reject: DeclineReason) {
        val current = now()
        closedCallRefs.entries.removeAll { Duration.between(it.value.first, current).seconds >= 120 }
        closedCallRefs[ref] = current to reject
    }

    /** What the system and our screen show for an incoming call (plan D10). */
    private fun display(name: String?, number: String?, account: StoredAccount): IncomingCallDisplay {
        val showAccount = CallDisplay.shouldShowAccount(preferences.preferences(account.id).showCalledAccount, accounts.size)

        return IncomingCallDisplay(
            handle = number ?: anonymousCallerText,
            displayName = CallDisplay.callerText(name, number, account.displayLabel, showAccount, anonymousCallerText),
            callerTitle = CallDisplay.callerTitle(name, number, anonymousCallerText),
            accountLabel = account.displayLabel,
        )
    }

    // MARK: Engine events

    private fun engineRegistrationChanged(state: RegistrationState, account: SipAccountId) {
        if (accounts.containsKey(account.raw)) {
            _registrations.value = _registrations.value + (account.raw to state)
        }
    }

    private fun engineIncomingCall(call: IncomingCall) {
        val account = accounts[call.accountId.raw]

        if (account == null) {
            runCatching { engine.decline(call.id) }
            return
        }

        // The call a push announced: join the INVITE to the call that is already on the screen.
        if (attachInvite(call)) {
            return
        }

        // The push for this call was handled and the call already ended here: reject it.
        val closed = call.fssCallRef?.let { closedCallRefs[it] }
        if (closed != null) {
            logger.notice("INVITE for an ended call ${call.fssCallRef}: rejected")
            runCatching { engine.decline(call.id, closed.second) }
            return
        }

        // One call at a time in this version: a second caller hears busy.
        if (_sessions.value.isNotEmpty()) {
            runCatching { engine.decline(call.id, DeclineReason.BUSY) }
            onCallFinished?.invoke(
                RecentCall(number = call.from ?: "", name = call.displayName, accountId = account.id, accountLabel = account.displayLabel, direction = RecentCall.Direction.INCOMING, outcome = RecentCall.Outcome.MISSED, startedAtMillis = now().toEpochMilli(), durationSeconds = 0),
            )
            return
        }

        // Keyed on the callRef, so a push that arrives after its INVITE maps onto this same call.
        val uuid = IncomingPushPolicy.callUuid(call.fssCallRef) ?: UUID.randomUUID()
        val name = call.from?.let(lookupName) ?: call.displayName
        append(
            CallSession(
                id = uuid,
                engineCallId = call.id,
                direction = CallDirection.INCOMING,
                accountId = call.accountId,
                accountLabel = account.displayLabel,
                remoteNumber = call.from,
                remoteName = name,
                phase = CallSession.Phase.Incoming,
                createdAt = now(),
                fssCallRef = call.fssCallRef,
            ),
        )

        system.reportIncomingCall(uuid, display(name, call.from, account)) { error ->
            if (error != null) {
                // The system refused (another app's call, policy): decline the SIP call.
                logger.notice("The system refused the incoming call")
                markEndedByUser(uuid)
                runCatching { engine.decline(call.id) }
                finish(uuid, CallEndReason.Declined, tellSystem = false)
            }
        }
    }

    private fun engineCallChanged(info: CallInfo) {
        val uuid = _sessions.value.firstOrNull { it.engineCallId == info.id }?.id ?: return
        val state = info.state

        if (state is CallState.Ended) {
            finish(uuid, state.reason, tellSystem = true)
            return
        }

        update(uuid) { session ->
            val phase = CallSession.phaseFor(state)
            session.copy(
                phase = phase,
                remoteName = session.remoteName ?: info.remoteName,
                connectedAt = session.connectedAt ?: if (phase.isConnected) now() else null,
            )
        }

        val session = session(uuid) ?: return

        if (session.direction == CallDirection.OUTGOING && session.phase.isConnected && !session.reportedConnected) {
            update(uuid) { it.copy(reportedConnected = true) }
            system.reportOutgoingCallConnected(uuid)
        }
    }

    // MARK: Helpers

    fun session(uuid: UUID): CallSession? = _sessions.value.firstOrNull { it.id == uuid }

    private fun append(session: CallSession) {
        _sessions.value = _sessions.value + session
    }

    private fun update(uuid: UUID, change: (CallSession) -> CallSession) {
        _sessions.value = _sessions.value.map { if (it.id == uuid) change(it) else it }
    }

    private fun markEndedByUser(uuid: UUID) = update(uuid) { it.copy(endedByUser = true) }

    private fun finish(uuid: UUID, reason: CallEndReason, tellSystem: Boolean) {
        val session = session(uuid) ?: return
        val ended = session.copy(phase = CallSession.Phase.Ended(reason))
        _sessions.value = _sessions.value.filter { it.id != uuid }
        inviteTimers.remove(uuid)?.invoke()

        // A late INVITE (or a repeated push) for this call must not ring again.
        session.fssCallRef?.let { rememberClosed(it, if (reason == CallEndReason.Declined) DeclineReason.DECLINED else DeclineReason.BUSY) }

        if (tellSystem && !session.endedByUser) {
            system.reportCallEnded(uuid, systemReason(reason))
        }

        if (_sessions.value.isEmpty()) {
            // A new call starts unmuted, on the earpiece.
            engine.setMuted(false)

            if (_isSpeakerOn.value) {
                setSpeaker(false)
            }

            // The call kept the app registered in the background; now the push gate takes over again.
            if (isInBackground) {
                suspendRegistrations()
            }
        }

        onCallFinished?.invoke(recent(ended, reason, now()))

        _lastEnded.value = ended

        if (endedLingerMillis > 0) {
            scheduler.schedule(endedLingerMillis) {
                if (_lastEnded.value?.id == uuid) {
                    _lastEnded.value = null
                }
            }
        } else {
            _lastEnded.value = null
        }
    }

    // MARK: System actions

    override fun performStartCall(uuid: UUID, handle: String): Boolean {
        val session = session(uuid) ?: return false
        val number = session.remoteNumber

        val engineId = try {
            engine.audio.configure()
            number?.let { engine.call(it, session.accountId) }
        } catch (error: Exception) {
            logger.error("Outgoing call failed to start: ${error.message}")
            null
        }

        if (engineId == null) {
            finish(uuid, CallEndReason.Failed("start"), tellSystem = false)
            return false
        }

        update(uuid) { it.copy(engineCallId = engineId, phase = CallSession.Phase.Ringing) }
        system.reportOutgoingCallStartedConnecting(uuid)

        return true
    }

    override fun performAnswerCall(uuid: UUID): Boolean {
        val session = session(uuid) ?: return false
        val engineId = session.engineCallId

        if (engineId == null) {
            // Answered before the INVITE arrived (the usual case after a push): answer it as soon as it comes.
            if (!session.awaitingInvite) {
                return false
            }

            engine.audio.configure()
            update(uuid) { it.copy(answerPending = true, phase = CallSession.Phase.Connecting) }
            return true
        }

        engine.audio.configure()

        return try {
            engine.answer(engineId)
            update(uuid) { it.copy(phase = CallSession.Phase.Connecting) }
            true
        } catch (error: Exception) {
            logger.error("Answer failed: ${error.message}")
            false
        }
    }

    override fun performEndCall(uuid: UUID): Boolean {
        // Already gone on our side: let the system drop it too.
        val session = session(uuid) ?: return true
        markEndedByUser(uuid)

        val engineId = session.engineCallId

        if (engineId == null) {
            // Declined (or hung up after answering) before the INVITE arrived: the late INVITE gets a 603.
            val declined = session.direction == CallDirection.INCOMING && !session.answerPending
            finish(uuid, if (declined) CallEndReason.Declined else CallEndReason.LocalHangup, tellSystem = false)
            return true
        }

        try {
            if (session.direction == CallDirection.INCOMING && session.phase == CallSession.Phase.Incoming) {
                engine.decline(engineId)
            } else {
                engine.hangup(engineId)
            }
        } catch (_: Exception) {
            // The engine no longer knows the call: end it here.
            finish(uuid, if (session.phase == CallSession.Phase.Incoming) CallEndReason.Declined else CallEndReason.LocalHangup, tellSystem = false)
        }

        return true
    }

    override fun performSetHeld(uuid: UUID, onHold: Boolean): Boolean {
        val engineId = session(uuid)?.engineCallId ?: return false

        return try {
            engine.setHold(engineId, onHold)
            update(uuid) { it.copy(isOnHold = onHold) }
            true
        } catch (error: Exception) {
            logger.error("Hold failed: ${error.message}")
            false
        }
    }

    override fun performSetMuted(uuid: UUID, muted: Boolean): Boolean {
        if (session(uuid) == null) {
            return false
        }

        engine.setMuted(muted)
        update(uuid) { it.copy(isMuted = muted) }

        return true
    }

    override fun performPlayDtmf(uuid: UUID, digits: String): Boolean {
        val engineId = session(uuid)?.engineCallId ?: return false

        for (character in digits) {
            val digit = DtmfDigit.of(character) ?: continue
            runCatching { engine.sendDtmf(digit, engineId) }
        }

        return true
    }

    override fun audioActivated(active: Boolean) = engine.audio.activate(active)

    override fun systemDidReset() {
        for (session in _sessions.value) {
            session.engineCallId?.let { runCatching { engine.hangup(it) } }
            markEndedByUser(session.id)
            finish(session.id, CallEndReason.LocalHangup, tellSystem = false)
        }
    }

    companion object {
        fun systemReason(reason: CallEndReason): CallSystemEndReason = when (reason) {
            CallEndReason.LocalHangup, CallEndReason.RemoteHangup -> CallSystemEndReason.REMOTE_ENDED
            CallEndReason.Declined -> CallSystemEndReason.DECLINED_ELSEWHERE
            CallEndReason.Unanswered -> CallSystemEndReason.UNANSWERED
            CallEndReason.Busy, is CallEndReason.Failed -> CallSystemEndReason.FAILED
        }

        fun recent(session: CallSession, reason: CallEndReason, endedAt: Instant): RecentCall {
            val outcome = when {
                session.connectedAt != null -> RecentCall.Outcome.ANSWERED
                session.direction == CallDirection.INCOMING -> if (reason == CallEndReason.Declined) RecentCall.Outcome.DECLINED else RecentCall.Outcome.MISSED
                reason is CallEndReason.Failed -> RecentCall.Outcome.FAILED
                else -> RecentCall.Outcome.NOT_ANSWERED
            }

            return RecentCall(
                id = session.id.toString(),
                number = session.remoteNumber ?: "",
                name = session.remoteName,
                accountId = session.accountId.raw,
                accountLabel = session.accountLabel,
                direction = if (session.direction == CallDirection.INCOMING) RecentCall.Direction.INCOMING else RecentCall.Direction.OUTGOING,
                outcome = outcome,
                startedAtMillis = session.createdAt.toEpochMilli(),
                durationSeconds = session.connectedAt?.let { Duration.between(it, endedAt).seconds.coerceAtLeast(0) } ?: 0,
            )
        }

        private fun nonEmpty(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }
    }
}
