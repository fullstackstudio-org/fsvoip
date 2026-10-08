// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** One finished call in the local history. Stays on the phone; never uploaded. */
@Serializable
data class RecentCall(
    val id: String = UUID.randomUUID().toString(),
    val number: String,
    val name: String? = null,
    val accountId: String,
    val accountLabel: String,
    val direction: Direction,
    val outcome: Outcome,
    /** Epoch milliseconds. */
    val startedAtMillis: Long,
    /** Seconds connected (0 when the call was never answered). */
    val durationSeconds: Long,
) {
    @Serializable
    enum class Direction { INCOMING, OUTGOING }

    @Serializable
    enum class Outcome {
        /** The call was connected. */
        ANSWERED,

        /** Incoming and not answered here. */
        MISSED,

        /** Incoming and declined here. */
        DECLINED,

        /** Outgoing and not answered, busy, or ended before it was answered. */
        NOT_ANSWERED,
        FAILED,
    }
}

/** Local call history, newest first, at most [limit] entries. */
class RecentCallsStore(
    private val store: KeyValueStore,
    private val key: String = "fsvoip.recent-calls",
    private val limit: Int = DEFAULT_LIMIT,
) {
    private val lock = Any()

    fun all(): List<RecentCall> = synchronized(lock) { load() }

    fun add(call: RecentCall) = synchronized(lock) { save((listOf(call) + load()).take(limit)) }

    fun remove(accountId: String) = synchronized(lock) { save(load().filter { it.accountId != accountId }) }

    fun clear() = synchronized(lock) { save(emptyList()) }

    private fun load(): List<RecentCall> {
        val text = store.getString(key) ?: return emptyList()

        return runCatching { FsJson.default.decodeFromString(ListSerializer(RecentCall.serializer()), text) }.getOrDefault(emptyList())
    }

    private fun save(calls: List<RecentCall>) {
        store.putString(key, FsJson.default.encodeToString(ListSerializer(RecentCall.serializer()), calls))
    }

    companion object {
        const val DEFAULT_LIMIT = 200
    }
}
