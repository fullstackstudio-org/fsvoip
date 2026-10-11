// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The long-lived state of the Contacts tab: the hub (the address books and their sync), the favourites, which sub screen is open
// and what the list shows (segment, filter, search). Installed once per process with `model.contactsKit(context)` (the app services
// do it at start, so caller names work before the tab is ever opened) and kept by `AppModel.feature`.

package nl.fullstackstudio.fsvoip.ui.contacts

import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.contacts.ClientContactsApi
import nl.fullstackstudio.fsvoip.contacts.ContactDraft
import nl.fullstackstudio.fsvoip.contacts.ContactFailure
import nl.fullstackstudio.fsvoip.contacts.ContactListFilter
import nl.fullstackstudio.fsvoip.contacts.ContactScope
import nl.fullstackstudio.fsvoip.contacts.ContactSource
import nl.fullstackstudio.fsvoip.contacts.ContactsHub
import nl.fullstackstudio.fsvoip.contacts.ContactsSettingsStoring
import nl.fullstackstudio.fsvoip.contacts.FavoriteContacts
import nl.fullstackstudio.fsvoip.contacts.PhoneCountry
import nl.fullstackstudio.fsvoip.contacts.SystemDeviceContacts
import nl.fullstackstudio.fsvoip.core.ContactCapabilities
import nl.fullstackstudio.fsvoip.core.ContactsFileStore
import nl.fullstackstudio.fsvoip.core.InMemoryContactsStore
import nl.fullstackstudio.fsvoip.core.InMemoryKeyValueStore
import nl.fullstackstudio.fsvoip.core.SharedPreferencesStore
import nl.fullstackstudio.fsvoip.demo.DemoConfig

/** Where the Contacts tab is: the list, one contact, the edit form (a new contact when `entryId` is null) or the sources. */
sealed interface ContactsRoute {
    data object Home : ContactsRoute

    data class Detail(val entryId: String) : ContactsRoute

    data class Edit(val entryId: String?) : ContactsRoute

    data object Sources : ContactsRoute
}

class ContactsNav {
    private val _route = MutableStateFlow<ContactsRoute>(ContactsRoute.Home)

    val route: StateFlow<ContactsRoute> = _route.asStateFlow()

    fun open(route: ContactsRoute) {
        _route.value = route
    }

    /** One step back: from the form to the contact it came from, from a contact to the list. */
    fun back() {
        _route.value = when (val current = _route.value) {
            is ContactsRoute.Edit -> current.entryId?.let { ContactsRoute.Detail(it) } ?: ContactsRoute.Home
            else -> ContactsRoute.Home
        }
    }

    fun home() {
        _route.value = ContactsRoute.Home
    }
}

/** What the list shows; kept here so it is still there after looking at a contact. */
class ContactsViewState {
    var scope by mutableStateOf(ContactScope.CENTRALE)
    var filter by mutableStateOf<ContactListFilter>(ContactListFilter.All)
    var query by mutableStateOf("")
    val listState = LazyListState()

    fun select(scope: ContactScope) {
        this.scope = scope
        filter = ContactListFilter.All
    }
}

/**
 * The form being filled in. It lives here (not in the screen) so a rotation or a dark-mode switch does not throw away what was
 * typed; [key] says which contact the state belongs to (`new` or an entry id), and a new key starts a fresh form.
 */
class ContactEditState {
    var key by mutableStateOf<String?>(null)
    var draft by mutableStateOf(ContactDraft())
    var original by mutableStateOf(ContactDraft())

    /** The country picked for a number typed in national form, by phone row id. */
    var countries by mutableStateOf<Map<String, PhoneCountry>>(emptyMap())
    var countryTouched by mutableStateOf(false)

    /** The `updatedAt` the screen was filled with; goes back as `expectedUpdatedAt`. */
    var expectedUpdatedAt by mutableStateOf<String?>(null)
    var contactId by mutableStateOf<String?>(null)
    var accountId by mutableStateOf<String?>(null)
    var isLoading by mutableStateOf(false)
    var isSaving by mutableStateOf(false)
    var failure by mutableStateOf<ContactFailure?>(null)
    var showsStale by mutableStateOf(false)

    val isDirty: Boolean get() = draft != original || countryTouched

    fun reset() {
        key = null
        draft = ContactDraft()
        original = ContactDraft()
        countries = emptyMap()
        countryTouched = false
        expectedUpdatedAt = null
        contactId = null
        accountId = null
        isLoading = false
        isSaving = false
        failure = null
        showsStale = false
    }
}

class ContactsKit(
    val hub: ContactsHub,
    val favorites: FavoriteContacts,
    val nav: ContactsNav,
    val view: ContactsViewState,
    val scope: CoroutineScope,
    val edit: ContactEditState = ContactEditState(),
)

/** The kit of this process. The first call creates it (and starts the sync); later calls return the same one. */
fun AppModel.contactsKit(context: Context): ContactsKit = feature { createContactsKit(this, context.applicationContext) }

private fun createContactsKit(model: AppModel, context: Context): ContactsKit {
    val demo = DemoConfig.load(context) != null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings = object : ContactsSettingsStoring {
        override var usesDeviceContacts: Boolean
            get() = model.useDeviceContacts
            set(value) {
                model.useDeviceContacts = value
            }
    }

    val hub = ContactsHub(
        // Demo mode keeps nothing on the phone.
        store = if (demo) InMemoryContactsStore() else ContactsFileStore(File(context.filesDir, "contacts")),
        api = { accountId -> model.client(accountId)?.let(::ClientContactsApi) },
        device = SystemDeviceContacts(context),
        settings = settings,
        scope = scope,
        workDispatcher = Dispatchers.Default,
        minimumIntervalMillis = if (demo) 0 else 60_000,
    )
    val favorites = FavoriteContacts(
        if (demo) InMemoryKeyValueStore() else SharedPreferencesStore(context.getSharedPreferences("fsvoip.contacts", Context.MODE_PRIVATE)),
    )

    // A 401 on the contacts means the pairing is gone: `GET /me` removes the account here (and with it its contacts).
    hub.onUnauthorized = { scope.launch { model.refreshAccounts() } }

    // The one `GET /me` of every refresh feeds the contacts as well; an account that goes away takes its address book with it.
    model.meListeners += { accountId, me -> hub.apply(me.capabilities?.contacts ?: ContactCapabilities(), accountId) }
    model.accountRemovedListeners += { accountId -> hub.forget(accountId) }

    scope.launch {
        var known = emptySet<String>()

        model.accounts.collect { accounts ->
            val ids = accounts.map { it.id }
            hub.configure(ids)

            // A line that was just added: its address book is wanted now, not at the next timer tick.
            val added = ids.filter { it !in known }
            known = ids.toSet()
            added.forEach { launch { hub.sync(it) } }
        }
    }

    scope.launch { model.internalContacts.collect { byAccount -> byAccount.forEach { (accountId, contacts) -> hub.setInternalContacts(contacts, accountId) } } }
    // The switch in Settings ("Namen uit je contacten") is the same choice as the one on the sources screen.
    scope.launch { model.settingsRevision.collect { hub.deviceSettingChanged() } }

    var timer: Job? = null

    // In front: read the address books now and look again every few minutes. In the background nothing polls.
    val observer = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            scope.launch {
                hub.awaitLoaded()
                hub.syncAll()
                hub.refreshDeviceContacts()
            }

            timer?.cancel()
            timer = scope.launch {
                while (isActive) {
                    delay(300_000)
                    hub.syncAll()
                }
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            timer?.cancel()
            timer = null
        }
    }
    ProcessLifecycleOwner.get().lifecycle.addObserver(observer)

    return ContactsKit(hub, favorites, ContactsNav(), ContactsViewState(), scope)
}

/** Demo mode (`demo-screen`): open a sub screen of the Contacts tab without tapping. Waits for the first synced contact. */
fun openContactsDemoScreen(model: AppModel, screen: String) {
    model.select(AppModel.Tab.CONTACTS)
    // The app services installed the kit at start (they need it for caller names), so no context is needed here.
    val kit = model.feature<ContactsKit> { error("The contacts kit is installed by AppServices") }

    kit.scope.launch {
        val entries = kit.hub.entries.first { list -> list.any { it.source == ContactSource.Customer } }
        val pieter = entries.firstOrNull { it.name == "Pieter de Groot" } ?: entries.first { it.source == ContactSource.Customer }

        delay(400)

        when (screen) {
            "detail" -> kit.nav.open(ContactsRoute.Detail(pieter.id))
            "edit" -> kit.nav.open(ContactsRoute.Edit(pieter.id))
            "new" -> kit.nav.open(ContactsRoute.Edit(null))
            "sources" -> kit.nav.open(ContactsRoute.Sources)
            "phone" -> kit.view.select(ContactScope.PHONE)
            else -> Unit
        }
    }
}
