// SPDX-License-Identifier: AGPL-3.0-or-later
//
// What the app keeps of the customer's address book on this phone: the contacts, the lists with their members and the `since` of
// the next sync. One JSON file per account, in the app's private storage. Removing the account removes its file.

package nl.fullstackstudio.fsvoip.contacts

import java.io.File
import kotlinx.serialization.Serializable
import nl.fullstackstudio.fsvoip.core.Contact
import nl.fullstackstudio.fsvoip.core.ContactPhone
import nl.fullstackstudio.fsvoip.core.FsJson

@Serializable
data class StoredContact(
    val id: String,
    val name: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val company: String? = null,
    val email: String? = null,
    val phones: List<ContactPhone> = emptyList(),
    val tags: List<String> = emptyList(),
    /** Opaque: goes back as `expectedUpdatedAt` when editing. */
    val updatedAt: String,
) {
    constructor(contact: Contact) : this(
        contact.id, contact.name, contact.firstName, contact.lastName, contact.company, contact.email, contact.phones, contact.tags, contact.updatedAt,
    )

    /** The name to show: the name, else the company, else the first number. */
    val displayName: String
        get() = name.ifBlank { company.orEmpty() }.ifBlank { phones.firstOrNull()?.number.orEmpty() }
}

@Serializable
data class StoredContactList(
    val id: String,
    val name: String,
    /** The version of the downloaded members. */
    val version: Int,
    /** The `ETag` of the downloaded members (`"3"`); `null` = never downloaded. */
    val etag: String? = null,
    /** The user switched this list on (new lists start on). A list that is off is not downloaded. */
    val isEnabled: Boolean = true,
    val memberIds: List<String> = emptyList(),
    val contactCount: Int = 0,
)

@Serializable
data class AccountContactsData(
    val contacts: List<StoredContact> = emptyList(),
    val lists: List<StoredContactList> = emptyList(),
    /** The `serverTime` of the last complete run: the next `since`. `null` = never synced. */
    val since: String? = null,
    val lastSyncedAtMillis: Long? = null,
)

interface ContactsFileStoring {
    fun load(accountId: String): AccountContactsData?

    fun save(accountId: String, data: AccountContactsData)

    fun remove(accountId: String)
}

class InMemoryContactsStore : ContactsFileStoring {
    private val data = HashMap<String, AccountContactsData>()

    @Synchronized
    override fun load(accountId: String): AccountContactsData? = data[accountId]

    @Synchronized
    override fun save(accountId: String, data: AccountContactsData) {
        this.data[accountId] = data
    }

    @Synchronized
    override fun remove(accountId: String) {
        data.remove(accountId)
    }
}

/** One file per account (`contacts-<id>.json`), written atomically (temp file, then rename). */
class FileContactsStore(private val directory: File) : ContactsFileStoring {
    private fun file(accountId: String): File {
        // The account id is a uuid from the server; keep anything else out of a file name anyway.
        require(accountId.all { it.isLetterOrDigit() || it == '-' }) { "unsafe account id" }

        return File(directory, "contacts-$accountId.json")
    }

    @Synchronized
    override fun load(accountId: String): AccountContactsData? = try {
        file(accountId).takeIf { it.isFile }?.readText()?.let { FsJson.default.decodeFromString(AccountContactsData.serializer(), it) }
    } catch (_: Exception) {
        // Unreadable (a half-written file from a crash, a newer format): start again from the server.
        null
    }

    @Synchronized
    override fun save(accountId: String, data: AccountContactsData) {
        directory.mkdirs()
        val target = file(accountId)
        val temp = File(directory, target.name + ".tmp")
        temp.writeText(FsJson.default.encodeToString(AccountContactsData.serializer(), data))

        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    @Synchronized
    override fun remove(accountId: String) {
        file(accountId).delete()
    }
}
