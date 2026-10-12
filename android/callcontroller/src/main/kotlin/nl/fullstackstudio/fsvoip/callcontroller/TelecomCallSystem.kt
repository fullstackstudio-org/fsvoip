// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The system call framework on Android (plan D16): Telecom with a SELF-MANAGED PhoneAccount. FSVoip draws its own call
// screen (Compose + a full-screen notification in the app module); Telecom keeps the phone aware of the call (audio
// focus, Bluetooth headset buttons, cars, watches, other VoIP apps, the cellular call).
//
// The SIP stack does not know Telecom exists: everything goes through [CallSystemActionHandler] (PhoneController).
// Where Telecom cannot be used (no Telecom on the device, or registering the PhoneAccount fails) every request is
// carried out directly, like [ImmediateCallSystem]: calls still work, only the system integration is missing.

package nl.fullstackstudio.fsvoip.callcontroller

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.OutcomeReceiver
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import java.util.UUID
import nl.fullstackstudio.fsvoip.core.FsLogger

class TelecomCallSystem(
    context: Context,
    private val label: String = "FSVoip",
    private val logger: FsLogger = FsLogger("telecom"),
) : CallSystem, AudioRouting {
    private val context = context.applicationContext
    private val telecom = context.getSystemService(TelecomManager::class.java)
    private val fallback = ImmediateCallSystem()

    override var handler: CallSystemActionHandler? = null
        set(value) {
            field = value
            fallback.handler = value
        }

    /** Telecom asked us to show the incoming call screen (or to silence the ringer). The app module draws it. */
    var onShowIncomingUi: ((UUID) -> Unit)? = null
    var onSilenceRinger: ((UUID) -> Unit)? = null

    val phoneAccountHandle = PhoneAccountHandle(ComponentName(context, FsConnectionService::class.java), ACCOUNT_ID)

    /** `false` when Telecom could not be set up: calls then run without system integration. */
    var isAvailable: Boolean = false
        private set

    private val connections = mutableMapOf<UUID, FsConnection>()
    private val pendingIncoming = mutableMapOf<UUID, Pair<IncomingCallDisplay, (Throwable?) -> Unit>>()
    private val pendingOutgoing = mutableMapOf<UUID, Pair<String, (Throwable?) -> Unit>>()
    private var audioActive = false
    private var speakerActive = false

    init {
        instance = this
        isAvailable = try {
            val account = PhoneAccount.builder(phoneAccountHandle, label)
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_SIP, PhoneAccount.SCHEME_TEL))
                .build()
            telecom?.registerPhoneAccount(account)
            telecom != null
        } catch (error: Exception) {
            logger.error("Telecom PhoneAccount could not be registered: ${error.javaClass.simpleName}")
            false
        }
    }

    // MARK: Reports

    override fun reportIncomingCall(uuid: UUID, display: IncomingCallDisplay, completion: (Throwable?) -> Unit) {
        if (!isAvailable) return fallback.reportIncomingCall(uuid, display, completion)

        if (connections.containsKey(uuid) || pendingIncoming.containsKey(uuid)) {
            // Already on the screen (push and INVITE for the same call): nothing changes.
            return completion(null)
        }

        pendingIncoming[uuid] = display to completion

        try {
            val extras = Bundle().apply {
                putString(EXTRA_CALL_UUID, uuid.toString())
                putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, address(display.handle))
            }
            telecom!!.addNewIncomingCall(phoneAccountHandle, extras)
        } catch (error: Exception) {
            logger.error("Telecom refused the incoming call: ${error.javaClass.simpleName}")
            pendingIncoming.remove(uuid)
            completion(CallSystemActionFailed(error.javaClass.simpleName))
        }
    }

    override fun reportCallUpdated(uuid: UUID, display: IncomingCallDisplay) {
        if (!isAvailable) return fallback.reportCallUpdated(uuid, display)

        connections[uuid]?.setCallerDisplayName(display.displayName, TelecomManager.PRESENTATION_ALLOWED)
    }

    override fun reportCallEnded(uuid: UUID, reason: CallSystemEndReason) {
        if (!isAvailable) return fallback.reportCallEnded(uuid, reason)

        pendingIncoming.remove(uuid)
        disconnect(uuid, disconnectCause(reason))
    }

    override fun reportOutgoingCallStartedConnecting(uuid: UUID) {
        if (!isAvailable) return fallback.reportOutgoingCallStartedConnecting(uuid)

        connections[uuid]?.setDialing()
    }

    override fun reportOutgoingCallConnected(uuid: UUID) {
        if (!isAvailable) return fallback.reportOutgoingCallConnected(uuid)

        connections[uuid]?.setActive()
    }

    // MARK: Requests

    override fun requestStartCall(uuid: UUID, handle: String, displayName: String?, completion: (Throwable?) -> Unit) {
        if (!isAvailable) return fallback.requestStartCall(uuid, handle, displayName, completion)

        pendingOutgoing[uuid] = handle to completion

        try {
            val callExtras = Bundle().apply { putString(EXTRA_CALL_UUID, uuid.toString()) }
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
                putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, callExtras)
            }
            telecom!!.placeCall(address(handle), extras)
        } catch (error: Exception) {
            logger.error("Telecom refused the outgoing call: ${error.javaClass.simpleName}")
            pendingOutgoing.remove(uuid)
            completion(CallSystemActionFailed(error.javaClass.simpleName))
        }
    }

    override fun requestAnswerCall(uuid: UUID, completion: (Throwable?) -> Unit) {
        if (!isAvailable) return fallback.requestAnswerCall(uuid, completion)

        completion(if (answer(uuid)) null else CallSystemActionFailed())
    }

    override fun requestEndCall(uuid: UUID, completion: (Throwable?) -> Unit) {
        if (!isAvailable) return fallback.requestEndCall(uuid, completion)

        val ok = handler?.performEndCall(uuid) ?: false
        disconnect(uuid, DisconnectCause(DisconnectCause.LOCAL))
        completion(if (ok) null else CallSystemActionFailed())
    }

    override fun requestSetHeld(uuid: UUID, onHold: Boolean, completion: (Throwable?) -> Unit) {
        if (!isAvailable) return fallback.requestSetHeld(uuid, onHold, completion)

        completion(if (hold(uuid, onHold)) null else CallSystemActionFailed())
    }

    override fun requestSetMuted(uuid: UUID, muted: Boolean, completion: (Throwable?) -> Unit) {
        // A self-managed app mutes its own microphone.
        completion(if (handler?.performSetMuted(uuid, muted) == true) null else CallSystemActionFailed())
    }

    override fun requestPlayDtmf(uuid: UUID, digits: String, completion: (Throwable?) -> Unit) {
        completion(if (handler?.performPlayDtmf(uuid, digits) == true) null else CallSystemActionFailed())
    }

    // MARK: Speaker (AudioRouting)

    override val isSpeakerActive: Boolean
        get() = speakerActive

    override fun setSpeaker(on: Boolean) {
        val connection = connections.values.firstOrNull() ?: run {
            speakerActive = on
            return
        }

        connection.routeToSpeaker(on)
        speakerActive = on
    }

    // MARK: Called by the connections (Telecom's side of things)

    internal fun createIncoming(request: ConnectionRequest): Connection {
        val uuid = request.extras?.getString(EXTRA_CALL_UUID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val pending = uuid?.let { pendingIncoming.remove(it) }

        if (uuid == null || pending == null) {
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        }

        val (display, completion) = pending
        val connection = FsConnection(this, uuid).apply {
            setAddress(address(display.handle), TelecomManager.PRESENTATION_ALLOWED)
            setCallerDisplayName(display.displayName, TelecomManager.PRESENTATION_ALLOWED)
            setRinging()
        }
        connections[uuid] = connection
        completion(null)

        return connection
    }

    internal fun incomingFailed(request: ConnectionRequest?) {
        val uuid = request?.extras?.getString(EXTRA_CALL_UUID)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
        pendingIncoming.remove(uuid)?.second?.invoke(CallSystemActionFailed("Telecom refused the incoming call"))
    }

    internal fun createOutgoing(request: ConnectionRequest): Connection {
        val uuid = request.extras?.getString(EXTRA_CALL_UUID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val pending = uuid?.let { pendingOutgoing.remove(it) }

        if (uuid == null || pending == null) {
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        }

        val (handle, completion) = pending
        val connection = FsConnection(this, uuid).apply {
            setAddress(address(handle), TelecomManager.PRESENTATION_ALLOWED)
            setDialing()
        }
        connections[uuid] = connection

        if (handler?.performStartCall(uuid, handle) == true) {
            completion(null)
            activateAudio()
        } else {
            connections.remove(uuid)
            completion(CallSystemActionFailed())
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        }

        return connection
    }

    internal fun outgoingFailed(request: ConnectionRequest?) {
        val uuid = request?.extras?.getString(EXTRA_CALL_UUID)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
        pendingOutgoing.remove(uuid)?.second?.invoke(CallSystemActionFailed("Telecom refused the outgoing call"))
    }

    /** Answer (from our screen, or from a headset/watch through Telecom). */
    internal fun answer(uuid: UUID): Boolean {
        val ok = handler?.performAnswerCall(uuid) ?: false

        if (ok) {
            connections[uuid]?.setActive()
            activateAudio()
        }

        return ok
    }

    /** End/decline from Telecom (a headset button, a car, the system ending it for a cellular call). */
    internal fun endFromSystem(uuid: UUID, cause: Int) {
        handler?.performEndCall(uuid)
        disconnect(uuid, DisconnectCause(cause))
    }

    internal fun hold(uuid: UUID, onHold: Boolean): Boolean {
        val ok = handler?.performSetHeld(uuid, onHold) ?: false

        if (ok) {
            connections[uuid]?.apply { if (onHold) setOnHold() else setActive() }
        }

        return ok
    }

    internal fun playDtmf(uuid: UUID, digit: Char) {
        handler?.performPlayDtmf(uuid, digit.toString())
    }

    internal fun showIncomingUi(uuid: UUID) {
        onShowIncomingUi?.invoke(uuid)
    }

    internal fun silence(uuid: UUID) {
        onSilenceRinger?.invoke(uuid)
    }

    internal fun contextForExecutor(): Context = context

    internal fun speakerChanged(active: Boolean) {
        speakerActive = active
    }

    private fun disconnect(uuid: UUID, cause: DisconnectCause) {
        val connection = connections.remove(uuid) ?: return
        connection.setDisconnected(cause)
        connection.destroy()

        if (connections.isEmpty()) {
            deactivateAudio()
        }
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
            speakerActive = false
            handler?.audioActivated(false)
        }
    }

    private fun address(handle: String): Uri =
        if (DialNumber.isDialable(handle)) Uri.fromParts(PhoneAccount.SCHEME_TEL, handle, null) else Uri.fromParts(PhoneAccount.SCHEME_SIP, "anonymous", null)

    private fun disconnectCause(reason: CallSystemEndReason): DisconnectCause = DisconnectCause(
        when (reason) {
            CallSystemEndReason.FAILED -> DisconnectCause.ERROR
            CallSystemEndReason.REMOTE_ENDED -> DisconnectCause.REMOTE
            CallSystemEndReason.UNANSWERED -> DisconnectCause.MISSED
            CallSystemEndReason.ANSWERED_ELSEWHERE -> DisconnectCause.ANSWERED_ELSEWHERE
            CallSystemEndReason.DECLINED_ELSEWHERE -> DisconnectCause.REJECTED
        },
    )

    companion object {
        const val ACCOUNT_ID = "fsvoip"
        const val EXTRA_CALL_UUID = "nl.fullstackstudio.fsvoip.CALL_UUID"

        /** The one instance of the app (the ConnectionService, which Telecom creates, finds it here). */
        @Volatile
        var instance: TelecomCallSystem? = null
            private set
    }
}

/** One call as Telecom sees it. Every callback is forwarded to [TelecomCallSystem]. */
internal class FsConnection(private val system: TelecomCallSystem, val uuid: UUID) : Connection() {
    private var endpoints: List<CallEndpoint> = emptyList()

    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        connectionCapabilities = CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD or CAPABILITY_MUTE
        audioModeIsVoip = true
    }

    override fun onShowIncomingCallUi() = system.showIncomingUi(uuid)

    override fun onAnswer() {
        system.answer(uuid)
    }

    override fun onAnswer(videoState: Int) {
        system.answer(uuid)
    }

    override fun onReject() = system.endFromSystem(uuid, DisconnectCause.REJECTED)

    override fun onDisconnect() = system.endFromSystem(uuid, DisconnectCause.LOCAL)

    override fun onAbort() = system.endFromSystem(uuid, DisconnectCause.CANCELED)

    override fun onHold() {
        system.hold(uuid, true)
    }

    override fun onUnhold() {
        system.hold(uuid, false)
    }

    override fun onPlayDtmfTone(c: Char) = system.playDtmf(uuid, c)

    override fun onSilence() = system.silence(uuid)

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(state: CallAudioState?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            system.speakerChanged(state?.route == CallAudioState.ROUTE_SPEAKER)
        }
    }

    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        system.speakerChanged(callEndpoint.endpointType == CallEndpoint.TYPE_SPEAKER)
    }

    override fun onAvailableCallEndpointsChanged(availableEndpoints: MutableList<CallEndpoint>) {
        endpoints = availableEndpoints.toList()
    }

    fun routeToSpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val wanted = if (on) {
                endpoints.firstOrNull { it.endpointType == CallEndpoint.TYPE_SPEAKER }
            } else {
                endpoints.firstOrNull { it.endpointType == CallEndpoint.TYPE_BLUETOOTH }
                    ?: endpoints.firstOrNull { it.endpointType == CallEndpoint.TYPE_WIRED_HEADSET }
                    ?: endpoints.firstOrNull { it.endpointType == CallEndpoint.TYPE_EARPIECE }
            } ?: return

            requestCallEndpointChange(
                wanted,
                ContextCompat.getMainExecutor(system.contextForExecutor()),
                object : OutcomeReceiver<Void, CallEndpointException> {
                    override fun onResult(result: Void?) = Unit
                    override fun onError(error: CallEndpointException) = Unit
                },
            )
        } else {
            @Suppress("DEPRECATION")
            setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE)
        }
    }
}

/** Telecom binds to this service for every call; it hands the work to the app's [TelecomCallSystem]. */
class FsConnectionService : ConnectionService() {
    private val system: TelecomCallSystem?
        get() = TelecomCallSystem.instance

    override fun onCreateIncomingConnection(connectionManagerPhoneAccount: PhoneAccountHandle?, request: ConnectionRequest): Connection =
        system?.createIncoming(request) ?: Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))

    override fun onCreateIncomingConnectionFailed(connectionManagerPhoneAccount: PhoneAccountHandle?, request: ConnectionRequest?) {
        system?.incomingFailed(request)
    }

    override fun onCreateOutgoingConnection(connectionManagerPhoneAccount: PhoneAccountHandle?, request: ConnectionRequest): Connection =
        system?.createOutgoing(request) ?: Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))

    override fun onCreateOutgoingConnectionFailed(connectionManagerPhoneAccount: PhoneAccountHandle?, request: ConnectionRequest?) {
        system?.outgoingFailed(request)
    }
}
