// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.contacts

import java.util.UUID
import nl.fullstackstudio.fsvoip.core.Change
import nl.fullstackstudio.fsvoip.core.ContactCreate
import nl.fullstackstudio.fsvoip.core.ContactDetail
import nl.fullstackstudio.fsvoip.core.ContactPhone
import nl.fullstackstudio.fsvoip.core.ContactPhoneLabel
import nl.fullstackstudio.fsvoip.core.ContactUpdate

/** A contact as the edit screen holds it, and the request bodies built from it. */
data class ContactDraft(
    val firstName: String = "",
    val lastName: String = "",
    val company: String = "",
    val email: String = "",
    val notes: String = "",
    val phones: List<Phone> = listOf(Phone()),
    val listIds: Set<String> = emptySet(),
) {
    data class Phone(
        val id: String = UUID.randomUUID().toString(),
        val number: String = "",
        val label: ContactPhoneLabel = ContactPhoneLabel.MOBILE,
    )

    /** The display name sent as `name`: first and last name, otherwise the company. */
    val displayName: String
        get() {
            val person = listOf(firstName, lastName).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

            return person.ifEmpty { company.trim() }
        }

    /** Rows the user left empty do not count. */
    val filledPhones: List<Phone> get() = phones.filter { it.number.trim().isNotEmpty() }

    val hasEmail: Boolean get() = email.trim().isNotEmpty()

    /** A contact needs a name and a way to reach it (the server refuses a contact with neither number nor e-mail). */
    val canSave: Boolean get() = displayName.isNotEmpty() && (filledPhones.isNotEmpty() || hasEmail)

    /** Numbers go to the server in the `+31...` form when they can be written that way; short numbers stay as typed. */
    internal fun requestPhones(): List<ContactPhone> =
        filledPhones.mapIndexed { index, row ->
            val typed = row.number.trim()

            ContactPhone(PhoneNumberMatcher.e164(typed) ?: typed, if (row.label.isKnown) row.label else ContactPhoneLabel.OTHER, isPrimary = index == 0)
        }

    private fun text(value: String): String? = value.trim().takeIf { it.isNotEmpty() }

    private fun keepOrSet(value: String): Change<String> = text(value)?.let { Change.Set(it) } ?: Change.Keep

    fun createRequest(): ContactCreate = ContactCreate(
        name = displayName,
        firstName = keepOrSet(firstName),
        lastName = keepOrSet(lastName),
        company = keepOrSet(company),
        email = keepOrSet(email),
        notes = keepOrSet(notes),
        phones = requestPhones(),
        listIds = listIds.takeIf { it.isNotEmpty() }?.sorted(),
    )

    /**
     * Every field is sent (an emptied field clears it); phones and lists replace the whole set. [expectedUpdatedAt] is the
     * `updatedAt` the screen was opened with.
     */
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

    companion object {
        fun from(detail: ContactDetail): ContactDraft {
            val contact = detail.contact
            val hasParts = !contact.firstName.isNullOrEmpty() || !contact.lastName.isNullOrEmpty()
            var first = contact.firstName ?: ""

            // A contact that only has a display name ("Bakkerij Smit"): keep it as it is on the first line, unless it is the company.
            if (!hasParts && contact.name.isNotEmpty() && contact.name != contact.company) first = contact.name

            val phones = contact.phones.map { Phone(number = it.number, label = if (it.label.isKnown) it.label else ContactPhoneLabel.OTHER) }

            return ContactDraft(
                firstName = first,
                lastName = contact.lastName ?: "",
                company = contact.company ?: "",
                email = contact.email ?: "",
                notes = detail.notes ?: "",
                phones = phones.ifEmpty { listOf(Phone()) },
                listIds = detail.listIds.toSet(),
            )
        }

        fun from(entry: ContactEntry): ContactDraft {
            var first = ""
            var last = ""

            if (entry.name != entry.company) {
                val space = entry.name.indexOf(' ')

                if (space < 0) {
                    first = entry.name
                } else {
                    first = entry.name.substring(0, space)
                    last = entry.name.substring(space + 1).trimStart()
                }
            }

            val phones = entry.phones.map { Phone(number = it.number, label = if (it.label.isKnown) it.label else ContactPhoneLabel.OTHER) }

            return ContactDraft(firstName = first, lastName = last, company = entry.company ?: "", email = entry.email ?: "", phones = phones.ifEmpty { listOf(Phone()) })
        }
    }
}
