// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable

sealed class SecretStoreException(message: String) : Exception(message) {
    /** The Android Keystore refused (rare: a broken keystore after an OS update, or no key yet). */
    class Keystore(detail: String) : SecretStoreException(detail)

    class Corrupt : SecretStoreException("corrupt")
}

/** Minimal key/value store for secrets. The production implementation encrypts with an Android Keystore key. */
interface SecretStore {
    fun set(key: String, data: ByteArray)
    fun get(key: String): ByteArray?
    fun remove(key: String)
    fun allKeys(): List<String>
}

/**
 * Values encrypted with AES-256-GCM under a key that lives in the Android Keystore (never exportable), stored as
 * `base64(iv || ciphertext)` in a private `SharedPreferences` file. The key name is the AAD, so a value copied under
 * another key does not decrypt.
 *
 * The key needs no user authentication: a push must be able to register the account while the phone is locked. The
 * file is in credential-encrypted storage, so it is readable after the first unlock since boot (like iOS'
 * "after first unlock, this device only"). Backups are off in the manifest: a restore on another phone pairs again.
 */
class KeystoreSecretStore(
    private val storage: KeyValueStore,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : SecretStore {
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )

        return generator.generateKey()
    }

    override fun set(key: String, data: ByteArray) {
        val sealed = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
            cipher.iv + cipher.doFinal(data)
        } catch (error: Exception) {
            throw SecretStoreException.Keystore(error.javaClass.simpleName)
        }

        storage.putString(PREFIX + key, Base64.encodeToString(sealed, Base64.NO_WRAP))
    }

    override fun get(key: String): ByteArray? {
        val text = storage.getString(PREFIX + key) ?: return null
        val sealed = Base64.decode(text, Base64.NO_WRAP)

        if (sealed.size <= IV_LENGTH) {
            throw SecretStoreException.Corrupt()
        }

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_LENGTH))
            cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
            cipher.doFinal(sealed, IV_LENGTH, sealed.size - IV_LENGTH)
        } catch (error: Exception) {
            throw SecretStoreException.Corrupt()
        }
    }

    override fun remove(key: String) {
        storage.putString(PREFIX + key, null)
    }

    override fun allKeys(): List<String> = storage.keys().filter { it.startsWith(PREFIX) }.map { it.removePrefix(PREFIX) }

    companion object {
        const val DEFAULT_KEY_ALIAS = "nl.fullstackstudio.fsvoip.secrets"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128
        private const val PREFIX = "s."
    }
}

/** In-memory store for tests and previews. Never use it in the shipping app. */
class InMemorySecretStore : SecretStore {
    private val storage = ConcurrentHashMap<String, ByteArray>()

    override fun set(key: String, data: ByteArray) {
        storage[key] = data.copyOf()
    }

    override fun get(key: String): ByteArray? = storage[key]?.copyOf()

    override fun remove(key: String) {
        storage.remove(key)
    }

    override fun allKeys(): List<String> = storage.keys.toList()
}

/** Everything the app keeps about one paired account. The whole record is encrypted: it holds the SIP password and the device token. */
@Serializable
data class StoredAccount(
    /** `AccountInfo.id` (the app pairing id). */
    val id: String,
    val label: String,
    val labelOverride: String? = null,
    val pbxName: String,
    val extensionName: String,
    val extensionNumber: String? = null,
    val customerName: String,
    val deviceToken: Secret,
    val installId: String,
    val sip: SipCredentials,
    /** Epoch milliseconds. */
    val pairedAtMillis: Long,
) {
    val pairedAt: Instant
        get() = Instant.ofEpochMilli(pairedAtMillis)

    /** What the app shows for this account: the alias if set, otherwise the server label. */
    val displayLabel: String
        get() = labelOverride?.takeIf { it.isNotEmpty() } ?: label

    /**
     * The account after `GET /me`: labels and the server side of the SIP settings follow the server; the username,
     * password and device token stay (the server never sends them again). `sip == null` keeps the stored server.
     */
    fun updatedWith(me: MeResponse): StoredAccount {
        val server = me.sip

        return copy(
            label = me.account.label,
            labelOverride = me.account.labelOverride,
            pbxName = me.account.pbxName,
            extensionName = me.account.extensionName,
            extensionNumber = me.account.extensionNumber ?: extensionNumber,
            customerName = me.account.customerName,
            sip = if (server == null) sip else sip.copy(
                domain = server.domain,
                proxy = server.proxy,
                port = server.port,
                transport = server.transport,
                srv = server.srv,
            ),
        )
    }

    override fun toString(): String = "StoredAccount(id=$id, label=$label)"

    companion object {
        /** Build the record from a successful `POST /pair`. */
        fun fromPairing(response: PairResponse, pairedAt: Instant = Instant.now()): StoredAccount = StoredAccount(
            id = response.account.id,
            label = response.account.label,
            labelOverride = response.account.labelOverride,
            pbxName = response.account.pbxName,
            extensionName = response.account.extensionName,
            extensionNumber = response.account.extensionNumber,
            customerName = response.account.customerName,
            deviceToken = response.deviceToken,
            installId = response.device.installId,
            sip = response.sip,
            pairedAtMillis = pairedAt.toEpochMilli(),
        )
    }
}

/** The paired accounts of this installation (several accounts in one app). */
class AccountStore(private val secrets: SecretStore) {
    fun save(account: StoredAccount) {
        secrets.set(PREFIX + account.id, FsJson.default.encodeToString(StoredAccount.serializer(), account).toByteArray(Charsets.UTF_8))
    }

    fun account(id: String): StoredAccount? {
        val data = secrets.get(PREFIX + id) ?: return null

        return try {
            FsJson.default.decodeFromString(StoredAccount.serializer(), data.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            throw SecretStoreException.Corrupt()
        }
    }

    /** All accounts, oldest pairing first. A corrupt item is skipped, not fatal. */
    fun accounts(): List<StoredAccount> = secrets.allKeys()
        .filter { it.startsWith(PREFIX) }
        .mapNotNull { runCatching { account(it.removePrefix(PREFIX)) }.getOrNull() }
        .sortedBy { it.pairedAtMillis }

    fun remove(id: String) = secrets.remove(PREFIX + id)

    private companion object {
        const val PREFIX = "account."
    }
}
