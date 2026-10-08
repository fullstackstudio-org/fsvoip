// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageTest {
    private fun paired(id: String = "3f0c2b1e-8a4d-4d6f-9b7a-1c2d3e4f5a6b", at: Long = 1000): StoredAccount {
        val response = FsJson.default.decodeFromString(PairResponse.serializer(), Fixtures.text("pair-response.json"))
        return StoredAccount.fromPairing(response, Instant.ofEpochMilli(at)).copy(id = id)
    }

    @Test
    fun accountsRoundTripOldestFirst() {
        val store = AccountStore(InMemorySecretStore())
        store.save(paired("b", at = 2000))
        store.save(paired("a", at = 1000))

        val accounts = store.accounts()
        assertEquals(listOf("a", "b"), accounts.map { it.id })
        assertEquals("fixture-not-a-real-password", accounts.first().sip.password.reveal())
        assertTrue(accounts.first().deviceToken.reveal().startsWith("fss_vapp_"))

        store.remove("a")
        assertEquals(listOf("b"), store.accounts().map { it.id })
        assertNull(store.account("a"))
    }

    @Test
    fun corruptItemsAreSkipped() {
        val secrets = InMemorySecretStore()
        val store = AccountStore(secrets)
        store.save(paired("a"))
        secrets.set("account.broken", "nope".toByteArray())

        assertEquals(listOf("a"), store.accounts().map { it.id })
    }

    @Test
    fun displayLabelAndMeUpdate() {
        val account = paired()
        assertEquals("Voorbeeld Bouw · Jan de Vries", account.displayLabel)

        val me = FsJson.default.decodeFromString(MeResponse.serializer(), Fixtures.text("me-response.json"))
        val updated = account.updatedWith(me)
        assertEquals("Jan (balie)", updated.displayLabel)
        // The secret parts stay.
        assertEquals(account.sip.password, updated.sip.password)
        assertEquals(account.deviceToken, updated.deviceToken)

        val noSip = FsJson.default.decodeFromString(MeResponse.serializer(), Fixtures.text("me-response-no-sip.json"))
        assertEquals(account.sip, account.updatedWith(noSip).sip)
    }

    @Test
    fun printingNeverRevealsSecrets() {
        val account = paired()
        assertFalse(account.toString().contains("fixture-not-a-real-password"))
        assertFalse(account.sip.toString().contains("fixture-not-a-real-password"))
        assertFalse(account.deviceToken.toString().contains("fss_vapp_"))
    }

    @Test
    fun installIdentityIsCreatedOnceAndRepaired() {
        val store = InMemorySecretStore()
        val first = InstallIdentity.load(store) { byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8) }
        assertEquals("0102030405060708", first.installId)
        assertEquals(first, InstallIdentity.load(store))

        store.set(InstallIdentity.INSTALL_ID_KEY, "not hex!".toByteArray())
        val repaired = InstallIdentity.load(store) { ByteArray(8) { 0xab.toByte() } }
        assertEquals("abababababababab", repaired.installId)
        assertEquals(first.sipInstanceId, repaired.sipInstanceId)
    }

    @Test
    fun preferencesAndRecents() {
        val prefs = KeyValuePreferencesStore(InMemoryKeyValueStore())
        assertTrue(prefs.preferences("a").effectiveShowCalledAccount(2))
        assertFalse(prefs.preferences("a").effectiveShowCalledAccount(1))
        prefs.setPreferences(AccountPreferences(showCalledAccount = false), "a")
        prefs.defaultOutgoingAccountId = "a"
        assertFalse(prefs.preferences("a").effectiveShowCalledAccount(3))
        prefs.removePreferences("a")
        assertNull(prefs.defaultOutgoingAccountId)
        assertNull(prefs.preferences("a").showCalledAccount)

        val recents = RecentCallsStore(InMemoryKeyValueStore(), limit = 2)
        fun call(n: String, account: String = "a") = RecentCall(number = n, accountId = account, accountLabel = "A", direction = RecentCall.Direction.OUTGOING, outcome = RecentCall.Outcome.ANSWERED, startedAtMillis = 0, durationSeconds = 1)
        recents.add(call("1"))
        recents.add(call("2", "b"))
        recents.add(call("3"))
        assertEquals(listOf("3", "2"), recents.all().map { it.number })
        recents.remove("a")
        assertEquals(listOf("2"), recents.all().map { it.number })
    }
}
