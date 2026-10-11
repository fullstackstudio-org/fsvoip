// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.fullstackstudio.fsvoip.core.ContactPhoneLabel

enum class DeviceContactsAccess {
    /** The user was not asked yet (or denied once: Android does not tell the difference). */
    NOT_DETERMINED,

    /** Refused for good: only the system settings can change that. Set by the screens after a refused question. */
    DENIED,

    AUTHORIZED,
}

/** The phone's own contacts. Read only; FSVoip never stores them and never sends them anywhere. */
interface DeviceContactsReading {
    val access: DeviceContactsAccess

    /** Whether contacts may be read. A screen asks the system question itself (it needs an activity); this just looks again. */
    suspend fun requestAccess(): Boolean

    suspend fun fetch(): List<ContactEntry>
}

/** Used where the phone's contacts do not exist (tests, previews). */
object NoDeviceContacts : DeviceContactsReading {
    override val access = DeviceContactsAccess.DENIED

    override suspend fun requestAccess() = false

    override suspend fun fetch(): List<ContactEntry> = emptyList()
}

/** Reads `ContactsContract` (needs `READ_CONTACTS`). One entry per phone contact that has a number; numbers of one contact stay together. */
class SystemDeviceContacts(context: Context) : DeviceContactsReading {
    private val context = context.applicationContext

    override val access: DeviceContactsAccess
        get() = if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) DeviceContactsAccess.AUTHORIZED else DeviceContactsAccess.NOT_DETERMINED

    override suspend fun requestAccess(): Boolean = access == DeviceContactsAccess.AUTHORIZED

    override suspend fun fetch(): List<ContactEntry> = withContext(Dispatchers.IO) { read() }

    private fun read(): List<ContactEntry> {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
        )
        val names = LinkedHashMap<Long, String>()
        val phones = LinkedHashMap<Long, MutableList<ContactPhoneEntry>>()

        context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val number = cursor.getString(2)?.trim().orEmpty()

                if (number.isEmpty()) continue

                names.getOrPut(id) { cursor.getString(1)?.trim().orEmpty() }
                phones.getOrPut(id) { mutableListOf() } += ContactPhoneEntry(number, label(cursor.getInt(3)))
            }
        }

        return phones.map { (id, list) ->
            ContactEntry(id = "device:$id", name = names[id].orEmpty(), phones = list.distinctBy { it.number }, source = ContactSource.Device)
        }
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
