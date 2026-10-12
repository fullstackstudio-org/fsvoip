// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The customer card: who is calling according to the website of the customer (`GET /contacts/lookup`), and the activity of one
// contact (`GET /contacts/{id}/timeline`). Mirror of the iOS `CustomerCardServicing` / `TimelineModel`.

package nl.fullstackstudio.fsvoip.core

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the app needs from the server for the customer card. [LiveCustomerCardService] is the implementation; tests bring their own. */
interface CustomerCardServicing {
    suspend fun lookup(number: String, account: StoredAccount): ContactLookup

    suspend fun timeline(contactId: String, cursor: String?, account: StoredAccount): ContactTimelinePage
}

class LiveCustomerCardService(private val api: FsVoipApiClient) : CustomerCardServicing {
    override suspend fun lookup(number: String, account: StoredAccount): ContactLookup = api.authenticated(account.deviceToken).lookupContact(number)

    override suspend fun timeline(contactId: String, cursor: String?, account: StoredAccount): ContactTimelinePage =
        api.authenticated(account.deviceToken).contactTimeline(contactId, cursor)
}

/** The line on the call screen. Pure, so it is unit tested; only what the server counted is shown. */
object CallerLine {
    /** What to say: `null` = nothing; no counts = just that the caller is known. */
    data class Text(val openOrders: Int, val openRequests: Int) {
        val hasCounts: Boolean
            get() = openOrders > 0 || openRequests > 0
    }

    fun of(context: CallerContext?): Text? = context?.let { Text(it.openOrders, it.openRequests) }
}

/** An icon family per kind the server may send; an unknown kind gets a neutral dot. */
enum class TimelineSymbol { ORDER, REQUEST, CALL, APPOINTMENT, FORM, MAIL, SMS, INVOICE, OTHER;

    companion object {
        fun of(kind: String): TimelineSymbol {
            val key = kind.lowercase()

            return when {
                "order" in key -> ORDER
                "ticket" in key || "request" in key -> REQUEST
                "call" in key || "voice" in key -> CALL
                "appointment" in key -> APPOINTMENT
                "form" in key || "submission" in key -> FORM
                "mail" in key -> MAIL
                "sms" in key -> SMS
                "invoice" in key || "payment" in key -> INVOICE
                else -> OTHER
            }
        }
    }
}

/** "ref · date" under a line of the timeline; `occurredAt` is an opaque timestamp that may not parse (then it is left out). */
fun timelineSubtitle(item: TimelineItem, format: (Instant) -> String): String =
    listOfNotNull(item.ref, item.occurredAt?.let(FsJson::parseTimestamp)?.let(format)).filter { it.isNotEmpty() }.joinToString(" · ")

/** The paged timeline of one contact. The first page loads once; "Meer laden" follows the cursor. A failure keeps what is on screen. */
class TimelineModel(
    private val service: CustomerCardServicing,
    private val account: StoredAccount,
    private val contactId: String,
) {
    enum class Phase { IDLE, LOADING, FAILED }

    data class State(
        val items: List<TimelineItem> = emptyList(),
        val phase: Phase = Phase.IDLE,
        val nextCursor: String? = null,
        val hasLoaded: Boolean = false,
    ) {
        val canLoadMore: Boolean
            get() = nextCursor != null && phase != Phase.LOADING
    }

    private val lock = Mutex()
    private val _state = MutableStateFlow(State())

    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun loadFirstPage() {
        if (_state.value.hasLoaded || _state.value.phase == Phase.LOADING) return

        load(null)
    }

    suspend fun loadMore() {
        val cursor = _state.value.nextCursor ?: return

        if (_state.value.phase == Phase.LOADING) return

        load(cursor)
    }

    /** The retry after a failure: more if the first page is there, else the first page. */
    suspend fun retry() {
        if (_state.value.hasLoaded) loadMore() else load(null)
    }

    private suspend fun load(cursor: String?) = lock.withLock {
        _state.value = _state.value.copy(phase = Phase.LOADING)

        try {
            val page = service.timeline(contactId, cursor, account)
            val seen = _state.value.items.mapTo(HashSet()) { it.id }

            // The cursor can overlap a little: an id is shown once.
            _state.value = _state.value.copy(
                items = _state.value.items + page.items.filter { seen.add(it.id) },
                nextCursor = page.nextCursor,
                hasLoaded = true,
                phase = Phase.IDLE,
            )
        } catch (error: CancellationException) {
            _state.value = _state.value.copy(phase = Phase.IDLE)
            throw error
        } catch (error: Exception) {
            _state.value = _state.value.copy(phase = Phase.FAILED)
        }
    }
}
