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
import nl.fullstackstudio.fsvoip.core.InternalContact

sealed interface ContactSource {
    data object Device : ContactSource

    data object InternalExtensions : ContactSource

    /** A customer contact list from the portal. */
    data class ContactList(val id: String) : ContactSource
}

data class ContactEntry(
    val id: String,
    val name: String,
    val company: String? = null,
    val numbers: List<String>,
    val source: ContactSource,
)

/** Looks up a caller name. Synchronous and cheap: called while a call is being put on the screen. */
fun interface NameLookup {
    fun name(number: String): String?
}

object PhoneNumbers {
    /** Same number in another notation? Equal digits, or the same last 9 digits (`+31612345678` vs `0612345678`). */
    fun same(a: String, b: String): Boolean {
        val x = a.filter { it in '0'..'9' }
        val y = b.filter { it in '0'..'9' }

        if (x.isEmpty() || y.isEmpty()) {
            return false
        }

        // Short internal numbers (extensions) must match exactly.
        if (x.length < 9 || y.length < 9) {
            return x == y
        }

        return x.takeLast(9) == y.takeLast(9)
    }
}

/** Internal contacts straight from `GET /me`. */
class InternalContactsProvider(internalContacts: List<InternalContact>) : NameLookup {
    val entries: List<ContactEntry> = internalContacts.map {
        ContactEntry(id = "internal:${it.number}", name = it.name, numbers = listOf(it.number), source = ContactSource.InternalExtensions)
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
