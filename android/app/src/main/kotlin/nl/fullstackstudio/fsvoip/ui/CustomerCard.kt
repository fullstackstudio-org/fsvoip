// SPDX-License-Identifier: AGPL-3.0-or-later
//
// "Activiteit" on the contact screen: what the website of the customer knows about this contact, newest first, a page at a time.
// Android port of the iOS `ContactTimelineSection`.

package nl.fullstackstudio.fsvoip.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Euro
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.StoredAccount
import nl.fullstackstudio.fsvoip.core.TimelineItem
import nl.fullstackstudio.fsvoip.core.TimelineModel
import nl.fullstackstudio.fsvoip.core.TimelineSymbol
import nl.fullstackstudio.fsvoip.core.timelineSubtitle

@Composable
fun ContactTimelineSection(model: AppModel, account: StoredAccount, contactId: String) {
    val timeline = remember(account.id, contactId) { TimelineModel(model.customerCards, account, contactId) }
    val state by timeline.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val format = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault()) }

    LaunchedEffect(timeline) { timeline.loadFirstPage() }

    section(stringResource(R.string.timeline_title), null) {
        Column(Modifier.testTag("contact-timeline")) {
            if (state.items.isEmpty()) {
                when {
                    state.phase == TimelineModel.Phase.FAILED -> RetryRow { scope.launch { timeline.retry() } }
                    state.phase == TimelineModel.Phase.LOADING || !state.hasLoaded -> InfoRow(stringResource(R.string.timeline_loading))
                    else -> InfoRow(stringResource(R.string.timeline_empty))
                }
            }

            for (item in state.items) {
                TimelineRow(item, timelineSubtitle(item) { format.format(it) })
            }

            if (state.items.isNotEmpty() && state.phase == TimelineModel.Phase.FAILED) {
                RetryRow { scope.launch { timeline.retry() } }
            }

            if (state.canLoadMore && state.phase != TimelineModel.Phase.FAILED) {
                ActionRow(stringResource(R.string.timeline_more), Icons.Filled.Circle, Modifier.testTag("timeline-more")) { scope.launch { timeline.loadMore() } }
            }
        }
    }
}

@Composable
private fun RetryRow(onClick: () -> Unit) {
    Text(
        stringResource(R.string.timeline_error),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp).testTag("timeline-retry"),
    )
}

@Composable
private fun TimelineRow(item: TimelineItem, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(timelineIcon(TimelineSymbol.of(item.kind)), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp).size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge)

            item.summary?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

private fun timelineIcon(symbol: TimelineSymbol): ImageVector = when (symbol) {
    TimelineSymbol.ORDER -> Icons.Filled.ShoppingBag
    TimelineSymbol.REQUEST -> Icons.Filled.ChatBubbleOutline
    TimelineSymbol.CALL -> Icons.Filled.Phone
    TimelineSymbol.APPOINTMENT -> Icons.Filled.CalendarMonth
    TimelineSymbol.FORM -> Icons.Filled.Description
    TimelineSymbol.MAIL -> Icons.Filled.Email
    TimelineSymbol.SMS -> Icons.Filled.Message
    TimelineSymbol.INVOICE -> Icons.Filled.Euro
    TimelineSymbol.OTHER -> Icons.Filled.Circle
}
