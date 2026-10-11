// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.ui.contacts.ContactsTab
import nl.fullstackstudio.fsvoip.ui.media.VoicemailTab
import nl.fullstackstudio.fsvoip.ui.park.OnHoldTab

@Composable
fun RootScreen(model: AppModel) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val sessions by model.phone.sessions.collectAsStateWithLifecycle()
    val lastEnded by model.phone.lastEnded.collectAsStateWithLifecycle()
    val pairing by model.pairing.collectAsStateWithLifecycle()
    val scanner by model.isScannerPresented.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    val callOnScreen = sessions.lastOrNull() ?: lastEnded

    Box(Modifier.fillMaxSize()) {
        if (accounts.isEmpty()) {
            OnboardingScreen(model)
        } else {
            MainTabs(model)
        }

        val overlay by model.overlay.collectAsStateWithLifecycle()
        overlay?.let { content ->
            Surface(Modifier.fillMaxSize()) {
                BackHandler { model.closeOverlay() }
                content { model.closeOverlay() }
            }
        }

        if (scanner || pairing.isActive) {
            PairingSheet(model)
        }

        AnimatedVisibility(visible = callOnScreen != null, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            callOnScreen?.let { InCallScreen(model, it) }
        }

        AnimatedVisibility(
            visible = notice != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            notice?.let { current ->
                NoticeBanner(current) { model.dismissNotice() }
                LaunchedEffect(current.id) {
                    delay(5_000)
                    if (model.notice.value?.id == current.id) model.dismissNotice()
                }
            }
        }
    }
}

@Composable
private fun NoticeBanner(notice: AppModel.Notice, dismiss: () -> Unit) {
    Surface(
        modifier = Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth().testTag("notice-banner"),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                if (notice.isError) Icons.Filled.Error else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = if (notice.isError) Brand.hangUp else androidx.compose.ui.graphics.Color(0xFF34C759),
            )
            Text(notice.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = dismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close), modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Settings has sub pages; a tiny back stack in state (no navigation library needed for a handful of screens). */
sealed interface SettingsPage {
    data class Account(val id: String) : SettingsPage

    data object Licenses : SettingsPage
}

@Composable
private fun MainTabs(model: AppModel) {
    val tab by model.selectedTab.collectAsStateWithLifecycle()
    val settingsOpen by model.isSettingsOpen.collectAsStateWithLifecycle()
    val meByAccount by model.meByAccount.collectAsStateWithLifecycle()
    val settingsStack = remember { mutableStateListOf<SettingsPage>() }
    val showsOnHold = meByAccount.values.any { it.canPark }

    // The "On hold" tab disappears when no account can park: do not stay on it.
    LaunchedEffect(showsOnHold, tab) {
        if (!showsOnHold && tab == AppModel.Tab.ON_HOLD) model.select(AppModel.Tab.DIALER)
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    TabItem(tab == AppModel.Tab.DIALER, Icons.Filled.Dialpad, stringResource(R.string.tab_dialer)) { model.select(AppModel.Tab.DIALER) }
                    if (showsOnHold) {
                        TabItem(tab == AppModel.Tab.ON_HOLD, Icons.Filled.PauseCircle, stringResource(R.string.tab_onHold)) { model.select(AppModel.Tab.ON_HOLD) }
                    }
                    TabItem(tab == AppModel.Tab.RECENTS, Icons.Filled.History, stringResource(R.string.tab_recents)) { model.select(AppModel.Tab.RECENTS) }
                    TabItem(tab == AppModel.Tab.VOICEMAIL, Icons.Filled.Voicemail, stringResource(R.string.tab_voicemail)) { model.select(AppModel.Tab.VOICEMAIL) }
                    TabItem(tab == AppModel.Tab.CONTACTS, Icons.Filled.People, stringResource(R.string.tab_contacts)) { model.select(AppModel.Tab.CONTACTS) }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (tab) {
                    AppModel.Tab.DIALER -> DialerScreen(model)
                    AppModel.Tab.ON_HOLD -> OnHoldTab(model)
                    AppModel.Tab.RECENTS -> RecentsScreen(model)
                    AppModel.Tab.VOICEMAIL -> VoicemailTab(model)
                    AppModel.Tab.CONTACTS -> ContactsTab(model)
                }
            }
        }

        AnimatedVisibility(visible = settingsOpen, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            Surface(Modifier.fillMaxSize()) {
                BackHandler(enabled = settingsOpen && settingsStack.isEmpty()) { model.closeSettings() }
                SettingsNavigation(model, settingsStack)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(selected: Boolean, icon: ImageVector, label: String, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label) },
        // The lime accent has too little contrast as text on a light bar: the selected tab is marked by the indicator.
        colors = NavigationBarItemDefaults.colors(
            indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
            selectedIconColor = MaterialTheme.colorScheme.onSurface,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
fun OnboardingScreen(model: AppModel) {
    Scaffold { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
            Spacer(Modifier.weight(1f))
            BrandMark(72.dp)
            Spacer(Modifier.size(28.dp))
            Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(10.dp))
            Text(stringResource(R.string.onboarding_subtitle), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // What pairing gives you, in the order the user will meet it.
            Column(Modifier.padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OnboardingStep(Icons.Filled.Laptop, stringResource(R.string.onboarding_step_portal))
                OnboardingStep(Icons.Filled.QrCodeScanner, stringResource(R.string.onboarding_step_scan))
                OnboardingStep(Icons.Filled.Phone, stringResource(R.string.onboarding_step_call))
            }

            Spacer(Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.onboarding_scan), onClick = { model.presentScanner() }, icon = Icons.Filled.QrCodeScanner, modifier = Modifier.testTag("scan-button"))
            Text(
                stringResource(R.string.onboarding_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            )
        }
    }
}

@Composable
private fun OnboardingStep(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
