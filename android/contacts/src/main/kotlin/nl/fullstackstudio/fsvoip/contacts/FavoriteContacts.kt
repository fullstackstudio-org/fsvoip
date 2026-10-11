// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.contacts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.fullstackstudio.fsvoip.core.KeyValueStore

/** The favourites of this phone: a set of contact entry ids kept in the preferences. They never leave the phone. */
class FavoriteContacts(private val store: KeyValueStore) {
    private val _ids = MutableStateFlow(read())

    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    fun contains(id: String): Boolean = id in _ids.value

    @Synchronized
    fun toggle(id: String) {
        val next = if (id in _ids.value) _ids.value - id else _ids.value + id

        _ids.value = next
        store.putString(KEY, next.sorted().joinToString("\n").ifEmpty { null })
    }

    private fun read(): Set<String> = store.getString(KEY)?.split('\n')?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    companion object {
        const val KEY = "contacts.favorites"
    }
}
