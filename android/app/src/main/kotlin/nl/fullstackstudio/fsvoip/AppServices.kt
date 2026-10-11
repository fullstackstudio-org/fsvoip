// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip

import android.content.Context
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.call.CallService
import nl.fullstackstudio.fsvoip.callcontroller.AndroidMain
import nl.fullstackstudio.fsvoip.callcontroller.PhoneController
import nl.fullstackstudio.fsvoip.callcontroller.TelecomCallSystem
import nl.fullstackstudio.fsvoip.contacts.CompositeNameLookup
import nl.fullstackstudio.fsvoip.contacts.ContactsHub
import nl.fullstackstudio.fsvoip.contacts.DeviceContactsReader
import nl.fullstackstudio.fsvoip.contacts.FileContactsStore
import nl.fullstackstudio.fsvoip.contacts.LiveContactsApi
import nl.fullstackstudio.fsvoip.contacts.DeviceContactsProvider
import nl.fullstackstudio.fsvoip.contacts.InternalContactsProvider
import nl.fullstackstudio.fsvoip.contacts.NameLookup
import nl.fullstackstudio.fsvoip.call.ExoPlayerBackend
import nl.fullstackstudio.fsvoip.core.AccountStore
import nl.fullstackstudio.fsvoip.core.AudioPlayerModel
import nl.fullstackstudio.fsvoip.core.HistoryModel
import nl.fullstackstudio.fsvoip.core.LiveMediaService
import nl.fullstackstudio.fsvoip.core.FsLogger
import nl.fullstackstudio.fsvoip.core.FsVoipApiClient
import nl.fullstackstudio.fsvoip.core.InstallIdentity
import nl.fullstackstudio.fsvoip.core.InstallIdentity.Companion.toHex
import nl.fullstackstudio.fsvoip.core.KeyValuePreferencesStore
import nl.fullstackstudio.fsvoip.core.KeystoreSecretStore
import nl.fullstackstudio.fsvoip.core.LiveSelfExtensionService
import nl.fullstackstudio.fsvoip.core.SelfExtensionHub
import nl.fullstackstudio.fsvoip.core.OkHttpTransport
import nl.fullstackstudio.fsvoip.core.RecentCallsStore
import nl.fullstackstudio.fsvoip.core.SharedPreferencesStore
import nl.fullstackstudio.fsvoip.linphoneengine.LinphoneSipEngine
import nl.fullstackstudio.fsvoip.pairing.AccountService
import nl.fullstackstudio.fsvoip.pairing.DeviceDescriptor
import nl.fullstackstudio.fsvoip.pairing.PushTokenLedger
import nl.fullstackstudio.fsvoip.pairing.PushTokenReporter
import nl.fullstackstudio.fsvoip.push.PushSupport

/**
 * Composition root. The only place that knows which `SipEngine` implementation is used (plan D3): swapping the SIP
 * stack (the documented fallback is baresip) means changing the engine line and the module dependency, nothing else.
 *
 * Created once, in [FsVoipApplication.onCreate], on the main thread.
 */
class AppServices private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val logger = FsLogger("app")
    private val scope = MainScope()

    val identity: InstallIdentity
    val telecom: TelecomCallSystem
    val phone: PhoneController
    val model: AppModel
    val pushTokens: PushTokenReporter
    val biometric: BiometricAuthenticator

    /** The current FCM token (kept in memory only), for `POST /pair`. */
    @Volatile
    var fcmToken: String? = null
        private set

    init {
        val secrets = KeystoreSecretStore(SharedPreferencesStore(appContext.getSharedPreferences("fsvoip.secrets", Context.MODE_PRIVATE)))
        val plain = SharedPreferencesStore(appContext.getSharedPreferences("fsvoip.settings", Context.MODE_PRIVATE))

        identity = try {
            InstallIdentity.load(secrets)
        } catch (error: Exception) {
            // The Keystore is unavailable (rare): use a fresh identity for this run; pairing stores it again later.
            logger.error("Install identity could not be read: ${error.javaClass.simpleName}")
            InstallIdentity(InstallIdentity.secureRandomBytes(8).toHex(), java.util.UUID.randomUUID().toString())
        }

        val version = BuildConfig.VERSION_NAME
        biometric = BiometricAuthenticator(appContext)
        val accounts = AccountStore(secrets)
        val preferences = KeyValuePreferencesStore(plain)
        val api = FsVoipApiClient(baseUrl = BuildConfig.API_BASE_URL, transport = OkHttpTransport(), userAgent = "FSVoip/$version (Android)")

        val engine = LinphoneSipEngine(appContext, appVersion = version, installId = identity.installId)
        telecom = TelecomCallSystem(appContext, label = appContext.getString(R.string.app_name))
        phone = PhoneController(
            engine = engine,
            system = telecom,
            audioRouting = telecom,
            preferences = preferences,
            scheduler = AndroidMain.scheduler,
            mainExecutor = AndroidMain.executor,
        )
        pushTokens = PushTokenReporter(api, accounts, PushTokenLedger(plain))

        // Until the process is visible it counts as "in the background" (it may have been started by a push): the
        // accounts stay un-registered until the push wakes the one that is called.
        phone.enterBackground()

        val deviceContacts = DeviceContactsProvider(appContext)
        val deviceContactsReader = DeviceContactsReader(appContext, deviceContacts)
        val contactsHub = ContactsHub(
            store = FileContactsStore(java.io.File(appContext.filesDir, "contacts")),
            api = { accountId -> accounts.account(accountId)?.let { LiveContactsApi(api.authenticated(it.deviceToken)) } },
        )
        val media = LiveMediaService(api, locale = { if (java.util.Locale.getDefault().language == "nl") "nl" else "en" })

        model = AppModel(
            context = appContext,
            phone = phone,
            accountStore = accounts,
            service = AccountService(api, accounts),
            selfExtension = SelfExtensionHub(LiveSelfExtensionService(api)),
            pbx = nl.fullstackstudio.fsvoip.core.PbxHub(nl.fullstackstudio.fsvoip.core.LivePbxService(api)),
            localAccess = nl.fullstackstudio.fsvoip.core.LocalAccessGate(biometric),
            history = HistoryModel(media),
            park = nl.fullstackstudio.fsvoip.core.ParkModel(nl.fullstackstudio.fsvoip.core.LiveParkService(api)),
            mediaService = media,
            player = AudioPlayerModel(ExoPlayerBackend(appContext, userAgent = "FSVoip/$version (Android)")),
            contacts = contactsHub,
            favorites = nl.fullstackstudio.fsvoip.contacts.FavoriteContacts(plain),
            deviceContacts = deviceContactsReader,
            preferences = preferences,
            recentsStore = RecentCallsStore(plain),
            device = {
                DeviceDescriptor(
                    model = Build.MODEL,
                    osVersion = Build.VERSION.RELEASE,
                    appVersion = "$version (${BuildConfig.VERSION_CODE})",
                    installId = identity.installId,
                    sipInstanceId = "urn:uuid:${identity.sipInstanceId}",
                    pushToken = fcmToken,
                )
            },
            pushTokens = pushTokens,
        )

        val internal = { CompositeNameLookup { model.internalContacts.value.values.map { InternalContactsProvider(it) } } }
        phone.lookupName = { number ->
            // The phone's own contacts win, then the address book of the customer and the colleagues (one map lookup), then the
            // colleagues straight from `GET /me` (before the first sync has finished).
            val sources = buildList<NameLookup> {
                if (preferences.useDeviceContacts) add(deviceContacts)
                add(NameLookup { contactsHub.name(it) })
                add(internal())
            }
            CompositeNameLookup { sources }.name(number)
        }

        telecom.onSilenceRinger = { CallService.silence(appContext) }

        // A call on the screen keeps the process alive and the microphone usable in the background.
        scope.launch {
            phone.sessions.collect { sessions ->
                if (sessions.isNotEmpty()) CallService.ensureRunning(appContext)
            }
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = model.didBecomeActive()
            override fun onStop(owner: LifecycleOwner) = model.didEnterBackground()
        })

        PushSupport.fetchToken(appContext) { token -> pushTokenChanged(token) }
    }

    /** A new (or the first) FCM token: tell the server, per account. */
    fun pushTokenChanged(token: String?) {
        fcmToken = token
        scope.launch {
            pushTokens.setToken(token)
            model.reportPushTokens()
        }
    }

    companion object {
        @Volatile
        private var shared: AppServices? = null

        fun init(context: Context): AppServices = shared ?: synchronized(this) { shared ?: AppServices(context).also { shared = it } }

        fun get(context: Context): AppServices = init(context)
    }
}
