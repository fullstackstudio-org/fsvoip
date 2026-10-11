// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.pbx

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R

/**
 * The "Centrale" sections of the paired accounts: one [PbxSectionModel] per account, made on first use and dropped when the account
 * goes. Who may see the section comes from the app's `/me` (`AppModel.canManagePbx`) and is never stored here: the server enforces
 * every route, a 403 hides the section again.
 */
class PbxHub(private val model: AppModel, private val scope: CoroutineScope = MainScope()) {
    /** The check before a change (the biometric gate): set when it is plugged in; until then every change is allowed. */
    @Volatile
    var gate: PbxGate = PbxGate.Open

    private val sections = mutableMapOf<String, PbxSectionModel>()

    init {
        model.meListeners += { accountId, me -> synchronized(sections) { sections[accountId] }?.noteReadOnly(me.pbx?.readOnly ?: true) }
        model.accountRemovedListeners += { accountId -> forget(accountId) }
    }

    fun section(accountId: String): PbxSectionModel? {
        val account = model.account(accountId) ?: return null

        return synchronized(sections) {
            sections.getOrPut(accountId) {
                val readOnly = model.meByAccount.value[accountId]?.pbx?.readOnly ?: true
                val section = PbxSectionModel(
                    accountId = accountId,
                    fallbackName = account.pbxName,
                    readOnly = readOnly,
                    service = LivePbxService { model.client(accountId) },
                    scope = scope,
                    gate = PbxGate { this@PbxHub.gate.ensureUnlocked() },
                )
                section.onAccessLost = { scope.launch { model.refreshAccounts() } }
                section.onRevoked = { model.removeRevoked(accountId) }
                section
            }
        }
    }

    /** The account is gone from this phone. */
    fun forget(accountId: String) {
        synchronized(sections) { sections.remove(accountId) }?.stopPolling()
    }
}

sealed interface PbxRoute {
    data class Part(val part: PbxPart) : PbxRoute

    data class Device(val id: String) : PbxRoute

    /** `id == null`: a new ring group. */
    data class RingGroup(val id: String?) : PbxRoute

    data class HoursEdit(val id: String) : PbxRoute

    data class Closed(val id: String) : PbxRoute

    data class Number(val id: String, val placeholderTitle: String?) : PbxRoute

    data class NumberStep(val id: String, val step: NumberStepKind) : PbxRoute
}

enum class NumberStepKind { NAME, HOURS, WELCOME, FORWARDING, RECORDING }

/** What the screens use to move around inside the section. */
class PbxNav(private val stack: MutableList<PbxRoute>, private val leave: () -> Unit) {
    fun push(route: PbxRoute) {
        stack.add(route)
    }

    /** Back one page; from the first page of the section: out of the section. */
    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else leave()
    }
}

/**
 * The "Centrale" of one account. [part] is the page the settings opened; every other page is reached from it. [back] leaves the
 * whole section (from the first page).
 */
@Composable
fun PbxHubScreen(model: AppModel, accountId: String, part: PbxPart, back: () -> Unit) {
    val context = LocalContext.current
    val hub = model.feature { PbxHub(model).also { it.gate = nl.fullstackstudio.fsvoip.ui.LocalAccessWiring.pbxGate(context, model) } }
    val section = remember(accountId) { hub.section(accountId) }

    if (section == null) {
        LaunchedEffect(accountId) { back() }
        return
    }

    // The role goes away while the section is open: say so and close.
    val meByAccount by model.meByAccount.collectAsStateWithLifecycle()
    val allowed = meByAccount[accountId]?.canManagePbx ?: false
    var wasAllowed by remember(accountId) { mutableStateOf(allowed) }

    val accessLostText = stringResource(R.string.adm_pbx_error_accessLost)

    LaunchedEffect(allowed) {
        if (allowed) {
            wasAllowed = true
        } else if (wasAllowed) {
            model.showNotice(accessLostText, isError = true)
            back()
        }
    }

    DisposableEffect(accountId) { onDispose { section.stopPolling() } }

    val stack = remember(accountId, part) { mutableStateListOf<PbxRoute>(PbxRoute.Part(part)) }
    val nav = remember(stack) { PbxNav(stack, back) }

    BackHandler(enabled = stack.size > 1) { nav.pop() }

    when (val route = stack.last()) {
        is PbxRoute.Part -> when (route.part) {
            PbxPart.OVERVIEW -> PbxOverviewScreen(section, nav)
            PbxPart.DEVICES -> DevicesScreen(section, nav)
            PbxPart.RING_GROUPS -> RingGroupsScreen(section, nav)
            PbxPart.HOURS -> HoursScreen(section, nav)
            PbxPart.NUMBERS -> NumbersListScreen(section, nav)
        }
        is PbxRoute.Device -> DeviceEditScreen(section, route.id, nav)
        is PbxRoute.RingGroup -> RingGroupEditScreen(section, route.id, nav)
        is PbxRoute.HoursEdit -> HoursEditScreen(section, route.id, nav)
        is PbxRoute.Closed -> TemporaryClosedScreen(section, route.id, nav)
        is PbxRoute.Number -> NumberScreen(section, route.id, route.placeholderTitle, nav)
        is PbxRoute.NumberStep -> NumberStepScreen(section, route.id, route.step, nav)
    }
}
