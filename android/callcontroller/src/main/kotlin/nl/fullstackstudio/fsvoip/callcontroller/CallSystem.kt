// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.callcontroller

import java.util.UUID

/** Why a call ended, as the system call framework is told. */
enum class CallSystemEndReason { FAILED, REMOTE_ENDED, UNANSWERED, ANSWERED_ELSEWHERE, DECLINED_ELSEWHERE }

/**
 * What the user (or the system: a Bluetooth headset, a watch, a car) did with a call. Every action from our own
 * screens goes through the system too, so Telecom and the app never disagree about a call. The handler returns
 * `false` when the action cannot be performed.
 */
interface CallSystemActionHandler {
    fun performStartCall(uuid: UUID, handle: String): Boolean
    fun performAnswerCall(uuid: UUID): Boolean
    fun performEndCall(uuid: UUID): Boolean
    fun performSetHeld(uuid: UUID, onHold: Boolean): Boolean
    fun performSetMuted(uuid: UUID, muted: Boolean): Boolean
    fun performPlayDtmf(uuid: UUID, digits: String): Boolean

    /** The system made call audio active (`true`) or the last call is gone (`false`). */
    fun audioActivated(active: Boolean)

    /** The system dropped all calls. */
    fun systemDidReset()
}

class CallSystemActionFailed(message: String = "The system refused the action") : Exception(message)

/** What the system shows for an incoming call. On Android caller and account are shown apart (plan D10). */
data class IncomingCallDisplay(
    /** The number (or the anonymous text) as Telecom's address. */
    val handle: String,
    /** Combined text for Telecom and notifications (`<caller>` or `<caller> → <account>`). */
    val displayName: String,
    val callerTitle: String,
    val accountLabel: String,
)

/**
 * The system call framework as [PhoneController] sees it: Telecom with a self-managed ConnectionService on a phone
 * (`TelecomCallSystem`), or a loop-back for tests (`ImmediateCallSystem`). Everything happens on the main thread.
 */
interface CallSystem {
    var handler: CallSystemActionHandler?

    fun reportIncomingCall(uuid: UUID, display: IncomingCallDisplay, completion: (Throwable?) -> Unit)
    fun reportCallUpdated(uuid: UUID, display: IncomingCallDisplay)

    /** The call ended without the user ending it on the call UI (remote hang-up, failure). */
    fun reportCallEnded(uuid: UUID, reason: CallSystemEndReason)
    fun reportOutgoingCallStartedConnecting(uuid: UUID)
    fun reportOutgoingCallConnected(uuid: UUID)

    /** Ask the system to start/answer/end/hold/mute/play DTMF; it then calls the handler's `perform…`. */
    fun requestStartCall(uuid: UUID, handle: String, displayName: String?, completion: (Throwable?) -> Unit)
    fun requestAnswerCall(uuid: UUID, completion: (Throwable?) -> Unit)
    fun requestEndCall(uuid: UUID, completion: (Throwable?) -> Unit)
    fun requestSetHeld(uuid: UUID, onHold: Boolean, completion: (Throwable?) -> Unit)
    fun requestSetMuted(uuid: UUID, muted: Boolean, completion: (Throwable?) -> Unit)
    fun requestPlayDtmf(uuid: UUID, digits: String, completion: (Throwable?) -> Unit)
}

/**
 * Performs every request straight away on the handler and activates the audio like Telecom would. Used by the tests,
 * and by `TelecomCallSystem` on a device where Telecom is not available.
 */
class ImmediateCallSystem : CallSystem {
    override var handler: CallSystemActionHandler? = null

    sealed interface Event {
        data class ReportedIncoming(val uuid: UUID, val display: IncomingCallDisplay) : Event
        data class Updated(val uuid: UUID, val display: IncomingCallDisplay) : Event
        data class Ended(val uuid: UUID, val reason: CallSystemEndReason) : Event
        data class StartedConnecting(val uuid: UUID) : Event
        data class Connected(val uuid: UUID) : Event
    }

    val events = mutableListOf<Event>()

    /** Make `reportIncomingCall` fail (the system refuses). */
    var refuseIncoming = false
    private var audioActive = false

    override fun reportIncomingCall(uuid: UUID, display: IncomingCallDisplay, completion: (Throwable?) -> Unit) {
        events += Event.ReportedIncoming(uuid, display)
        completion(if (refuseIncoming) CallSystemActionFailed() else null)
    }

    override fun reportCallUpdated(uuid: UUID, display: IncomingCallDisplay) {
        events += Event.Updated(uuid, display)
    }

    override fun reportCallEnded(uuid: UUID, reason: CallSystemEndReason) {
        events += Event.Ended(uuid, reason)
        deactivateAudio()
    }

    override fun reportOutgoingCallStartedConnecting(uuid: UUID) {
        events += Event.StartedConnecting(uuid)
    }

    override fun reportOutgoingCallConnected(uuid: UUID) {
        events += Event.Connected(uuid)
    }

    override fun requestStartCall(uuid: UUID, handle: String, displayName: String?, completion: (Throwable?) -> Unit) {
        val ok = handler?.performStartCall(uuid, handle) ?: false
        completion(if (ok) null else CallSystemActionFailed())
        if (ok) activateAudio()
    }

    override fun requestAnswerCall(uuid: UUID, completion: (Throwable?) -> Unit) {
        val ok = handler?.performAnswerCall(uuid) ?: false
        completion(if (ok) null else CallSystemActionFailed())
        if (ok) activateAudio()
    }

    override fun requestEndCall(uuid: UUID, completion: (Throwable?) -> Unit) {
        val ok = handler?.performEndCall(uuid) ?: false
        completion(if (ok) null else CallSystemActionFailed())
        deactivateAudio()
    }

    override fun requestSetHeld(uuid: UUID, onHold: Boolean, completion: (Throwable?) -> Unit) {
        completion(if (handler?.performSetHeld(uuid, onHold) == true) null else CallSystemActionFailed())
    }

    override fun requestSetMuted(uuid: UUID, muted: Boolean, completion: (Throwable?) -> Unit) {
        completion(if (handler?.performSetMuted(uuid, muted) == true) null else CallSystemActionFailed())
    }

    override fun requestPlayDtmf(uuid: UUID, digits: String, completion: (Throwable?) -> Unit) {
        completion(if (handler?.performPlayDtmf(uuid, digits) == true) null else CallSystemActionFailed())
    }

    private fun activateAudio() {
        if (!audioActive) {
            audioActive = true
            handler?.audioActivated(true)
        }
    }

    private fun deactivateAudio() {
        if (audioActive) {
            audioActive = false
            handler?.audioActivated(false)
        }
    }
}
