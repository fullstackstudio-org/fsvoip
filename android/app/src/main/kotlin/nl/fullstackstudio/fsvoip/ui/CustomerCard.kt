// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The customer card: the line on the call screen ("Klant in website: 2 open bestellingen, 1 open verzoek") and the timeline of a
// contact ("Activiteit"). The Android port of the iOS `CustomerCard.swift`.

package nl.fullstackstudio.fsvoip.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Euro
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.CallerContext
import nl.fullstackstudio.fsvoip.core.CustomerCardServicing
import nl.fullstackstudio.fsvoip.core.StoredAccount
import nl.fullstackstudio.fsvoip.core.TimelineItem

/** The text on the call screen. Pure, so it is unit tested; only what the server counted is shown. */
object CallerLine {
    /** `null` = nothing to say. A recognised contact without open items still says "Klant in website". */
    fun text(context: CallerContext?, string: (id: Int, count: Int?, text: String?) -> String): String? {
        context ?: return null

        val parts = buildList {
            if (context.openOrders > 0) add(string(if (context.openOrders == 1) R.string.callcard_orders_one else R.string.callcard_orders_other, context.openOrders, null))
            if (context.openRequests > 0) add(string(if (context.openRequests == 1) R.string.callcard_requests_one else R.string.callcard_requests_other, context.openRequests, null))
        }

        if (parts.isEmpty()) return string(R.string.callcard_known, null, null)

        return string(R.string.callcard_prefix, null, parts.joinToString(", "))
    }

    /** With real resources. */
    fun text(context: CallerContext?, android: android.content.Context): String? = text(context) { id, count, text ->
        android.getString(id, *listOfNotNull<Any>(count, text).toTypedArray())
    }
}

/** The paged timeline of one contact. The first page loads once; "Meer laden" follows the cursor. A failure keeps what is on screen. */
class TimelineModel(
    private val service: CustomerCardServicing,
    private val account: StoredAccount,
    private val contactId: String,
    private val pageSize: Int = 20,
) {
    enum class Phase { IDLE, LOADING, FAILED }

    private val _items = MutableStateFlow<List<TimelineItem>>(emptyList())
    private val _phase = MutableStateFlow(Phase.IDLE)
    private val _nextCursor = MutableStateFlow<String?>(null)
    private val _hasLoaded = MutableStateFlow(false)

    val items: StateFlow<List<TimelineItem>> = _items.asStateFlow()
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    val nextCursor: StateFlow<String?> = _nextCursor.asStateFlow()
    val hasLoaded: StateFlow<Boolean> = _hasLoaded.asStateFlow()

    val canLoadMore: Boolean get() = _nextCursor.value != null && _phase.value != Phase.LOADING

    suspend fun loadFirstPage() {
        if (_hasLoaded.value || _phase.value == Phase.LOADING) return

        load(cursor = null)
    }

    suspend fun loadMore() {
        val cursor = _nextCursor.value ?: return

        if (_phase.value == Phase.LOADING) return

        load(cursor)
    }

    private suspend fun load(cursor: String?) {
        _phase.value = Phase.LOADING

        try {
            val page = service.timeline(contactId, cursor, pageSize, account)
            val seen = _items.value.map { it.id }.toMutableSet()

            // The cursor can overlap a little: an id is shown once.
            _items.value = _items.value + page.items.filter { seen.add(it.id) }
            _nextCursor.value = page.nextCursor
            _hasLoaded.value = true
            _phase.value = Phase.IDLE
        } catch (cancellation: CancellationException) {
            _phase.value = Phase.IDLE
            throw cancellation
        } catch (_: Exception) {
            _phase.value = Phase.FAILED
        }
    }
}

/** What a timeline line stands for; an unknown kind gets a neutral dot. */
enum class TimelineKind {
    ORDER, REQUEST, CALL, APPOINTMENT, FORM, MAIL, SMS, MONEY, OTHER,
    ;

    companion object {
        fun of(kind: String): TimelineKind {
            val key = kind.lowercase()

            return when {
                "order" in key -> ORDER
                "ticket" in key || "request" in key -> REQUEST
                "call" in key || "voice" in key -> CALL
                "appointment" in key -> APPOINTMENT
                "form" in key || "submission" in key -> FORM
                "mail" in key -> MAIL
                "sms" in key -> SMS
                "invoice" in key || "payment" in key -> MONEY
                else -> OTHER
            }
        }
    }
}

object TimelineFormat {
    /** "W-1042 · 9 okt. 2026 14:02": the reference and the moment, whatever of the two is known. */
    fun subtitle(item: TimelineItem, zone: ZoneId = ZoneId.systemDefault()): String {
        val whenText = item.occurredAtInstant?.atZone(zone)?.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))

        return listOfNotNull(item.ref, whenText).filter { it.isNotEmpty() }.joinToString(" · ")
    }
}

private fun TimelineKind.icon(): ImageVector = when (this) {
    TimelineKind.ORDER -> Icons.Filled.ShoppingBag
    TimelineKind.REQUEST -> Icons.AutoMirrored.Filled.Chat
    TimelineKind.CALL -> Icons.Filled.Phone
    TimelineKind.APPOINTMENT -> Icons.Filled.CalendarMonth
    TimelineKind.FORM -> Icons.Filled.Description
    TimelineKind.MAIL -> Icons.Filled.Mail
    TimelineKind.SMS -> Icons.Filled.Sms
    TimelineKind.MONEY -> Icons.Filled.Euro
    TimelineKind.OTHER -> Icons.Filled.Circle
}

/**
 * "Activiteit" on the contact screen. Not a lazy list: it sits inside the (scrolling) contact screen. The contacts feature puts it
 * under the contact data: `ContactTimelineSection(model, accountId, contact.id)`.
 */
@Composable
fun ContactTimelineSection(model: AppModel, accountId: String, contactId: String, modifier: Modifier = Modifier) {
    val account = model.account(accountId) ?: return
    val timeline = remember(accountId, contactId) { TimelineModel(model.customerCards, account, contactId) }
    val items by timeline.items.collectAsStateWithLifecycle()
    val phase by timeline.phase.collectAsStateWithLifecycle()
    val hasLoaded by timeline.hasLoaded.collectAsStateWithLifecycle()
    val nextCursor by timeline.nextCursor.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(timeline) { timeline.loadFirstPage() }

    Column(modifier.fillMaxWidth().testTag("contact-timeline")) {
        Text(stringResource(R.string.timeline_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

        if (items.isEmpty()) {
            when {
                phase == TimelineModel.Phase.LOADING || (!hasLoaded && phase != TimelineModel.Phase.FAILED) -> TimelineNote(stringResource(R.string.timeline_loading))
                phase == TimelineModel.Phase.FAILED -> TimelineAction(stringResource(R.string.timeline_error)) { scope.launch { timeline.loadFirstPage() } }
                else -> TimelineNote(stringResource(R.string.timeline_empty))
            }
        }

        for (item in items) {
            TimelineRow(item)
        }

        if (items.isNotEmpty() && phase == TimelineModel.Phase.FAILED) {
            TimelineAction(stringResource(R.string.timeline_error)) { scope.launch { timeline.loadMore() } }
        }

        if (nextCursor != null && phase != TimelineModel.Phase.LOADING && phase != TimelineModel.Phase.FAILED) {
            TextButton(onClick = { scope.launch { timeline.loadMore() } }, modifier = Modifier.padding(horizontal = 8.dp).testTag("timeline-more")) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.timeline_more), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun TimelineNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
}

@Composable
private fun TimelineAction(text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Filled.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TimelineRow(item: TimelineItem) {
    val zone = ZoneId.systemDefault()

    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(TimelineKind.of(item.kind).icon(), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp).size(16.dp))

        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge)
            item.summary?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(TimelineFormat.subtitle(item, zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
