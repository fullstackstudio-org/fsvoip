// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.pbx

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.core.ApiException
import nl.fullstackstudio.fsvoip.core.ChainStepRequest
import nl.fullstackstudio.fsvoip.core.FsVoipApiClient
import nl.fullstackstudio.fsvoip.core.NumberChain
import nl.fullstackstudio.fsvoip.core.NumberRecordingPatch
import nl.fullstackstudio.fsvoip.core.PbxDevice
import nl.fullstackstudio.fsvoip.core.PbxDevicePatch
import nl.fullstackstudio.fsvoip.core.PbxDevicesResponse
import nl.fullstackstudio.fsvoip.core.PbxHoliday
import nl.fullstackstudio.fsvoip.core.PbxHolidayInput
import nl.fullstackstudio.fsvoip.core.PbxHours
import nl.fullstackstudio.fsvoip.core.PbxHoursPatch
import nl.fullstackstudio.fsvoip.core.PbxHoursResponse
import nl.fullstackstudio.fsvoip.core.PbxNumber
import nl.fullstackstudio.fsvoip.core.PbxNumbersPage
import nl.fullstackstudio.fsvoip.core.PbxOverview
import nl.fullstackstudio.fsvoip.core.PbxRingGroup
import nl.fullstackstudio.fsvoip.core.PbxRingGroupCreate
import nl.fullstackstudio.fsvoip.core.PbxRingGroupCreated
import nl.fullstackstudio.fsvoip.core.PbxRingGroupPatch
import nl.fullstackstudio.fsvoip.core.PbxRingGroupsResponse
import nl.fullstackstudio.fsvoip.core.PbxRoutingPatch
import nl.fullstackstudio.fsvoip.core.PbxSoundChoice
import nl.fullstackstudio.fsvoip.core.PbxTarget
import nl.fullstackstudio.fsvoip.core.PbxTargetOption
import nl.fullstackstudio.fsvoip.core.SyncState
import nl.fullstackstudio.fsvoip.core.createPbxRingGroup
import nl.fullstackstudio.fsvoip.core.freshChain
import nl.fullstackstudio.fsvoip.core.numberChain
import nl.fullstackstudio.fsvoip.core.pbxDevices
import nl.fullstackstudio.fsvoip.core.pbxHours
import nl.fullstackstudio.fsvoip.core.pbxNumbers
import nl.fullstackstudio.fsvoip.core.pbxOverview
import nl.fullstackstudio.fsvoip.core.pbxRingGroups
import nl.fullstackstudio.fsvoip.core.pbxSoundChoices
import nl.fullstackstudio.fsvoip.core.recordingCost
import nl.fullstackstudio.fsvoip.core.saveChainStep
import nl.fullstackstudio.fsvoip.core.setNumberRecording
import nl.fullstackstudio.fsvoip.core.setPbxNumberRouting
import nl.fullstackstudio.fsvoip.core.updatePbxDevice
import nl.fullstackstudio.fsvoip.core.updatePbxHours
import nl.fullstackstudio.fsvoip.core.updatePbxRingGroup

/** The parts of the "Centrale" section an administrator can open (one page each). */
enum class PbxPart { OVERVIEW, DEVICES, RING_GROUPS, HOURS, NUMBERS }

/** What the "Centrale" screens need from the API, for one paired account. Every method throws `ApiException`. */
interface PbxServicing {
    suspend fun overview(): PbxOverview
    suspend fun devices(): PbxDevicesResponse
    suspend fun updateDevice(id: String, patch: PbxDevicePatch)
    suspend fun ringGroups(): PbxRingGroupsResponse
    suspend fun createRingGroup(group: PbxRingGroupCreate): PbxRingGroupCreated
    suspend fun updateRingGroup(id: String, patch: PbxRingGroupPatch)
    suspend fun hours(): PbxHoursResponse
    suspend fun updateHours(id: String, patch: PbxHoursPatch)
    suspend fun setNumberRouting(numberId: String, patch: PbxRoutingPatch)

    /** `GET /pbx/numbers`: every number with a one-line summary of its chain. */
    suspend fun numbers(): PbxNumbersPage
    suspend fun numberChain(numberId: String): NumberChain

    /** One step: answered with the fresh chain. `ApiException.StaleChain` carries the fresh chain too. */
    suspend fun saveChainStep(numberId: String, step: ChainStepRequest): NumberChain
    suspend fun setNumberRecording(numberId: String, patch: NumberRecordingPatch): NumberChain

    /** The sounds of the PBX (only id and name), for the pickers. */
    suspend fun sounds(): List<PbxSoundChoice>
}

class LivePbxService(private val client: () -> FsVoipApiClient?) : PbxServicing {
    private fun api(): FsVoipApiClient = client() ?: throw ApiException.MissingDeviceToken()

    override suspend fun overview() = api().pbxOverview()
    override suspend fun devices() = api().pbxDevices()
    override suspend fun updateDevice(id: String, patch: PbxDevicePatch) {
        api().updatePbxDevice(id, patch)
    }
    override suspend fun ringGroups() = api().pbxRingGroups()
    override suspend fun createRingGroup(group: PbxRingGroupCreate) = api().createPbxRingGroup(group)
    override suspend fun updateRingGroup(id: String, patch: PbxRingGroupPatch) {
        api().updatePbxRingGroup(id, patch)
    }
    override suspend fun hours() = api().pbxHours()
    override suspend fun updateHours(id: String, patch: PbxHoursPatch) {
        api().updatePbxHours(id, patch)
    }
    override suspend fun setNumberRouting(numberId: String, patch: PbxRoutingPatch) {
        api().setPbxNumberRouting(numberId, patch)
    }
    override suspend fun numbers() = api().pbxNumbers()
    override suspend fun numberChain(numberId: String) = api().numberChain(numberId)
    override suspend fun saveChainStep(numberId: String, step: ChainStepRequest) = api().saveChainStep(numberId, step)
    override suspend fun setNumberRecording(numberId: String, patch: NumberRecordingPatch) = api().setNumberRecording(numberId, patch)
    override suspend fun sounds() = api().pbxSoundChoices()
}

/**
 * The extra check before a change (the iOS Face ID prompt). The biometric gate is another part of the app; until it is plugged in
 * ([PbxHub.gate]) every change is allowed.
 */
fun interface PbxGate {
    suspend fun ensureUnlocked(): Result

    enum class Result { UNLOCKED, UNAVAILABLE, CANCELLED, FAILED }

    companion object {
        val Open = PbxGate { Result.UNLOCKED }
    }
}

/** A message above the screens; the screen resolves the words (it needs the resources), the model keeps the failure it is about. */
data class PbxBanner(val failure: PbxFailure, val isError: Boolean, val id: String = UUID.randomUUID().toString())

/** Everything the screens show of one account's "Centrale" section. */
data class PbxSectionState(
    val overview: PbxOverview? = null,
    val devices: PbxDevicesResponse? = null,
    val ringGroups: PbxRingGroupsResponse? = null,
    val hours: PbxHoursResponse? = null,
    val numbers: PbxNumbersPage? = null,
    /** The chains of the numbers that were opened, by number id. */
    val chains: Map<String, NumberChain> = emptyMap(),
    /** A chain that could not be loaded, by number id. */
    val chainFailures: Map<String, PbxFailure> = emptyMap(),
    /** The sounds of the PBX for the pickers (id and name); empty until loaded. */
    val sounds: List<PbxSoundChoice> = emptyList(),
    /** The centrale does not accept changes now (frozen, being set up, error). Only a fresh answer can lift it. */
    val isReadOnly: Boolean = true,
    /** The last reload failed because there is no connection: what is shown is the last known state. */
    val isOutdated: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val syncTimedOut: Boolean = false,
    val loadFailure: PbxFailure? = null,
    val banner: PbxBanner? = null,
) {
    /** Something is still being applied on the centrale ("Wordt bijgewerkt"). */
    val hasPendingSync: Boolean
        get() = overview?.numbers?.any { it.sync == SyncState.PENDING } == true ||
            devices?.devices?.any { it.sync == SyncState.PENDING } == true ||
            ringGroups?.ringGroups?.any { it.sync == SyncState.PENDING } == true ||
            hours?.hours?.any { it.sync == SyncState.PENDING } == true ||
            numbers?.numbers?.any { it.sync == SyncState.PENDING } == true ||
            chains.values.any { it.sync == SyncState.PENDING }

    /** Anything for the notices to show. */
    val hasNotices: Boolean get() = isReadOnly || isOutdated || hasPendingSync || syncTimedOut || banner != null || loadFailure != null

    /** Everything a call can be sent to (from whichever list was loaded last). */
    val targetOptions: List<PbxTargetOption> get() = devices?.targets ?: ringGroups?.targets ?: hours?.targets ?: emptyList()
}

/**
 * The "Centrale" section of ONE paired account: what the server last said, the saves, and the rules around them (the version goes
 * with every change, "changed in the meantime" reloads, a frozen centrale blocks every change, a pending sync is followed every five
 * seconds for at most three minutes). The server enforces everything; this keeps the screens honest.
 */
class PbxSectionModel(
    val accountId: String,
    private val fallbackName: String,
    readOnly: Boolean,
    private val service: PbxServicing,
    private val scope: CoroutineScope,
    private val gate: PbxGate = PbxGate.Open,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val _state = MutableStateFlow(PbxSectionState(isReadOnly = readOnly))
    val state: StateFlow<PbxSectionState> = _state.asStateFlow()

    /** Called when the role is gone (403): the hub hides the section. */
    var onAccessLost: (() -> Unit)? = null

    /** Called on a 401: the pairing is gone, the app refreshes its accounts. */
    var onRevoked: (() -> Unit)? = null

    private var pollJob: Job? = null

    private inline fun edit(change: PbxSectionState.() -> PbxSectionState) = _state.update { it.change() }

    val pbxName: String get() = _state.value.overview?.pbx?.name ?: fallbackName

    // MARK: Reading

    suspend fun load(part: PbxPart) {
        try {
            when (part) {
                PbxPart.OVERVIEW -> {
                    val value = service.overview()
                    edit { copy(overview = value, isReadOnly = value.pbx.readOnly) }
                }
                PbxPart.DEVICES -> {
                    val value = service.devices()
                    edit { copy(devices = value) }
                }
                PbxPart.RING_GROUPS -> {
                    val value = service.ringGroups()
                    edit { copy(ringGroups = value) }
                }
                PbxPart.HOURS -> {
                    val value = service.hours()
                    edit { copy(hours = value) }
                }
                PbxPart.NUMBERS -> {
                    val value = service.numbers()
                    edit { copy(numbers = value) }
                }
            }

            edit { copy(isOutdated = false, loadFailure = null) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            handleLoadError(error)
        }
    }

    /** Loads a part that is not there yet (the first time a screen opens), and keeps the polling going. */
    suspend fun loadIfNeeded(part: PbxPart) {
        val s = _state.value
        val missing = when (part) {
            PbxPart.OVERVIEW -> s.overview == null
            PbxPart.DEVICES -> s.devices == null
            PbxPart.RING_GROUPS -> s.ringGroups == null
            PbxPart.HOURS -> s.hours == null
            PbxPart.NUMBERS -> s.numbers == null
        }

        if (missing) {
            edit { copy(isLoading = true) }
            try {
                load(part)
            } finally {
                edit { copy(isLoading = false) }
            }
        }

        startPollingIfNeeded()
    }

    /** Pull to refresh: this part again (and the overview, which shows all numbers). */
    suspend fun refresh(part: PbxPart) {
        load(part)

        if (part != PbxPart.OVERVIEW) load(PbxPart.OVERVIEW)

        startPollingIfNeeded()
    }

    /** Reload what was loaded before (and always the overview). */
    suspend fun reloadLoaded() {
        load(PbxPart.OVERVIEW)

        val s = _state.value

        if (s.devices != null) load(PbxPart.DEVICES)
        if (s.ringGroups != null) load(PbxPart.RING_GROUPS)
        if (s.hours != null) load(PbxPart.HOURS)
        if (s.numbers != null) load(PbxPart.NUMBERS)

        for (id in _state.value.chains.keys.sorted()) loadChain(id)
    }

    /** The sounds for the pickers; a failure leaves the chain's own list (`options.sounds`) as the only choices. */
    suspend fun loadSounds() {
        try {
            val sounds = service.sounds()
            edit { copy(sounds = sounds) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The pickers fall back to the sounds the chain itself names.
        }
    }

    // MARK: Number chains

    /** Reads the chain of one number (again). */
    suspend fun loadChain(numberId: String) {
        try {
            val chain = service.numberChain(numberId)
            edit { copy(chains = chains + (numberId to chain), chainFailures = chainFailures - numberId, isOutdated = false) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = PbxFailure.classify(error)

            when (failure) {
                PbxFailure.AccessLost, PbxFailure.Revoked, PbxFailure.Offline, PbxFailure.ReadOnly -> handleLoadError(error)
                else -> edit { copy(chainFailures = chainFailures + (numberId to failure)) }
            }
        }
    }

    /** The chain screen opens: load it when it is not there, and keep the polling going. */
    suspend fun loadChainIfNeeded(numberId: String) {
        if (_state.value.chains[numberId] == null) {
            edit { copy(isLoading = true) }
            try {
                loadChain(numberId)
            } finally {
                edit { copy(isLoading = false) }
            }
        }

        startPollingIfNeeded()
    }

    /** One step of a chain (`null` = nothing changed, nothing is sent). The answer is the fresh chain. */
    suspend fun saveChainStep(numberId: String, step: ChainStepRequest?): ChainSaveOutcome {
        if (step == null) return ChainSaveOutcome.Unchanged

        return mutateChain(numberId) { service.saveChainStep(numberId, step) }
    }

    /** Call recording of a number (`null` = nothing changed). */
    suspend fun saveRecording(numberId: String, patch: NumberRecordingPatch?): ChainSaveOutcome {
        if (patch == null) return ChainSaveOutcome.Unchanged

        return mutateChain(numberId) { service.setNumberRecording(numberId, patch) }
    }

    /**
     * Like [mutate], but the answer is the fresh chain and a "changed in the meantime" keeps the form open: the fresh chain is shown,
     * what the user typed stays (the form rebases onto it).
     */
    private suspend fun mutateChain(numberId: String, send: suspend () -> NumberChain): ChainSaveOutcome {
        if (_state.value.isReadOnly) return ChainSaveOutcome.Failed(PbxFailure.ReadOnly)

        // Taken before the unlock prompt: a second tap while it is open must not send a second request.
        if (!takeSaving()) return ChainSaveOutcome.Unchanged

        try {
            unlockFailure()?.let { return ChainSaveOutcome.Failed(it) }

            val fresh = try {
                send()
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiException.StaleChain) {
                val chain = error.freshChain()

                if (chain != null) {
                    edit { copy(chains = chains + (numberId to chain)) }
                } else {
                    loadChain(numberId)
                }

                return ChainSaveOutcome.Stale
            } catch (error: ApiException.CostNotAccepted) {
                return ChainSaveOutcome.CostRequired(error.recordingCost())
            } catch (error: Exception) {
                val failure = PbxFailure.classify(error)

                return when (failure) {
                    PbxFailure.Stale -> {
                        // A plain 409 without the chain: read it again.
                        loadChain(numberId)
                        ChainSaveOutcome.Stale
                    }
                    PbxFailure.Advanced -> {
                        // The number turned out to be more than the chain can show: show it read-only.
                        loadChain(numberId)
                        ChainSaveOutcome.Failed(PbxFailure.Advanced)
                    }
                    else -> {
                        val outcome = handleSaveError(error)
                        if (outcome is PbxSaveOutcome.Failed) ChainSaveOutcome.Failed(outcome.failure) else ChainSaveOutcome.Failed(failure)
                    }
                }
            }

            edit { copy(chains = chains + (numberId to fresh), chainFailures = chainFailures - numberId, banner = null, syncTimedOut = false) }

            if (_state.value.numbers != null) load(PbxPart.NUMBERS)
            if (_state.value.overview != null) load(PbxPart.OVERVIEW)

            startPollingIfNeeded()

            return ChainSaveOutcome.Saved
        } finally {
            edit { copy(isSaving = false) }
        }
    }

    private fun handleLoadError(error: Throwable) {
        val failure = PbxFailure.classify(error)

        when (failure) {
            PbxFailure.AccessLost -> accessLost()
            PbxFailure.Revoked -> onRevoked?.invoke()
            // Keep the last known state and say it is not current.
            PbxFailure.Offline -> edit { copy(isOutdated = true) }
            PbxFailure.ReadOnly -> edit { copy(isReadOnly = true) }
            else -> edit { copy(loadFailure = failure) }
        }
    }

    /** The centrale state from `GET /me`: it can lift a block (activated) or set one (frozen). */
    fun noteReadOnly(value: Boolean) {
        edit { copy(isReadOnly = value) }
    }

    private fun accessLost() {
        edit { copy(banner = PbxBanner(PbxFailure.AccessLost, isError = true)) }
        onAccessLost?.invoke()
    }

    fun dismissBanner() {
        edit { copy(banner = null) }
    }

    // MARK: Saving

    suspend fun saveDevice(draft: DeviceDraft, original: PbxDevice): PbxSaveOutcome {
        val patch = draft.patch(original) ?: return PbxSaveOutcome.Unchanged

        return mutate { service.updateDevice(original.id, patch) }
    }

    suspend fun createRingGroup(draft: RingGroupDraft): PbxSaveOutcome {
        val body = draft.creation()

        return mutate { service.createRingGroup(body) }
    }

    suspend fun saveRingGroup(draft: RingGroupDraft, original: PbxRingGroup): PbxSaveOutcome {
        val patch = draft.patch(original) ?: return PbxSaveOutcome.Unchanged

        return mutate { service.updateRingGroup(original.id, patch) }
    }

    suspend fun saveHours(draft: HoursDraft, original: PbxHours): PbxSaveOutcome {
        val patch = draft.patch(original) ?: return PbxSaveOutcome.Unchanged

        return applyHoursPatch(patch, original.id)
    }

    /** "Tijdelijk dicht": the holiday list replaces the old one (see [TemporaryClosure]). */
    suspend fun saveHolidays(holidays: List<PbxHolidayInput>, original: PbxHours): PbxSaveOutcome =
        applyHoursPatch(PbxHoursPatch(version = original.version, holidays = holidays), original.id)

    private suspend fun applyHoursPatch(patch: PbxHoursPatch, id: String): PbxSaveOutcome = mutate { service.updateHours(id, patch) }

    /** `target == null` = back to the start of the call flow of the centrale. */
    suspend fun saveRouting(number: PbxNumber, target: PbxTarget?): PbxSaveOutcome {
        if (target == number.routing) return PbxSaveOutcome.Unchanged

        val patch = PbxRoutingPatch(target, number.version)

        return mutate { service.setNumberRouting(number.id, patch) }
    }

    private fun takeSaving(): Boolean {
        while (true) {
            val current = _state.value

            if (current.isSaving) return false
            if (_state.compareAndSet(current, current.copy(isSaving = true))) return true
        }
    }

    /** `null` = unlocked; else why not. */
    private suspend fun unlockFailure(): PbxFailure? = when (gate.ensureUnlocked()) {
        PbxGate.Result.UNLOCKED -> null
        PbxGate.Result.UNAVAILABLE -> PbxFailure.NotAvailable
        PbxGate.Result.CANCELLED, PbxGate.Result.FAILED -> PbxFailure.Authentication
    }

    /** The common shape of every change: frozen? asked the phone? send, then read again, then follow a pending sync. */
    private suspend fun mutate(send: suspend () -> Unit): PbxSaveOutcome {
        if (_state.value.isReadOnly) return PbxSaveOutcome.Failed(PbxFailure.ReadOnly)

        // Taken before the unlock prompt: a second tap while it is open must not send a second request.
        if (!takeSaving()) return PbxSaveOutcome.Unchanged

        try {
            unlockFailure()?.let { return PbxSaveOutcome.Failed(it) }

            try {
                send()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return handleSaveError(error)
            }

            edit { copy(banner = null, syncTimedOut = false) }
            reloadLoaded()
            startPollingIfNeeded()

            return PbxSaveOutcome.Saved
        } finally {
            edit { copy(isSaving = false) }
        }
    }

    private suspend fun handleSaveError(error: Throwable): PbxSaveOutcome {
        val failure = PbxFailure.classify(error)

        when (failure) {
            PbxFailure.AccessLost -> accessLost()
            PbxFailure.Revoked -> onRevoked?.invoke()
            PbxFailure.ReadOnly -> {
                edit { copy(isReadOnly = true) }
                load(PbxPart.OVERVIEW)
            }
            PbxFailure.Stale -> {
                // Someone (or this phone, an instant ago) changed it: load what is there now; the form closes.
                edit { copy(banner = PbxBanner(failure, isError = false)) }
                reloadLoaded()

                return PbxSaveOutcome.Stale
            }
            else -> Unit
        }

        return PbxSaveOutcome.Failed(failure)
    }

    // MARK: Following a pending sync

    fun startPollingIfNeeded() {
        if (pollJob?.isActive == true || !_state.value.hasPendingSync) return

        edit { copy(syncTimedOut = false) }
        pollJob = scope.launch { poll() }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun poll() {
        var rounds = 0

        try {
            while (_state.value.hasPendingSync) {
                if (rounds >= MAX_POLL_ROUNDS) {
                    edit { copy(syncTimedOut = true) }
                    break
                }

                rounds += 1
                sleep(POLL_INTERVAL_MILLIS)
                reloadLoaded()
            }
        } finally {
            pollJob = null
        }
    }

    companion object {
        const val POLL_INTERVAL_MILLIS = 5_000L

        /** Three minutes of polling. */
        const val MAX_POLL_ROUNDS = 36
    }
}
