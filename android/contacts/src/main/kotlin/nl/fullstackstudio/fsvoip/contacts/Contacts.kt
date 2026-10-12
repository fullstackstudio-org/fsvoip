// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Contact sources for caller names (plan Task 7, mirrored from iOS):
//   1. the phone's own contacts (read only, never uploaded; optional, needs the contacts permission),
//   2. internal contacts (the other extensions of the PBX, from `GET /me`),
//   3. customer contact lists from the portal (a later API route).

package nl.fullstackstudio.fsvoip.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import nl.fullstackstudio.fsvoip.core.ContactPhoneLabel
import nl.fullstackstudio.fsvoip.core.InternalContact

sealed interface ContactSource {
    data object Device : ContactSource

    data object InternalExtensions : ContactSource

    /** The address book of the customer (the portal's contacts), synced to this phone. */
    data object AddressBook : ContactSource

    /** A customer contact list from the portal. */
    data class ContactList(val id: String) : ContactSource
}

data class ContactEntryPhone(val number: String, val label: ContactPhoneLabel = ContactPhoneLabel.OTHER)

data class ContactEntry(
    val id: String,
    val name: String,
    val company: String? = null,
    val phones: List<ContactEntryPhone>,
    val source: ContactSource,
    val email: String? = null,
    /** Of the customer's address book: the account it belongs to. */
    val accountId: String? = null,
) {
    val numbers: List<String>
        get() = phones.map { it.number }
}

/** Looks up a caller name. Synchronous and cheap: called while a call is being put on the screen. */
fun interface NameLookup {
    fun name(number: String): String?
}

object PhoneNumbers {
    /** Same number in another notation? See [PhoneNumberMatcher]. */
    fun same(a: String, b: String): Boolean = PhoneNumberMatcher.same(a, b)
}

/** Internal contacts straight from `GET /me`. */
class InternalContactsProvider(internalContacts: List<InternalContact>) : NameLookup {
    val entries: List<ContactEntry> = internalContacts.map {
        ContactEntry(id = "internal:${it.number}", name = it.name, phones = listOf(ContactEntryPhone(it.number, ContactPhoneLabel.WORK)), source = ContactSource.InternalExtensions)
    }

    override fun name(number: String): String? = entries.firstOrNull { entry -> entry.numbers.any { it == number } }?.name
}

/** The phone's own contacts (`ContactsContract.PhoneLookup`). Silent without the permission. */
class DeviceContactsProvider(context: Context) : NameLookup {
    private val context = context.applicationContext

    val hasPermission: Boolean
        get() = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    override fun name(number: String): String? {
        if (number.isBlank() || !hasPermission) {
            return null
        }

        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** Asks the sources in order (device contacts first, then internal extensions); the first name wins. */
class CompositeNameLookup(private val sources: () -> List<NameLookup>) : NameLookup {
    override fun name(number: String): String? = sources().firstNotNullOfOrNull { it.name(number) }
}
