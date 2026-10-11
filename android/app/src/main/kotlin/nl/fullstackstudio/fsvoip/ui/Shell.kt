// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The shell of the main screen (Android port of the iOS `RootView` tab bar, top bar and settings sheet): five tabs in the
// order of the iOS app, a top bar with the gear and the avatar (with the availability dot), and the settings sheet.

package nl.fullstackstudio.fsvoip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.sipengine.RegistrationState

/** Titles of the tabs, in the order of the bottom bar. */
private data class TabSpec(val tab: AppModel.Tab, val icon: ImageVector, val label: Int, val tag: String)

private val TABS = listOf(
    TabSpec(AppModel.Tab.DIALER, Icons.Filled.Dialpad, R.string.tab_dialer, "tab-dialer"),
    TabSpec(AppModel.Tab.ON_HOLD, Icons.Filled.PauseCircle, R.string.tab_onHold, "tab-onhold"),
    TabSpec(AppModel.Tab.RECENTS, Icons.Filled.History, R.string.tab_recents, "tab-recents"),
    TabSpec(AppModel.Tab.VOICEMAIL, Icons.Filled.Voicemail, R.string.tab_voicemail, "tab-voicemail"),
    TabSpec(AppModel.Tab.CONTACTS, Icons.Filled.Contacts, R.string.tab_contacts, "tab-contacts"),
)

@Composable
fun MainTabs(model: AppModel) {
    val tab by model.selectedTab.collectAsStateWithLifecycle()
    val settingsOpen by model.isSettingsPresented.collectAsStateWithLifecycle()
    val title = stringResource(TABS.first { it.tab == tab }.label)

    Scaffold(
        topBar = { ShellTopBar(model, title) },
        bottomBar = {
            NavigationBar {
                for (spec in TABS) {
                    TabItem(tab == spec.tab, spec.icon, stringResource(spec.label), spec.tag) { model.select(spec.tab) }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                AppModel.Tab.DIALER -> DialerScreen(model)
                AppModel.Tab.ON_HOLD -> ComingSoon(Icons.Filled.PauseCircle, stringResource(R.string.tab_onHold))
                AppModel.Tab.RECENTS -> RecentsScreen(model)
                AppModel.Tab.VOICEMAIL -> ComingSoon(Icons.Filled.Voicemail, stringResource(R.string.tab_voicemail))
                AppModel.Tab.CONTACTS -> ComingSoon(Icons.Filled.Contacts, stringResource(R.string.tab_contacts))
            }
        }
    }

    if (settingsOpen) {
        SettingsSheet(model)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(selected: Boolean, icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.testTag(tag),
        icon = { Icon(icon, contentDescription = null) },
        // Five labels share the bar: "Geschiedenis" is the longest, so the text is small and never wraps.
        label = { Text(label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Clip) },
        alwaysShowLabel = true,
        // The lime accent has too little contrast as text on a light bar: the selected tab is marked by the indicator.
        colors = NavigationBarItemDefaults.colors(
            indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
            selectedIconColor = MaterialTheme.colorScheme.onSurface,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

/** The top bar of every tab: the title, the gear and the avatar with the availability dot. Both open the settings sheet. */
@Composable
fun ShellTopBar(model: AppModel, title: String) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val registrations by model.phone.registrations.collectAsStateWithLifecycle()
    model.settingsRevision.collectAsStateWithLifecycle().value
    val account = model.defaultOutgoingAccountId?.let { id -> accounts.firstOrNull { it.id == id } } ?: accounts.firstOrNull()
    val state = account?.let { registrations[it.id] } ?: RegistrationState.Unregistered
    val dot = AvailabilityDot.of(state, doNotDisturb = false)
    val avatarLabel = stringResource(R.string.settings_avatar_label, account?.extensionName ?: "")
    val dotLabel = stringResource(dot.label)

    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp).testTag("shell-top-bar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))

        IconButton(onClick = { model.openSettings() }, modifier = Modifier.size(48.dp).testTag("settings-gear")) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
        }

        Box(
            Modifier
                .size(48.dp)
                .clickable { model.openSettings() }
                .testTag("settings-avatar")
                .semantics(mergeDescendants = true) {
                    contentDescription = avatarLabel
                    stateDescription = dotLabel
                },
            contentAlignment = Alignment.Center,
        ) {
            InitialsAvatar(account?.extensionName, size = 32.dp, dot = dot.color)
        }
    }
}

/** What the dot on the avatar says. Pure, so it is tested without a screen. */
enum class AvailabilityDot(val color: Color, val label: Int) {
    AVAILABLE(Brand.lime, R.string.settings_dot_available),
    DO_NOT_DISTURB(Brand.hangUp, R.string.settings_dot_dnd),
    CONNECTING(Brand.amber, R.string.settings_dot_connecting),
    OFFLINE(Color(0xFFC7C7CC), R.string.settings_dot_offline),
    ;

    companion object {
        /** Do not disturb wins over everything but a line that is not connected: nobody reaches you then either way. */
        fun of(state: RegistrationState, doNotDisturb: Boolean): AvailabilityDot = when {
            state == RegistrationState.Registering -> CONNECTING
            state != RegistrationState.Registered -> OFFLINE
            doNotDisturb -> DO_NOT_DISTURB
            else -> AVAILABLE
        }
    }
}

/** Initials in a circle, with a small status dot at the bottom right. */
@Composable
fun InitialsAvatar(name: String?, size: androidx.compose.ui.unit.Dp, dot: Color? = null) {
    Box(Modifier.size(size)) {
        Box(Modifier.size(size).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Text(initials(name), fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }

        if (dot != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.36f)
                    .background(MaterialTheme.colorScheme.background, CircleShape)
                    .padding(1.5.dp)
                    .background(dot, CircleShape),
            )
        }
    }
}

fun initials(name: String?): String {
    val words = name.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    return when (words.size) {
        0 -> "•"
        1 -> words[0].take(1).uppercase()
        else -> (words.first().take(1) + words.last().take(1)).uppercase()
    }
}

/** A tab whose screen comes in a later part of the Android port. */
@Composable
fun ComingSoon(icon: ImageVector, title: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(34.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
        Text(stringResource(R.string.soon_title), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        Text(stringResource(R.string.soon_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Settings sheet (the iOS `SettingsSheet`): the account list and everything behind it, over the main screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(model: AppModel) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val stack = remember { mutableStateListOf<SettingsPage>() }

    ModalBottomSheet(
        onDismissRequest = { model.closeSettings() },
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Box(Modifier.fillMaxHeight(0.94f).testTag("settings-sheet")) {
            SettingsNavigation(model, stack, close = { model.closeSettings() })
        }
    }
}
