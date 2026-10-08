// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.callcontroller

import java.io.File
import java.time.Instant
import nl.fullstackstudio.fsvoip.core.FsJson
import nl.fullstackstudio.fsvoip.core.FsLogger
import nl.fullstackstudio.fsvoip.core.InMemoryKeyValueStore
import nl.fullstackstudio.fsvoip.core.KeyValuePreferencesStore
import nl.fullstackstudio.fsvoip.core.MemoryLogSink
import nl.fullstackstudio.fsvoip.core.PairResponse
import nl.fullstackstudio.fsvoip.core.RecentCall
import nl.fullstackstudio.fsvoip.core.StoredAccount
import nl.fullstackstudio.fsvoip.sipengine.CallDirection
import nl.fullstackstudio.fsvoip.sipengine.CallId
import nl.fullstackstudio.fsvoip.sipengine.CallInfo
import nl.fullstackstudio.fsvoip.sipengine.CallState
import nl.fullstackstudio.fsvoip.sipengine.DeclineReason
import nl.fullstackstudio.fsvoip.sipengine.DtmfDigit
import nl.fullstackstudio.fsvoip.sipengine.IncomingCall
import nl.fullstackstudio.fsvoip.sipengine.RegistrationState
import nl.fullstackstudio.fsvoip.sipengine.SipAccountConfig
import nl.fullstackstudio.fsvoip.sipengine.SipAccountId
import nl.fullstackstudio.fsvoip.sipengine.SipAudioControl
import nl.fullstackstudio.fsvoip.sipengine.SipEngine
import nl.fullstackstudio.fsvoip.sipengine.SipEngineException
import nl.fullstackstudio.fsvoip.sipengine.SipEngineListener

fun fixture(name: String): String = File(System.getProperty("fsvoip.fixtures") ?: "../../shared/fixtures", name).readText()

/** Records what the phone asks; the test drives the engine's events by hand. */
class FakeSipEngine : SipEngine {
    override var listener: SipEngineListener? = null

    val audioLog = mutableListOf<String>()
    override val audio: SipAudioControl = object : SipAudioControl {
        override fun configure() {
            audioLog += "configure"
        }

        override fun activate(active: Boolean) {
            audioLog += if (active) "activate" else "deactivate"
        }
    }

    var started = false
    val registered = mutableMapOf<SipAccountId, SipAccountConfig>()
    var registerCount = 0
    val log = mutableListOf<String>()
    var micMuted = false
    var failNextCall = false
    val disabled = mutableSetOf<SipAccountId>()

    override fun start() {
        started = true
    }

    override fun stop() = Unit
    override fun enterBackground() {
        log += "background"
    }

    override fun enterForeground() {
        log += "foreground"
    }

    override fun refreshRegistrations() {
        log += "refresh"
    }

    override fun register(account: SipAccountConfig) {
        registerCount++
        registered[account.id] = account
    }

    override fun unregister(account: SipAccountId) {
        registered.remove(account)
        log += "unregister $account"
    }

    override fun registrationState(account: SipAccountId): RegistrationState = RegistrationState.Unregistered

    override fun setRegistrationEnabled(enabled: Boolean, account: SipAccountId) {
        if (enabled) disabled.remove(account) else disabled.add(account)
        log += "${if (enabled) "enable" else "disable"} $account"
    }

    override fun refreshRegistration(account: SipAccountId) {
        log += "refresh $account"
    }

    override fun call(number: String, account: SipAccountId): CallId {
        if (failNextCall) {
            failNextCall = false
            throw SipEngineException.Engine("boom")
        }

        log += "call $number from $account"
        return CallId("out-1")
    }

    override fun answer(call: CallId) {
        log += "answer $call"
    }

    override fun decline(call: CallId, reason: DeclineReason) {
        log += if (reason == DeclineReason.BUSY) "busy $call" else "decline $call"
    }

    override fun hangup(call: CallId) {
        log += "hangup $call"
    }

    override fun setHold(call: CallId, onHold: Boolean) {
        log += "hold $onHold"
    }

    override fun setMuted(muted: Boolean) {
        micMuted = muted
    }

    override fun sendDtmf(digit: DtmfDigit, call: CallId) {
        log += "dtmf ${digit.character}"
    }

    override fun transfer(call: CallId, number: String) = Unit
    override fun calls(): List<CallInfo> = emptyList()

    fun emitRegistration(state: RegistrationState, account: String) =
        listener!!.onRegistrationChanged(this, state, SipAccountId(account))

    fun emitIncoming(id: String, from: String?, name: String?, account: String, callRef: String? = null) =
        listener!!.onIncomingCall(this, IncomingCall(CallId(id), from, name, SipAccountId(account), callRef))

    fun emitState(state: CallState, id: String, direction: CallDirection, account: String) =
        listener!!.onCallChanged(this, CallInfo(CallId(id), direction, SipAccountId(account), null, null, state))
}

/** Time the test controls: `advance` fires the timers that are due. */
class TestClock(var now: Instant = Instant.parse("2026-10-08T12:34:56Z")) : CallScheduler {
    private data class Timer(val at: Instant, val action: () -> Unit, var cancelled: Boolean = false)

    private val timers = mutableListOf<Timer>()

    override fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit {
        val timer = Timer(now.plusMillis(delayMillis), action)
        timers += timer
        return { timer.cancelled = true }
    }

    fun advance(millis: Long) {
        now = now.plusMillis(millis)
        val due = timers.filter { !it.cancelled && !it.at.isAfter(now) }
        timers.removeAll(due)
        due.forEach { it.action() }
    }
}

fun storedAccount(id: String, pairedAt: Long = 0, label: String? = null): StoredAccount {
    val response = FsJson.default.decodeFromString(PairResponse.serializer(), fixture("pair-response.json"))
    val account = StoredAccount.fromPairing(response, Instant.ofEpochMilli(pairedAt)).copy(id = id)
    return if (label != null) account.copy(label = label) else account
}

class Harness(accountIds: List<String> = listOf(ACCOUNT)) {
    val engine = FakeSipEngine()
    val system = ImmediateCallSystem()
    val clock = TestClock()
    val preferences = KeyValuePreferencesStore(InMemoryKeyValueStore())
    val recents = mutableListOf<RecentCall>()
    val logs = MemoryLogSink()
    val phone = PhoneController(
        engine = engine,
        system = system,
        audioRouting = MemoryAudioRouting(),
        preferences = preferences,
        logger = FsLogger("phone", logs),
        endedLingerMillis = 0,
        now = { clock.now },
        scheduler = clock,
        mainExecutor = { it() },
    ).also { phone ->
        phone.onCallFinished = { recents += it }
        phone.anonymousCallerText = "Onbekend nummer"
        phone.sync(accountIds.mapIndexed { index, id -> storedAccount(id, index.toLong(), label = "Line $id") })
    }

    fun registerAll(vararg ids: String = arrayOf(ACCOUNT)) = ids.forEach { engine.emitRegistration(RegistrationState.Registered, it) }

    companion object {
        /** The account id of the fixtures. */
        const val ACCOUNT = "3f0c2b1e-8a4d-4d6f-9b7a-1c2d3e4f5a6b"
        const val CALL_REF = "9d3f5c52-7b1e-4a0c-8e6d-2f4a6b8c0d1e"
    }
}
