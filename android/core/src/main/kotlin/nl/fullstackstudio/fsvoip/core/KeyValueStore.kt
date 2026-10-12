// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/** A tiny string key/value store: `SharedPreferences` in the app, a map in tests. Not for secrets on its own. */
interface KeyValueStore {
    fun getString(key: String): String?

    /** `null` removes the key. */
    fun putString(key: String, value: String?)

    fun keys(): Set<String>
}

class SharedPreferencesStore(private val preferences: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String?) {
        // `commit`: the value must be on disk before a push handler that started the process returns.
        preferences.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit()
    }

    override fun keys(): Set<String> = preferences.all.keys
}

class InMemoryKeyValueStore : KeyValueStore {
    private val values = ConcurrentHashMap<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }

    override fun keys(): Set<String> = values.keys.toSet()
}
