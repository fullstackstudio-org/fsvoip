// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The contacts of the app: three sources, one list (mirrored from iOS).
//   1. `Customer`: the customer's own address book from the portal (synced to this phone, editable, see ContactsHub),
//   2. `Device`: the phone's own contacts (read only, never uploaded and never written to disk by FSVoip),
//   3. `InternalExtensions`: the other extensions of the PBX (from `GET /me`),
// plus number matching for caller names (PhoneNumberMatcher, ContactIndex).

package nl.fullstackstudio.fsvoip.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import java.text.Normalizer
import nl.fullstackstudio.fsvoip.core.ContactPhoneLabel
import nl.fullstackstudio.fsvoip.core.InternalContact

sealed interface ContactSource {
    /** The customer's own address book (portal). */
    data object Customer : ContactSource

    data object Device : ContactSource

    data object InternalExtensions : ContactSource

    /** A customer contact list from the portal. */
    data class ContactList(val id: String) : ContactSource
}

data class ContactPhoneEntry(
    val number: String,
    val label: ContactPhoneLabel = ContactPhoneLabel.OTHER,
    val isPrimary: Boolean = false,
)

data class ContactEntry(
    val id: String,
    val name: String,
    val company: String? = null,
    val email: String? = null,
    val phones: List<ContactPhoneEntry> = emptyList(),
    val source: ContactSource,
    /** Customer contacts: the accounts whose address book holds this contact (the same customer can have several extensions). */
    val accountIds: List<String> = emptyList(),
    /** Customer contacts: the server's id and `updatedAt` (needed to edit). */
    val contactId: String? = null,
    val updatedAt: String? = null,
) {
    val numbers: List<String> get() = phones.map { it.number }

    /** What a list shows for this contact: the name, otherwise the company, otherwise the first number. */
    val displayName: String
        get() {
            val trimmed = name.trim()

            if (trimmed.isNotEmpty()) return trimmed
            if (!company.isNullOrBlank()) return company

            return phones.firstOrNull()?.number ?: ""
        }

    /** The letter a list files this contact under (`#` for digits and symbols). */
    val sectionLetter: Char
        get() {
            val first = ContactText.fold(displayName).uppercase().firstOrNull()

            return if (first != null && first.isLetter()) first else '#'
        }

    /** Search: name, company, e-mail and numbers (digits compared without spaces or punctuation). */
    fun matches(query: String): Boolean {
        val needle = query.trim()

        if (needle.isEmpty()) return true

        val folded = ContactText.fold(needle)

        if (ContactText.fold(displayName).contains(folded)) return true
        if (company != null && ContactText.fold(company).contains(folded)) return true
        if (email != null && ContactText.fold(email).contains(folded)) return true

        val digits = needle.filter { it in '0'..'9' }

        if (digits.length >= 3) {
            return phones.any { phone ->
                val own = phone.number.filter { it in '0'..'9' }
                // Typed the way people write it: `06 12...` finds `+31 6 12...`.
                val national = if (own.startsWith("31") && phone.number.startsWith("+")) "0" + own.drop(2) else own

                own.contains(digits) || national.contains(digits)
            }
        }

        return false
    }

    companion object {
        /** Entry for a source that only knows plain numbers (colleagues). */
        fun withNumbers(id: String, name: String, numbers: List<String>, source: ContactSource, company: String? = null) =
            ContactEntry(id = id, name = name, company = company, phones = numbers.map { ContactPhoneEntry(it) }, source = source)
    }
}

/** Case- and accent-insensitive text handling, the way a person expects names to sort and match. */
object ContactText {
    private val marks = Regex("\\p{M}+")

    /** Lower case without accents (`Zoë` becomes `zoe`). */
    fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).replace(marks, "").lowercase()

    private val collator: java.text.Collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }

    /** Name order for a list: case- and accent-insensitive, ties broken by the plain order so the result is stable. */
    val nameOrder: Comparator<String> = Comparator { a, b ->
        val primary = synchronized(collator) { collator.compare(a, b) }

        if (primary != 0) primary else a.compareTo(b)
    }
}

/** Looks up a caller name. Synchronous and cheap: called while a call is being put on the screen. */
fun interface NameLookup {
    fun name(number: String): String?
}

object PhoneNumbers {
    /** Same number in another notation (`+31612345678` vs `06 12 34 56 78`)? Extensions only match exactly. */
    fun same(a: String, b: String): Boolean = PhoneNumberMatcher.same(a, b)
}

/** Internal contacts straight from `GET /me`. */
class InternalContactsProvider(internalContacts: List<InternalContact>) : NameLookup {
    val entries: List<ContactEntry> = internalContacts.map {
        ContactEntry.withNumbers(id = "internal:${it.number}", name = it.name, numbers = listOf(it.number), source = ContactSource.InternalExtensions)
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
