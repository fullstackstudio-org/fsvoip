// SPDX-License-Identifier: AGPL-3.0-or-later
//
// State of the app: paired accounts, the pairing flow, settings and recents (the Android port of the iOS
// `FSVoipAppModel`). Calls and registrations live in `phone` (callcontroller); the screens observe both.

package nl.fullstackstudio.fsvoip

import android.content.Context
import androidx.annotation.StringRes
import java.util.UUID
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.callcontroller.CallDisplay
import nl.fullstackstudio.fsvoip.callcontroller.PhoneController
import nl.fullstackstudio.fsvoip.callcontroller.PhoneException
import nl.fullstackstudio.fsvoip.core.AppCapabilities
import nl.fullstackstudio.fsvoip.core.AudioPlayerModel
import nl.fullstackstudio.fsvoip.core.HistoryModel
import nl.fullstackstudio.fsvoip.core.MediaServicing
import nl.fullstackstudio.fsvoip.core.VoicemailModel
import nl.fullstackstudio.fsvoip.core.VoicemailMessage
import nl.fullstackstudio.fsvoip.core.VoicemailAccess
import nl.fullstackstudio.fsvoip.core.FsLogger
import nl.fullstackstudio.fsvoip.core.InternalContact
import nl.fullstackstudio.fsvoip.core.MeResponse
import nl.fullstackstudio.fsvoip.core.OutboundChoice
import nl.fullstackstudio.fsvoip.core.PbxVocabulary
import nl.fullstackstudio.fsvoip.core.PreferencesStore
import nl.fullstackstudio.fsvoip.core.PushMessage
import nl.fullstackstudio.fsvoip.core.HistoryEntry
import nl.fullstackstudio.fsvoip.core.RecentCall
import nl.fullstackstudio.fsvoip.core.RecentCallsStore
import nl.fullstackstudio.fsvoip.core.SelfExtension
import nl.fullstackstudio.fsvoip.core.SelfExtensionHub
import nl.fullstackstudio.fsvoip.core.SelfNumber
import nl.fullstackstudio.fsvoip.core.StoredAccount
import nl.fullstackstudio.fsvoip.ui.RecentFormat
import nl.fullstackstudio.fsvoip.pairing.AccountRefreshResult
import nl.fullstackstudio.fsvoip.pairing.AccountServicing
import nl.fullstackstudio.fsvoip.pairing.DeviceDescriptor
import nl.fullstackstudio.fsvoip.pairing.PairingFailure
import nl.fullstackstudio.fsvoip.pairing.PairingLink
import nl.fullstackstudio.fsvoip.pairing.PairingLinkException
import nl.fullstackstudio.fsvoip.pairing.PairingLinkParser
import nl.fullstackstudio.fsvoip.pairing.PushTokenReporting
import nl.fullstackstudio.fsvoip.sipengine.CallOptions
import nl.fullstackstudio.fsvoip.sipengine.RegistrationState

/** Runtime permissions the model asks for at the right moment; the activity implements them. */
interface PermissionRequester {
    suspend fun requestMicrophone(): Boolean
    suspend fun requestNotifications()
}

class AppModel(
    context: Context,
    val phone: PhoneController,
    private val accountStore: nl.fullstackstudio.fsvoip.core.AccountStore,
    private val service: AccountServicing,
    val selfExtension: SelfExtensionHub,
    val history: HistoryModel,
    val mediaService: MediaServicing,
    val player: AudioPlayerModel,
    private val preferences: PreferencesStore,
    private val recentsStore: RecentCallsStore,
    private val device: () -> DeviceDescriptor,
    private val pushTokens: PushTokenReporting?,
    private val logger: FsLogger = FsLogger("app"),
) {
    private val context = context.applicationContext
    private val scope = MainScope()

    /** The pairing flow, shown on top of whatever screen is open. */
    sealed interface PairingPhase {
        data object Idle : PairingPhase

        /** A link arrived (QR scan, App Link or `fsvoip://`); nothing was claimed yet. */
        data class LinkReceived(val link: PairingLink) : PairingPhase

        data class Pairing(val link: PairingLink) : PairingPhase

        data class Paired(val account: StoredAccount) : PairingPhase

        /** [link] is kept when trying again makes sense (the code was not consumed). */
        data class Failed(val link: PairingLink?, val failure: PairingFailure) : PairingPhase

        val isActive: Boolean
            get() = this != Idle
    }

    /** Same order as the iOS app. Settings is not a tab: it is a sheet (the gear and the avatar in the top bar). */
    enum class Tab { DIALER, ON_HOLD, RECENTS, VOICEMAIL, CONTACTS }

    data class Notice(val message: String, val isError: Boolean, val id: UUID = UUID.randomUUID())

    /** What the audio bar shows while something plays. */
    data class NowPlaying(val id: String, val title: String, val subtitle: String)

    sealed interface UnpairResult {
        data object Done : UnpairResult

        /** The server could not be reached: the user may remove the account from this phone only. */
        data class Failed(val failure: PairingFailure) : UnpairResult
    }

    private val _pairing = MutableStateFlow<PairingPhase>(PairingPhase.Idle)
    private val _isScannerPresented = MutableStateFlow(false)
    private val _scannerError = MutableStateFlow<String?>(null)
    private val _accounts = MutableStateFlow<List<StoredAccount>>(emptyList())
    private val _recents = MutableStateFlow<List<RecentCall>>(emptyList())
    private val _internalContacts = MutableStateFlow<Map<String, List<InternalContact>>>(emptyMap())
    private val _notice = MutableStateFlow<Notice?>(null)
    private val _selectedTab = MutableStateFlow(Tab.DIALER)
    private val _settingsRevision = MutableStateFlow(0)
    private val _isSettingsPresented = MutableStateFlow(false)
    private val _me = MutableStateFlow<Map<String, MeResponse>>(emptyMap())
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    private val voicemailModels = mutableMapOf<String, VoicemailModel>()

    val pairing: StateFlow<PairingPhase> = _pairing.asStateFlow()
    val isScannerPresented: StateFlow<Boolean> = _isScannerPresented.asStateFlow()

    /** Error shown inside the scanner (not a link we know). */
    val scannerError: StateFlow<String?> = _scannerError.asStateFlow()
    val accounts: StateFlow<List<StoredAccount>> = _accounts.asStateFlow()
    val recents: StateFlow<List<RecentCall>> = _recents.asStateFlow()

    /** Internal contacts per account id (from `GET /me`). */
    val internalContacts: StateFlow<Map<String, List<InternalContact>>> = _internalContacts.asStateFlow()
    val notice: StateFlow<Notice?> = _notice.asStateFlow()
    val selectedTab: StateFlow<Tab> = _selectedTab.asStateFlow()
    val isSettingsPresented: StateFlow<Boolean> = _isSettingsPresented.asStateFlow()
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    /** The last `GET /me` per account id: role, capabilities and the state of the PBX. */
    val me: StateFlow<Map<String, MeResponse>> = _me.asStateFlow()

    /** Bumped whenever a per-account setting changes, so the settings screens redraw. */
    val settingsRevision: StateFlow<Int> = _settingsRevision.asStateFlow()

    var permissions: PermissionRequester? = null

    init {
        phone.anonymousCallerText = string(R.string.call_anonymous)
        phone.onCallFinished = { call ->
            recentsStore.add(call)
            _recents.value = recentsStore.all()
        }

        // A 401/403 on the own extension: the pairing or the role is gone; `GET /me` removes or updates the account.
        selfExtension.onRevoked = { scope.launch { refreshAccounts() } }
        history.onRevoked = { scope.launch { refreshAccounts() } }

        // A call takes the speaker and the microphone: nothing else plays during it.
        scope.launch {
            phone.sessions.collect { sessions ->
                if (sessions.isNotEmpty()) stopPlayer()
            }
        }

        reloadAccounts()
        _recents.value = recentsStore.all()
    }

    // MARK: Accounts

    fun reloadAccounts() {
        _accounts.value = try {
            accountStore.accounts()
        } catch (error: Exception) {
            logger.error("Accounts could not be read: ${error.javaClass.simpleName}")
            emptyList()
        }

        phone.sync(_accounts.value)
    }

    fun account(id: String): StoredAccount? = _accounts.value.firstOrNull { it.id == id }

    fun registration(accountId: String): RegistrationState = phone.registrationState(accountId)

    /** `GET /me` for every account: labels, SIP server, internal contacts. Revoked accounts are removed. */
    suspend fun refreshAccounts() {
        for (account in _accounts.value) {
            try {
                when (val result = service.refresh(account)) {
                    is AccountRefreshResult.Updated -> {
                        _internalContacts.value = _internalContacts.value + (result.account.id to result.internalContacts)
                        _me.value = _me.value + (result.account.id to result.me)

                        if (result.me.canEditOwnExtension) {
                            // Availability (the dot on the avatar), the numbers to call out with and "Oproepvoorkeuren".
                            selfExtension.load(result.account)
                        } else {
                            selfExtension.forget(result.account.id)
                        }
                    }
                    AccountRefreshResult.Revoked -> {
                        cleanUp(account.id)
                        _notice.value = Notice(string(R.string.notice_revoked, account.displayLabel), isError = true)
                    }
                }
            } catch (error: Exception) {
                // Offline or a server hiccup: keep what we have, try again next time.
                logger.notice("Refresh of account ${account.id} failed: ${error.javaClass.simpleName}")
            }
        }

        reloadAccounts()
    }

    suspend fun rename(accountId: String, alias: String?): Boolean {
        val account = account(accountId) ?: return false

        return try {
            service.rename(account, alias)
            reloadAccounts()
            true
        } catch (error: Exception) {
            handleAccountError(error, account)
            false
        }
    }

    suspend fun unpair(accountId: String): UnpairResult {
        val account = account(accountId) ?: return UnpairResult.Done

        return try {
            service.unpair(account)
            cleanUp(accountId)
            reloadAccounts()
            _notice.value = Notice(string(R.string.notice_unpaired, account.displayLabel), isError = false)
            UnpairResult.Done
        } catch (error: Exception) {
            UnpairResult.Failed(PairingFailure.from(error))
        }
    }

    /** Remove from this phone only (when unpairing on the server did not work). */
    fun forget(accountId: String) {
        val account = account(accountId) ?: return
        runCatching { service.forget(account) }.onFailure { logger.error("Account could not be removed: ${it.javaClass.simpleName}") }
        cleanUp(accountId)
        reloadAccounts()
    }

    // MARK: Settings

    /** Whether the incoming call screen shows the dialled account for this account (the value in effect). */
    fun showsCalledAccount(accountId: String): Boolean =
        CallDisplay.shouldShowAccount(preferences.preferences(accountId).showCalledAccount, _accounts.value.size)

    fun setShowsCalledAccount(accountId: String, show: Boolean) {
        preferences.setPreferences(preferences.preferences(accountId).copy(showCalledAccount = show), accountId)
        _settingsRevision.value++
    }

    /** The account outgoing calls use unless the user picks another one. */
    val defaultOutgoingAccountId: String?
        get() = preferences.defaultOutgoingAccountId?.takeIf { stored -> _accounts.value.any { it.id == stored } } ?: _accounts.value.firstOrNull()?.id

    fun setDefaultOutgoing(accountId: String) {
        preferences.defaultOutgoingAccountId = accountId
        _settingsRevision.value++
    }

    var useDeviceContacts: Boolean
        get() = preferences.useDeviceContacts
        set(value) {
            preferences.useDeviceContacts = value
            _settingsRevision.value++
        }

    fun select(tab: Tab) {
        _selectedTab.value = tab
    }

    fun openSettings() {
        _isSettingsPresented.value = true
    }

    fun closeSettings() {
        _isSettingsPresented.value = false
    }

    /** What this pairing may do. `null` until the first `GET /me` of the account has come back (then nothing extra is shown). */
    fun capabilities(accountId: String): AppCapabilities? = _me.value[accountId]?.capabilities

    fun meOf(accountId: String): MeResponse? = _me.value[accountId]

    fun dismissNotice() {
        _notice.value = null
    }

    fun showNotice(message: String, isError: Boolean) {
        _notice.value = Notice(message, isError)
    }

    // MARK: Calls

    /** Start a call. Returns `false` (with a notice) when it could not start. */
    fun call(number: String, accountId: String?): Boolean {
        val id = accountId ?: defaultOutgoingAccountId

        if (id == null) {
            _notice.value = Notice(string(R.string.call_error_noAccount), isError = true)
            return false
        }

        return try {
            phone.startCall(number, id, CallOptions(fromNumber = OutboundChoice.headerNumber(preferences.preferences(id).outboundNumber, capabilities(id), selfExtension.state(id))))
            scope.launch { permissions?.requestMicrophone() }
            true
        } catch (error: PhoneException) {
            _notice.value = Notice(message(error), isError = true)
            false
        } catch (error: Exception) {
            _notice.value = Notice(string(R.string.error_generic), isError = true)
            false
        }
    }

    fun clearRecents() {
        recentsStore.clear()
        _recents.value = emptyList()
    }

    // MARK: Availability and the number to call out with

    /** Do not disturb on the own extension; `null` = not known (the server has no `/me/extension` or it has not loaded yet). */
    fun doNotDisturb(accountId: String): Boolean? = selfExtension.state(accountId)?.dnd

    /** Switch availability. Optimistic; a failure puts the switch back and shows a notice. */
    fun setAvailable(available: Boolean, accountId: String) {
        val account = account(accountId) ?: return

        scope.launch {
            selfExtension.setAvailable(available, account)

            if (selfExtension.failedFor.value == accountId) {
                selfExtension.clearFailure()
                _notice.value = Notice(string(R.string.self_error_other), isError = true)
            }
        }
    }

    /** "Bellen met" only exists when the server says the PBX understands `X-FSS-From`. */
    fun offersOutboundChoice(accountId: String): Boolean = capabilities(accountId)?.callerChoice == true

    fun outboundNumbers(accountId: String): List<SelfNumber> =
        if (offersOutboundChoice(accountId)) selfExtension.state(accountId)?.numbers.orEmpty() else emptyList()

    /** The chooser is only worth opening with something to choose from. */
    fun canChooseOutbound(accountId: String): Boolean = OutboundChoice.canChoose(capabilities(accountId), selfExtension.state(accountId))

    fun selectedOutbound(accountId: String): SelfNumber? =
        if (offersOutboundChoice(accountId)) OutboundChoice.selected(preferences.preferences(accountId).outboundNumber, selfExtension.state(accountId)) else null

    /** "Hoofdnummer · 085 060 7848", or "Standaardnummer" when there is nothing to show. */
    fun outboundLabel(accountId: String): String =
        selectedOutbound(accountId)?.let(PbxVocabulary::numberTitle) ?: string(R.string.outbound_default)

    /** Remember the choice. No request: the number rides along with the next call. */
    fun selectOutbound(accountId: String, number: String) {
        if (outboundNumbers(accountId).none { it.number == number }) return

        preferences.setPreferences(preferences.preferences(accountId).copy(outboundNumber = number), accountId)
        _settingsRevision.value++
    }

    // MARK: History and voicemail

    /** The calls of this phone, as lines of "Geschiedenis" (shown while the PBX cannot say, and the ones it has not caught up with). */
    fun localHistory(recents: List<RecentCall>): List<HistoryEntry> {
        val yesterday = string(R.string.recents_yesterday)

        return recents.map { call ->
            HistoryEntry.of(
                call,
                extensionName = account(call.accountId)?.extensionName,
                whenLabel = RecentFormat.`when`(java.time.Instant.ofEpochMilli(call.startedAtMillis), yesterday),
                durationLabel = RecentFormat.duration(call.durationSeconds),
            )
        }
    }

    fun refreshHistory() {
        scope.launch { for (account in _accounts.value) history.load(account) }
    }

    /** Whether this pairing may open voicemail at all (the server says; an older server says nothing and the PBX answers `403`). */
    fun canSeeVoicemail(accountId: String): Boolean = capabilities(accountId)?.voicemail?.let { it != VoicemailAccess.NONE } ?: true

    fun voicemailModel(account: StoredAccount): VoicemailModel = voicemailModels.getOrPut(account.id) {
        VoicemailModel(account, mediaService) { canSeeVoicemail(account.id) }.also { model -> model.onRevoked = { scope.launch { refreshAccounts() } } }
    }

    /** Plays a message. Not during a call; the audio bar shows what plays. */
    fun playVoicemail(account: StoredAccount, message: VoicemailMessage, title: String, subtitle: String) {
        if (phone.sessions.value.isNotEmpty()) {
            _notice.value = Notice(string(R.string.media_error_callInProgress), isError = true)
            return
        }

        val source = voicemailModel(account).source(message)
        _nowPlaying.value = NowPlaying("${account.id}/${message.key}", title, subtitle)
        player.play("${account.id}/${message.key}", source)
    }

    fun stopPlayer() {
        player.stop()
        _nowPlaying.value = null
    }

    /** Name of a contact (internal extensions; the phone's contacts when switched on) for a number. */
    fun name(number: String): String? = if (number.isEmpty()) null else phone.lookupName(number)

    // MARK: Lifecycle

    fun didBecomeActive() {
        phone.enterForeground()
        scope.launch {
            refreshAccounts()
            reportPushTokens()
        }
    }

    fun didEnterBackground() {
        phone.enterBackground()
    }

    // MARK: Push

    suspend fun reportPushTokens() {
        val reporter = pushTokens ?: return
        val report = reporter.report()

        // A 401 means the pairing is gone on the server: `GET /me` removes the account here.
        if (report.revoked.isNotEmpty()) {
            refreshAccounts()
        }
    }

    /** A `revoked` or `refresh` push (calls go to `phone.handleRingPush`, never here). */
    fun handleNotice(message: PushMessage) {
        when (message) {
            is PushMessage.Revoked -> removeRevoked(message.revoked.accountId)
            is PushMessage.Refresh -> scope.launch { refreshAccounts() }
            is PushMessage.Notice -> logger.notice("A portal notice arrived: shown in part 2")
            is PushMessage.Ring -> logger.notice("A ring message arrived as a notice: ignored")
        }
    }

    /** The server says this pairing was removed (portal, admin, or another phone took over the extension). */
    fun removeRevoked(accountId: String) {
        val account = account(accountId) ?: return
        runCatching { service.forget(account) }
        cleanUp(accountId)
        reloadAccounts()
        _notice.value = Notice(string(R.string.notice_revoked, account.displayLabel), isError = true)
    }

    // MARK: Pairing links

    /** An App Link or `fsvoip://` URL. */
    fun handleIncoming(url: String) {
        try {
            show(PairingLinkParser.parse(url))
        } catch (error: Exception) {
            logger.notice("Pairing link rejected")
            _notice.value = Notice(message(error), isError = true)
        }
    }

    /** Text from the QR scanner or the paste field. Returns `true` when it was a pairing link. */
    fun handleScanned(text: String): Boolean = try {
        val link = PairingLinkParser.parseScanned(text)
        _isScannerPresented.value = false
        _scannerError.value = null
        show(link)
        true
    } catch (error: Exception) {
        _scannerError.value = message(error)
        false
    }

    fun clearScannerError() {
        _scannerError.value = null
    }

    fun presentScanner() {
        // The scanner sits on top of the main screen, not on top of the settings sheet.
        _isSettingsPresented.value = false
        _scannerError.value = null
        _isScannerPresented.value = true
    }

    fun dismissScanner() {
        _isScannerPresented.value = false
        closePairing()
    }

    /** Exchange the received link for an account. */
    fun confirmPairing() {
        val link = when (val phase = _pairing.value) {
            is PairingPhase.LinkReceived -> phase.link
            is PairingPhase.Failed -> phase.link?.takeIf { phase.failure.isRetryable } ?: return
            else -> return
        }

        _pairing.value = PairingPhase.Pairing(link)

        scope.launch {
            try {
                val account = service.pair(link, device())
                reloadAccounts()
                _pairing.value = PairingPhase.Paired(account)
                // The new account needs this phone's push token before it can ring with the app closed.
                reportPushTokens()
                // Calls need the microphone; ask now, not in the middle of the first call.
                permissions?.requestMicrophone()
                // Notifications carry incoming calls while the app is closed: ask once there is an account.
                permissions?.requestNotifications()
            } catch (error: Exception) {
                val failure = PairingFailure.from(error)
                logger.notice("Pairing failed: $failure")
                _pairing.value = PairingPhase.Failed(if (failure.isRetryable) link else null, failure)
            }
        }
    }

    /** Close the pairing flow (after success, failure or cancel). */
    fun closePairing() {
        val phase = _pairing.value

        if (phase is PairingPhase.Pairing) {
            return
        }

        if (phase is PairingPhase.Paired) {
            _selectedTab.value = Tab.DIALER
            _isSettingsPresented.value = false
        }

        _pairing.value = PairingPhase.Idle
    }

    /** After a failed pairing: back to the scanner for a new code. */
    fun restartScan() {
        if (_pairing.value is PairingPhase.Pairing) {
            return
        }

        _pairing.value = PairingPhase.Idle
        presentScanner()
    }

    private fun show(link: PairingLink) {
        // Never log the link: the token is a one-time credential.
        logger.notice("Pairing link received")

        if (_pairing.value is PairingPhase.Pairing) {
            return
        }

        _isScannerPresented.value = false
        _pairing.value = PairingPhase.LinkReceived(link)
    }

    // MARK: Helpers

    private fun cleanUp(accountId: String) {
        preferences.removePreferences(accountId)
        _internalContacts.value = _internalContacts.value - accountId
        _me.value = _me.value - accountId
        selfExtension.forget(accountId)
        history.forget(accountId)
        voicemailModels.remove(accountId)
        if (_nowPlaying.value?.id?.startsWith("$accountId/") == true) stopPlayer()
        _settingsRevision.value++
    }

    private fun handleAccountError(error: Exception, account: StoredAccount) {
        val failure = PairingFailure.from(error)

        if (failure == PairingFailure.Revoked) {
            runCatching { service.forget(account) }
            cleanUp(account.id)
            reloadAccounts()
            _notice.value = Notice(string(R.string.notice_revoked, account.displayLabel), isError = true)
        } else {
            _notice.value = Notice(message(failure), isError = true)
        }
    }

    private fun string(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    fun message(error: Throwable): String = when (error) {
        is PairingLinkException.NotAPairingLink -> string(R.string.error_notAPairingLink)
        is PairingLinkException.MissingToken -> string(R.string.error_missingToken)
        is PairingLinkException.MalformedToken -> string(R.string.error_malformedToken)
        is PhoneException -> message(error)
        else -> message(PairingFailure.from(error))
    }

    fun message(error: PhoneException): String = when (error) {
        is PhoneException.InvalidNumber -> string(R.string.call_error_invalidNumber)
        is PhoneException.UnknownAccount -> string(R.string.call_error_noAccount)
        is PhoneException.LineNotConnected -> string(R.string.call_error_notConnected)
        is PhoneException.CallInProgress -> string(R.string.call_error_inProgress)
    }

    fun message(failure: PairingFailure): String = when (failure) {
        PairingFailure.CodeExpiredOrUsed -> string(R.string.pairing_error_expired)
        is PairingFailure.TooManyAttempts -> string(R.string.pairing_error_tooMany)
        PairingFailure.TemporarilyUnavailable -> string(R.string.pairing_error_unavailable)
        PairingFailure.Network -> string(R.string.pairing_error_network)
        PairingFailure.Revoked -> string(R.string.pairing_error_revoked)
        PairingFailure.Storage -> string(R.string.pairing_error_storage)
        PairingFailure.Other -> string(R.string.error_generic)
    }
}
