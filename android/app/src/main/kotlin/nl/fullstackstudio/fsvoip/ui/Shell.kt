// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.sipengine.RegistrationState

/**
 * The right side of the top bar of every tab (the iOS `shellToolbar`): the gear and the avatar with the availability dot. Both
 * open the settings. Put it in the `actions` of a `TopAppBar`, or in a `Row` at the top of a screen without a bar.
 */
@Composable
fun ShellActions(model: AppModel) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val registrations by model.phone.registrations.collectAsStateWithLifecycle()
    model.settingsRevision.collectAsStateWithLifecycle().value
    val account = model.defaultOutgoingAccountId?.let { model.account(it) } ?: accounts.firstOrNull()
    val state = account?.let { registrations[it.id] } ?: RegistrationState.Unregistered
    val dot = when (state) {
        RegistrationState.Registered -> Brand.lime
        RegistrationState.Registering -> Brand.amber
        else -> Color(0xFFC7C7CC)
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        IconButton(onClick = { model.openSettings() }, modifier = Modifier.testTag("settings-gear")) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.tab_settings))
        }
        Box(Modifier.size(48.dp).clickable { model.openSettings() }.testTag("settings-avatar"), contentAlignment = Alignment.Center) {
            InitialsAvatar(account?.extensionName, 30, dot)
        }
    }
}

/** Initials in a circle (like the iOS `InitialsAvatar`), with an optional status dot at the lower right. */
@Composable
fun InitialsAvatar(name: String?, size: Int, dot: Color? = null, modifier: Modifier = Modifier) {
    val initials = initialsOf(name)
    val description = name ?: ""

    Box(modifier.size(size.dp).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(size.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
            if (initials.isNotEmpty()) {
                Text(initials, fontSize = (size * 0.38f).sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (dot != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).size((size * 0.3f).dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .background(dot, CircleShape),
            )
        }
    }
}

fun initialsOf(name: String?): String {
    val words = (name ?: "").trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it.first().isLetterOrDigit() }

    return when {
        words.isEmpty() -> ""
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words.first().take(1) + words.last().take(1)).uppercase()
    }
}

/** Temporary body of a tab whose slice is not merged yet. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TabPlaceholder(model: AppModel, title: String) {
    androidx.compose.material3.Scaffold(
        topBar = { androidx.compose.material3.TopAppBar(title = { Text(title) }, actions = { ShellActions(model) }) },
    ) { padding ->
        Box(Modifier.padding(padding))
    }
}
