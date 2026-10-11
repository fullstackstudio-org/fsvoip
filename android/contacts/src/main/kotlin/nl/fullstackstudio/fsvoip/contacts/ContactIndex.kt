// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.contacts

/**
 * An in-memory index from phone number to contact: one map lookup, no scanning (a caller's name must be there within 50 ms
 * even for 25,000 contacts).
 *
 * Entries are given in priority order; for one number the first entry wins.
 */
class ContactIndex(private val entries: List<ContactEntry>) {
    private class TailCandidate(val entryIndex: Int, val isAssumed: Boolean)

    private val exact = HashMap<String, Int>()
    private val tails = HashMap<String, MutableList<TailCandidate>>()

    init {
        entries.forEachIndexed { index, entry ->
            for (phone in entry.phones) {
                val key = PhoneNumberMatcher.key(phone.number) ?: continue

                if (!exact.containsKey(key.exact)) exact[key.exact] = index

                key.tail?.let { tails.getOrPut(it) { ArrayList(1) }.add(TailCandidate(index, key.isCountryAssumed)) }
            }
        }
    }

    val count: Int get() = entries.size

    fun entry(number: String): ContactEntry? {
        val key = PhoneNumberMatcher.key(number) ?: return null

        exact[key.exact]?.let { return entries[it] }

        // Fallback on the last 9 digits. Only a subscriber number gets here, and only when every candidate is the same person:
        // two different people with the same last digits (another country, another operator) answer "unknown".
        val tail = key.tail ?: return null
        val candidates = tails[tail] ?: return null

        // A number that STATES its country (`+32...`) may only fall back on stored numbers whose country was assumed too.
        val usable = candidates.filter { key.isCountryAssumed || it.isAssumed }
        val names = usable.map { entries[it.entryIndex].displayName.lowercase() }.toSet()

        if (names.size != 1) return null

        return entries[usable.minOf { it.entryIndex }]
    }

    fun name(number: String): String? = entry(number)?.displayName?.takeIf { it.isNotEmpty() }

    companion object {
        val EMPTY = ContactIndex(emptyList())
    }
}
