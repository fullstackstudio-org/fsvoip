// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.sipengine

/**
 * Hooks for an app that owns the system call UI and the audio itself (Telecom on Android, CallKit on iOS). The engine
 * must not start call audio on its own before the system said so; `callcontroller` calls these at those moments.
 */
interface SipAudioControl {
    /** Prepare the engine's audio for a call (before the call is reported to the system). */
    fun configure()

    /** `true` when the system made the call audio active, `false` when the last call ended. */
    fun activate(active: Boolean)
}

/** Receives everything the engine reports. Callbacks may arrive on any thread. */
interface SipEngineListener {
    fun onRegistrationChanged(engine: SipEngine, state: RegistrationState, account: SipAccountId)

    fun onIncomingCall(engine: SipEngine, call: IncomingCall)

    fun onCallChanged(engine: SipEngine, call: CallInfo)
}

/**
 * The strict boundary between the app and the SIP/media stack (plan D3). Implemented by `linphoneengine`; a baresip
 * engine would implement the same interface (documented fallback). UI, pairing, contacts and callcontroller depend on
 * this interface and on nothing from the stack.
 */
interface SipEngine {
    var listener: SipEngineListener?
    val audio: SipAudioControl

    // Lifecycle
    fun start()
    fun stop()

    /** The app moved to the background / foreground. */
    fun enterBackground()
    fun enterForeground()

    /** Re-register all accounts now (after a push woke the app, or after a network change). */
    fun refreshRegistrations()

    // Accounts
    fun register(account: SipAccountConfig)
    fun unregister(account: SipAccountId)
    fun registrationState(account: SipAccountId): RegistrationState

    /**
     * Keep the account but stop (`false`: un-REGISTER, `Expires: 0`) or resume (`true`) its registration. Used in the
     * background: without a call the app does not stay registered, the PBX push gate wakes it (plan D1/D4). May be
     * called before `register`: the account is then added with the registration in this state.
     */
    fun setRegistrationEnabled(enabled: Boolean, account: SipAccountId)

    /**
     * Send a fresh REGISTER for this account now, on a new connection if the old one may be stale. The PBX push gate
     * waits for exactly this after a push (a new Call-ID or a full `Expires`).
     */
    fun refreshRegistration(account: SipAccountId)

    // Calls
    fun call(number: String, account: SipAccountId): CallId
    fun answer(call: CallId)

    /** Reject an incoming call that was not answered: 603 Decline or 486 Busy Here. */
    fun decline(call: CallId, reason: DeclineReason = DeclineReason.DECLINED)
    fun hangup(call: CallId)
    fun setHold(call: CallId, onHold: Boolean)

    /** Microphone mute (applies to the active call). */
    fun setMuted(muted: Boolean)
    fun sendDtmf(digit: DtmfDigit, call: CallId)
    fun transfer(call: CallId, number: String)
    fun calls(): List<CallInfo>
}

/** An engine that does nothing and reports nothing (previews, tests, a safe default). */
class NullSipEngine : SipEngine {
    override var listener: SipEngineListener? = null

    override val audio: SipAudioControl = object : SipAudioControl {
        override fun configure() = Unit
        override fun activate(active: Boolean) = Unit
    }

    override fun start() = Unit
    override fun stop() = Unit
    override fun enterBackground() = Unit
    override fun enterForeground() = Unit
    override fun refreshRegistrations() = Unit
    override fun register(account: SipAccountConfig) = Unit
    override fun unregister(account: SipAccountId) = Unit
    override fun registrationState(account: SipAccountId): RegistrationState = RegistrationState.Unregistered
    override fun setRegistrationEnabled(enabled: Boolean, account: SipAccountId) = Unit
    override fun refreshRegistration(account: SipAccountId) = Unit
    override fun call(number: String, account: SipAccountId): CallId = throw SipEngineException.NotStarted()
    override fun answer(call: CallId) = throw SipEngineException.UnknownCall(call)
    override fun decline(call: CallId, reason: DeclineReason) = throw SipEngineException.UnknownCall(call)
    override fun hangup(call: CallId) = throw SipEngineException.UnknownCall(call)
    override fun setHold(call: CallId, onHold: Boolean) = throw SipEngineException.UnknownCall(call)
    override fun setMuted(muted: Boolean) = Unit
    override fun sendDtmf(digit: DtmfDigit, call: CallId) = throw SipEngineException.UnknownCall(call)
    override fun transfer(call: CallId, number: String) = throw SipEngineException.UnknownCall(call)
    override fun calls(): List<CallInfo> = emptyList()
}
