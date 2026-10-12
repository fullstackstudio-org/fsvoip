// SPDX-License-Identifier: AGPL-3.0-or-later
//
// A contact as the edit screen holds it, and the request bodies built from it. Mirror of the iOS `ContactDraft`.

package nl.fullstackstudio.fsvoip.contacts

import nl.fullstackstudio.fsvoip.core.Change
import nl.fullstackstudio.fsvoip.core.Contact
import nl.fullstackstudio.fsvoip.core.ContactCreate
import nl.fullstackstudio.fsvoip.core.ContactPhone
import nl.fullstackstudio.fsvoip.core.ContactPhoneLabel
import nl.fullstackstudio.fsvoip.core.ContactUpdate

data class ContactDraftPhone(
    /** Stable identity of the row while editing (never sent). */
    val key: Long,
    val number: String = "",
    val label: ContactPhoneLabel = ContactPhoneLabel.MOBILE,
)

data class ContactDraft(
    val firstName: String = "",
    val lastName: String = "",
    val company: String = "",
    val email: String = "",
    val notes: String = "",
    val phones: List<ContactDraftPhone> = listOf(ContactDraftPhone(0)),
    val listIds: Set<String> = emptySet(),
) {
    /** The display name sent as `name`: first and last name, otherwise the company. */
    val displayName: String
        get() {
            val person = listOf(firstName, lastName).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

            return person.ifEmpty { company.trim() }
        }

    /** Rows the user left empty do not count. */
    val filledPhones: List<ContactDraftPhone>
        get() = phones.filter { it.number.isNotBlank() }

    val hasEmail: Boolean
        get() = email.isNotBlank()

    /** A contact needs a name and a way to reach it (the server refuses a contact with neither number nor e-mail). */
    val canSave: Boolean
        get() = displayName.isNotEmpty() && (filledPhones.isNotEmpty() || hasEmail)

    /** Numbers go to the server in the `+31...` form when they can be written that way; short numbers stay as typed. */
    fun requestPhones(): List<ContactPhone> = filledPhones.mapIndexed { index, row ->
        val typed = row.number.trim()

        ContactPhone(PhoneNumberMatcher.e164(typed) ?: typed, row.label, isPrimary = index == 0)
    }

    private fun text(value: String): String? = value.trim().ifEmpty { null }

    private fun set(value: String): Change<String> = text(value)?.let { Change.Set(it) } ?: Change.Keep

    fun createRequest(): ContactCreate = ContactCreate(
        name = displayName,
        firstName = set(firstName),
        lastName = set(lastName),
        company = set(company),
        email = set(email),
        notes = set(notes),
        phones = requestPhones(),
        listIds = listIds.sorted().takeIf { it.isNotEmpty() },
    )

    /** Every field is sent (an emptied field clears it); phones and lists replace the whole set. */
    fun updateRequest(expectedUpdatedAt: String): ContactUpdate = ContactUpdate(
        expectedUpdatedAt = expectedUpdatedAt,
        name = displayName,
        firstName = Change.of(text(firstName)),
        lastName = Change.of(text(lastName)),
        company = Change.of(text(company)),
        email = Change.of(text(email)),
        notes = Change.of(text(notes)),
        phones = requestPhones(),
        listIds = listIds.sorted(),
    )

    fun withPhone(index: Int, change: (ContactDraftPhone) -> ContactDraftPhone): ContactDraft = copy(phones = phones.mapIndexed { i, phone -> if (i == index) change(phone) else phone })

    fun addingPhone(): ContactDraft = copy(phones = phones + ContactDraftPhone((phones.maxOfOrNull { it.key } ?: -1) + 1, label = ContactPhoneLabel.MOBILE))

    fun removingPhone(index: Int): ContactDraft = copy(phones = phones.filterIndexed { i, _ -> i != index }.ifEmpty { listOf(ContactDraftPhone(0)) })

    companion object {
        /** From the detail of a contact (with notes and lists). */
        fun from(contact: Contact): ContactDraft {
            val hasParts = !contact.firstName.isNullOrEmpty() || !contact.lastName.isNullOrEmpty()
            // A contact that only has a display name ("Bakkerij Smit"): keep it as it is on the first line, unless it is the company.
            val firstName = when {
                hasParts -> contact.firstName.orEmpty()
                contact.name.isNotEmpty() && contact.name != contact.company -> contact.name
                else -> ""
            }
            val phones = contact.phones.mapIndexed { index, phone -> ContactDraftPhone(index.toLong(), phone.number, phone.label) }

            return ContactDraft(
                firstName = firstName,
                lastName = if (hasParts) contact.lastName.orEmpty() else "",
                company = contact.company.orEmpty(),
                email = contact.email.orEmpty(),
                notes = contact.notes.orEmpty(),
                phones = phones.ifEmpty { listOf(ContactDraftPhone(0)) },
                listIds = contact.listIds.toSet(),
            )
        }

        /** A new contact from something that is already known (a device contact, a caller, a call in the history). */
        fun from(entry: ContactEntry): ContactDraft {
            val parts = entry.name.split(" ", limit = 2)
            val isCompanyOnly = entry.name == entry.company
            val phones = entry.phones.mapIndexed { index, phone -> ContactDraftPhone(index.toLong(), phone.number, phone.label) }

            return ContactDraft(
                firstName = if (isCompanyOnly) "" else parts.firstOrNull().orEmpty(),
                lastName = if (isCompanyOnly) "" else parts.getOrNull(1).orEmpty(),
                company = entry.company.orEmpty(),
                email = entry.email.orEmpty(),
                phones = phones.ifEmpty { listOf(ContactDraftPhone(0)) },
            )
        }

        /** A new contact for a number that is not in the address book yet. */
        fun forNumber(number: String): ContactDraft = ContactDraft(phones = listOf(ContactDraftPhone(0, number, ContactPhoneLabel.MOBILE)))
    }
}
