// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Small pieces the Contacts screens share: texts for labels and failures, the empty state, rows and a permission helper.

package nl.fullstackstudio.fsvoip.ui.contacts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Euro
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.contacts.ContactEntry
import nl.fullstackstudio.fsvoip.contacts.ContactFailure
import nl.fullstackstudio.fsvoip.contacts.ContactSource
import nl.fullstackstudio.fsvoip.contacts.ContactsHub
import nl.fullstackstudio.fsvoip.contacts.TimelineSymbol
import nl.fullstackstudio.fsvoip.core.ContactPhoneLabel
import nl.fullstackstudio.fsvoip.ui.SecondaryButton

@Composable
fun ContactPhoneLabel.title(): String = stringResource(
    when (this) {
        ContactPhoneLabel.MOBILE -> R.string.contacts_label_mobile
        ContactPhoneLabel.WORK -> R.string.contacts_label_work
        ContactPhoneLabel.HOME -> R.string.contacts_label_home
        ContactPhoneLabel.MAIN -> R.string.contacts_label_main
        ContactPhoneLabel.FAX -> R.string.contacts_label_fax
        ContactPhoneLabel.OTHER, ContactPhoneLabel.UNKNOWN -> R.string.contacts_label_other
    },
)

/** The labels the edit screen offers. */
val contactLabelChoices = listOf(
    ContactPhoneLabel.MOBILE, ContactPhoneLabel.WORK, ContactPhoneLabel.HOME, ContactPhoneLabel.MAIN, ContactPhoneLabel.FAX, ContactPhoneLabel.OTHER,
)

/** The grey line under the name in a list. */
@Composable
fun ContactEntry.subtitle(): String = when (source) {
    ContactSource.InternalExtensions -> numbers.firstOrNull()?.let { stringResource(R.string.account_extension, it) } ?: ""
    else -> if (!company.isNullOrEmpty() && company != name) company!! else numbers.firstOrNull() ?: ""
}

/** A small tag for contacts that do not come from the address book. */
@Composable
fun ContactEntry.sourceTag(): String? = when (source) {
    ContactSource.Device -> stringResource(R.string.contacts_source_device)
    ContactSource.InternalExtensions -> stringResource(R.string.contacts_source_colleague)
    else -> null
}

/** What to tell the user when a contact request fails. Never the server's own words. */
@Composable
fun ContactFailure.message(): String = stringResource(
    when (this) {
        ContactFailure.PHONE -> R.string.contacts_error_phone
        ContactFailure.EMAIL -> R.string.contacts_error_email
        ContactFailure.FORBIDDEN -> R.string.contacts_error_forbidden
        ContactFailure.LIMIT -> R.string.contacts_error_limit
        ContactFailure.RATE -> R.string.contacts_error_rate
        ContactFailure.NETWORK -> R.string.contacts_error_network
        ContactFailure.GONE -> R.string.contacts_error_gone
        ContactFailure.STALE, ContactFailure.GENERIC -> R.string.error_generic
    },
)

@Composable
fun countText(count: Int): String = if (count == 1) stringResource(R.string.contacts_count_one) else stringResource(R.string.contacts_count_many, count)

fun TimelineSymbol.icon(): ImageVector = when (this) {
    TimelineSymbol.ORDER -> Icons.Filled.ShoppingBag
    TimelineSymbol.TICKET -> Icons.Filled.ChatBubbleOutline
    TimelineSymbol.CALL -> Icons.Filled.Phone
    TimelineSymbol.APPOINTMENT -> Icons.Filled.CalendarMonth
    TimelineSymbol.FORM -> Icons.Filled.Description
    TimelineSymbol.MAIL -> Icons.Filled.Email
    TimelineSymbol.SMS -> Icons.Filled.Sms
    TimelineSymbol.INVOICE -> Icons.Filled.Euro
    TimelineSymbol.OTHER -> Icons.Filled.Circle
}

private val shortDateTime: DateTimeFormatter get() = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault())

fun formatDateTime(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = shortDateTime.format(instant.atZone(zone))

/** The empty state of a tab: icon, title, text and an optional action. */
@Composable
fun ContactsEmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, actionTitle: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(38.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)

        if (actionTitle != null && onAction != null) {
            SecondaryButton(actionTitle, onAction, modifier = Modifier.padding(top = 12.dp).testTag("contacts-empty-action"))
        }
    }
}

fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    runCatching { context.startActivity(intent) }
}

/**
 * Switch the phone's contacts on or off. Switching on asks the system for permission first (Android needs the screen for that),
 * then tells the hub; a refusal puts the hub in the "denied" state, which shows the way to the system settings.
 */
@Composable
fun rememberDeviceContactsSwitch(hub: ContactsHub): (Boolean) -> Unit {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scope.launch { if (granted) hub.setDeviceContactsEnabled(true) else hub.markDeviceAccessDenied() }
    }

    return { on ->
        if (!on) {
            scope.launch { hub.setDeviceContactsEnabled(false) }
        } else if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            scope.launch { hub.setDeviceContactsEnabled(true) }
        } else {
            launcher.launch(Manifest.permission.READ_CONTACTS)
        }
    }
}
