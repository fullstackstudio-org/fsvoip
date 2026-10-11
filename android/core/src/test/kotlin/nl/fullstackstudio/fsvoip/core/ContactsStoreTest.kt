// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContactsStoreTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = File(System.getProperty("java.io.tmpdir"), "fsvoip-contacts-${java.util.UUID.randomUUID()}")
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun sample() = AccountContactsData(
        serverTime = "2026-10-09T10:00:00.000Z",
        lastSyncedAtMillis = 1_791_549_000_000,
        contacts = listOf(
            StoredContact(
                id = "c1",
                name = "Pieter de Groot",
                firstName = "Pieter",
                lastName = "de Groot",
                email = "p@example.nl",
                phones = listOf(StoredContactPhone("+31612345678", "mobile", true)),
                tags = listOf("klant"),
                updatedAt = "2026-10-09T09:59:59.123Z",
            ),
        ),
        lists = listOf(StoredContactList("l1", "Klanten", 3, "\"3\"", true, listOf("c1"), 1)),
        canRead = true,
        canWrite = true,
        canDelete = true,
    )

    @Test
    fun roundTripKeepsEverythingAndTheOpaqueTimestamps() {
        val store = ContactsFileStore(directory)

        store.save(sample(), "acc-1")

        assertEquals(sample(), store.load("acc-1"))
        assertEquals("2026-10-09T09:59:59.123Z", store.load("acc-1")?.contacts?.first()?.updatedAt)
    }

    @Test
    fun accountsAreSeparateAndRemoveDeletesOnlyThatFile() {
        val store = ContactsFileStore(directory)
        store.save(sample(), "acc-1")
        store.save(AccountContactsData(), "acc-2")

        assertEquals(setOf("acc-1", "acc-2"), store.storedAccountIds().toSet())

        store.remove("acc-1")

        assertNull(store.load("acc-1"))
        assertNotNull(store.load("acc-2"))
    }

    @Test
    fun unsafeAccountIdsCannotEscapeTheDirectory() {
        val store = ContactsFileStore(directory)

        store.save(sample(), "../../etc/passwd")

        assertEquals(1, store.storedAccountIds().size)
        assertFalse(store.storedAccountIds()[0].contains("/"))
        assertNotNull(store.load("../../etc/passwd"))
        assertEquals(ContactsFileStore.fileNameStem("../../etc/passwd"), store.storedAccountIds()[0])
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test
    fun anUnreadableFileIsTreatedAsAbsent() {
        val store = ContactsFileStore(directory)
        directory.mkdirs()
        File(directory, "acc-1.json").writeText("not json")

        assertNull(store.load("acc-1"))
    }

    @Test
    fun anOlderFileWithFewerFieldsStillLoads() {
        val store = ContactsFileStore(directory)
        directory.mkdirs()
        File(directory, "acc-1.json").writeText("""{"contacts":[{"id":"c1","name":"A","phones":[],"tags":[],"updatedAt":"x"}]}""")

        val loaded = store.load("acc-1")!!
        assertEquals(listOf("c1"), loaded.contacts.map { it.id })
        assertTrue(loaded.isEnabled)
        assertTrue(loaded.canWrite)
        assertFalse("deleting is the restricted right: a missing value means no", loaded.canDelete)
    }

    @Test
    fun aSaveLeavesNoTemporaryFileBehind() {
        val store = ContactsFileStore(directory)

        store.save(sample(), "acc-1")
        store.save(sample().copy(serverTime = "later"), "acc-1")

        assertEquals(listOf("acc-1.json"), directory.list()!!.toList())
        assertEquals("later", store.load("acc-1")?.serverTime)
    }

    @Test
    fun anUnknownPhoneLabelSurvivesTheStore() {
        val phone = StoredContactPhone("+31612345678", "pager")

        assertEquals(ContactPhoneLabel.OTHER, phone.phoneLabel)

        val data = FsJson.default.encodeToString(StoredContactPhone.serializer(), phone)
        assertEquals("pager", FsJson.default.decodeFromString(StoredContactPhone.serializer(), data).label)
        assertEquals("other", StoredContactPhone.of(ContactPhone("+31612345678", ContactPhoneLabel.UNKNOWN)).label)
    }
}
