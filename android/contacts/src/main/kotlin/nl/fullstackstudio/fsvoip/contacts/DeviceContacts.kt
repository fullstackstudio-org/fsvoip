// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The phone's own contacts, read only and only with the contacts permission AND the user's switch ("Namen uit je contacten").
// They are shown in the "Telefoon" part of the Contacten tab and used to show a caller's name. They stay on the phone: nothing of
// them is stored by the app or sent anywhere.

package nl.fullstackstudio.fsvoip.contacts

import android.content.Context
import android.provider.ContactsContract
import nl.fullstackstudio.fsvoip.core.ContactPhoneLabel

class DeviceContactsReader(context: Context, private val provider: DeviceContactsProvider = DeviceContactsProvider(context)) {
    private val context = context.applicationContext

    val hasPermission: Boolean
        get() = provider.hasPermission

    /** All contacts of the phone that have a number, by name. At most [limit]; empty without the permission. */
    fun readAll(limit: Int = 5_000): List<ContactEntry> {
        if (!hasPermission) return emptyList()

        val byContact = LinkedHashMap<Long, MutableList<ContactEntryPhone>>()
        val names = HashMap<Long, String>()

        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
            )

            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC",
            )?.use { cursor ->
                while (cursor.moveToNext() && byContact.size <= limit) {
                    val id = cursor.getLong(0)
                    val number = cursor.getString(2)?.takeIf { it.isNotBlank() } ?: continue

                    names.putIfAbsent(id, cursor.getString(1).orEmpty())
                    byContact.getOrPut(id) { mutableListOf() }.add(ContactEntryPhone(number, label(cursor.getInt(3))))
                }
            }
        } catch (_: Exception) {
            // A provider that goes away or refuses is "no contacts", never a crash.
        }

        return byContact.map { (id, phones) ->
            ContactEntry(id = "device:$id", name = names[id].orEmpty(), phones = phones.distinctBy { it.number }, source = ContactSource.Device)
        }.take(limit)
    }

    private fun label(type: Int): ContactPhoneLabel = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> ContactPhoneLabel.MOBILE
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK, ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE -> ContactPhoneLabel.WORK
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> ContactPhoneLabel.HOME
        ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> ContactPhoneLabel.MAIN
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK, ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME -> ContactPhoneLabel.FAX
        else -> ContactPhoneLabel.OTHER
    }
}
