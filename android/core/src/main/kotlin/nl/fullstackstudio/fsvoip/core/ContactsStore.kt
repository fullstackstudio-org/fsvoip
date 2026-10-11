// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Local copy of the customer's address book, one file per paired account.
//
// - Lives in the app's private storage (not the Keystore: it can be tens of thousands of rows). The app does not allow backups
//   (`allowBackup=false`), so a customer's address book never reaches a new phone through a backup; it syncs again.
// - The file is plain JSON of [AccountContactsData]. It never holds a token or password.
// - Removing an account removes its file.

package nl.fullstackstudio.fsvoip.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

/** One phone number of a stored contact. The label is kept as the raw server string so an unknown future label survives a round trip. */
@Serializable
data class StoredContactPhone(
    val number: String,
    val label: String = "other",
    val isPrimary: Boolean = false,
) {
    /** The label as this app version understands it (`OTHER` for anything unknown). */
    val phoneLabel: ContactPhoneLabel get() = ContactPhoneLabel.fromWire(label) ?: ContactPhoneLabel.OTHER

    companion object {
        fun of(phone: ContactPhone) = StoredContactPhone(
            number = phone.number,
            label = if (phone.label.isKnown) phone.label.wire else ContactPhoneLabel.OTHER.wire,
            isPrimary = phone.isPrimary,
        )
    }
}

@Serializable
data class StoredContact(
    val id: String,
    val name: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val company: String? = null,
    val email: String? = null,
    val phones: List<StoredContactPhone> = emptyList(),
    val tags: List<String> = emptyList(),
    /** Opaque server timestamp; goes back as `expectedUpdatedAt`. */
    val updatedAt: String,
) {
    companion object {
        fun of(contact: Contact) = StoredContact(
            id = contact.id,
            name = contact.name,
            firstName = contact.firstName,
            lastName = contact.lastName,
            company = contact.company,
            email = contact.email,
            phones = contact.phones.map(StoredContactPhone::of),
            tags = contact.tags,
            updatedAt = contact.updatedAt,
        )
    }
}

/** A contact list as known on this phone: its version, which contacts are in it and whether the user wants it shown. */
@Serializable
data class StoredContactList(
    val id: String,
    val name: String,
    val version: Int,
    /** The ETag of the last snapshot we downloaded (`"3"`); `null` = never downloaded. */
    val etag: String? = null,
    /** Local choice (default on): a list that is off is neither downloaded nor offered as a filter. */
    val isEnabled: Boolean = true,
    /** Ids of the customer contacts in the list (from the snapshot). */
    val memberIds: List<String> = emptyList(),
    val contactCount: Int = 0,
)

/** Everything stored for one account. A field added in a later app version is missing in an older file and takes its default. */
@Serializable
data class AccountContactsData(
    val schema: Int = CURRENT_SCHEMA,
    /** The address book of this account is used (synced, shown, used for caller names). Default on. */
    val isEnabled: Boolean = true,
    /** `serverTime` of the last complete sync run: the next `since`. `null` = never synced, or a full sync is due. */
    val serverTime: String? = null,
    val lastSyncedAtMillis: Long? = null,
    val contacts: List<StoredContact> = emptyList(),
    val lists: List<StoredContactList> = emptyList(),
    /** What the server allowed at the last sync (`GET /me`). Delete is the restricted one: a missing value means no. */
    val canRead: Boolean = true,
    val canWrite: Boolean = true,
    val canDelete: Boolean = false,
) {
    companion object {
        const val CURRENT_SCHEMA = 1
    }
}

interface ContactsFileStoring {
    /** `null` = nothing stored (or an unreadable file: it is then treated as absent and a full sync follows). */
    fun load(accountId: String): AccountContactsData?

    fun save(data: AccountContactsData, accountId: String)

    fun remove(accountId: String)

    /** The account ids that have data (as file name stems, see [ContactsFileStore.fileNameStem]). */
    fun storedAccountIds(): List<String>
}

/** JSON files in [directory] (the app uses `filesDir/contacts`). */
class ContactsFileStore(private val directory: File, private val logger: FsLogger = FsLogger("contacts-store")) : ContactsFileStoring {
    private fun file(accountId: String) = File(directory, fileName(accountId))

    override fun load(accountId: String): AccountContactsData? {
        val file = file(accountId)

        if (!file.isFile) return null

        return try {
            FsJson.default.decodeFromString(AccountContactsData.serializer(), file.readText())
        } catch (_: Exception) {
            logger.error("Stored contacts of an account could not be read; a new sync will rebuild them")
            null
        }
    }

    @Synchronized
    override fun save(data: AccountContactsData, accountId: String) {
        directory.mkdirs()

        val target = file(accountId)
        val temporary = File(directory, "${target.name}.tmp")
        temporary.writeText(FsJson.default.encodeToString(AccountContactsData.serializer(), data))
        // Replace atomically: a crash halfway never leaves a half-written address book.
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    @Synchronized
    override fun remove(accountId: String) {
        file(accountId).delete()
    }

    override fun storedAccountIds(): List<String> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(".json") }?.map { it.name.removeSuffix(".json") } ?: emptyList()

    companion object {
        /** Account ids are server ids; keep only characters that are safe in a file name. */
        fun fileName(accountId: String): String {
            val safe = accountId.map { char -> if ((char.code < 128 && char.isLetterOrDigit()) || char == '-' || char == '_') char else '_' }.joinToString("")

            return "$safe.json"
        }

        /** The file name without `.json`, as [storedAccountIds] reports it. */
        fun fileNameStem(accountId: String): String = fileName(accountId).removeSuffix(".json")
    }
}

/** In-memory store for tests and demo mode. */
class InMemoryContactsStore : ContactsFileStoring {
    private val storage = ConcurrentHashMap<String, AccountContactsData>()

    override fun load(accountId: String): AccountContactsData? = storage[accountId]

    override fun save(data: AccountContactsData, accountId: String) {
        storage[accountId] = data
    }

    override fun remove(accountId: String) {
        storage.remove(accountId)
    }

    override fun storedAccountIds(): List<String> = storage.keys.toList()
}
