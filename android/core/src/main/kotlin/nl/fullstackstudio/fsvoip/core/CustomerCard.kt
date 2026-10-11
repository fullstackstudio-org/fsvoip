// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The "customer card": who is calling, with a little context from the customer's website, and the timeline of a contact.
//
//   GET /contacts/lookup?number=      → [CallerLookup] (the contact of THIS customer with that number, counters, the last 10 lines)
//   GET /contacts/{id}/timeline       → [TimelinePage] (newest first, paged with an opaque cursor)
//
// The server only sends what the app may show: a title and an optional summary per line (no event data), a human reference
// (order / request number) and two counters. The app shows exactly that and nothing it derives itself.

package nl.fullstackstudio.fsvoip.core

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

@Serializable
data class CallerCounters(
    @Serializable(with = NonNegativeIntSerializer::class) val openRequests: Int = 0,
    @Serializable(with = NonNegativeIntSerializer::class) val openOrders: Int = 0,
)

/** The few fields of the contact the call screen needs (the full contact model belongs to the contacts feature). */
@Serializable
data class CallerContact(
    val id: String,
    val name: String = "",
    val company: String? = null,
)

/**
 * `GET /contacts/lookup?number=`. `contact == null` covers unknown, suppressed (privacy) and ambiguous (`ambiguous == true`:
 * several contacts share the number; the server does not guess and neither does the app).
 */
@Serializable
data class CallerLookup(
    val contact: CallerContact? = null,
    val ambiguous: Boolean = false,
    val doNotContact: Boolean = false,
    val counters: CallerCounters = CallerCounters(),
    val timeline: List<TimelineItem> = emptyList(),
)

/** What the call screen knows about the caller from the website of the customer. */
data class CallerContext(
    val contactId: String,
    val name: String,
    val company: String? = null,
    val openRequests: Int = 0,
    val openOrders: Int = 0,
) {
    val displayName: String? get() = name.ifEmpty { null }

    companion object {
        /** `null` when the server did not identify one contact (unknown, ambiguous or suppressed): then the app shows nothing extra. */
        fun from(lookup: CallerLookup): CallerContext? {
            val contact = lookup.contact ?: return null

            if (lookup.ambiguous) return null

            val name = contact.name.trim()
            val company = contact.company?.trim()

            return CallerContext(
                contactId = contact.id,
                name = name.ifEmpty { company ?: "" },
                company = company?.ifEmpty { null },
                openRequests = lookup.counters.openRequests,
                openOrders = lookup.counters.openOrders,
            )
        }
    }
}

/** What the app needs from the server for the customer card. [LiveCustomerCardService] is the implementation; tests use their own. */
interface CustomerCardServicing {
    suspend fun lookup(number: String, account: StoredAccount): CallerLookup

    suspend fun timeline(contactId: String, cursor: String?, limit: Int, account: StoredAccount): TimelinePage
}

class LiveCustomerCardService(private val api: FsVoipApiClient = FsVoipApiClient()) : CustomerCardServicing {
    override suspend fun lookup(number: String, account: StoredAccount): CallerLookup = api.authenticated(account.deviceToken).callerLookup(number)

    override suspend fun timeline(contactId: String, cursor: String?, limit: Int, account: StoredAccount): TimelinePage =
        api.authenticated(account.deviceToken).contactTimeline(contactId, cursor, limit)
}

/**
 * `GET /contacts/lookup?number=`. The number goes as given (an E.164 or a national number); the server normalises it. The request
 * has its own short time-out: the answer is only worth having within a second.
 */
suspend fun FsVoipApiClient.callerLookup(number: String): CallerLookup {
    val response = send("GET", "contacts/lookup", null, authenticated = true, query = listOf("number" to number), timeoutSeconds = 3)

    return decode("contacts/lookup", CallerLookup.serializer(), response)
}

/** Runs an operation but gives up after the time: the call must never wait for the website. */
object CallerLookupTimeout {
    /** The longest an incoming call waits for the customer card. */
    const val INCOMING_CALL_MILLIS = 1_000L

    private val detached = CoroutineScope(SupervisorJob())

    /**
     * `null` on a timeout or any error; the operation is cancelled in that case. The operation runs detached: a blocking
     * network call that does not notice the cancellation can never hold the caller back.
     */
    suspend fun <T : Any> run(timeoutMillis: Long, operation: suspend () -> T?): T? {
        val work = detached.async {
            try {
                operation()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
        }

        val result = try {
            withTimeoutOrNull(timeoutMillis.coerceAtLeast(0)) { work.await() }
        } catch (cancellation: CancellationException) {
            work.cancel()
            throw cancellation
        } catch (_: Exception) {
            null
        }

        work.cancel()

        return result
    }
}
