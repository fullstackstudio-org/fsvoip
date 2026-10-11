// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.contacts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.contacts.AlphabetIndex
import nl.fullstackstudio.fsvoip.contacts.ContactEntry
import nl.fullstackstudio.fsvoip.contacts.ContactListFilter
import nl.fullstackstudio.fsvoip.contacts.ContactScope
import nl.fullstackstudio.fsvoip.contacts.ContactSection
import nl.fullstackstudio.fsvoip.contacts.ContactSource
import nl.fullstackstudio.fsvoip.contacts.ContactsScreenBrowsing
import nl.fullstackstudio.fsvoip.contacts.DeviceContactsAccess
import nl.fullstackstudio.fsvoip.ui.Brand
import nl.fullstackstudio.fsvoip.ui.InitialsAvatar
import nl.fullstackstudio.fsvoip.ui.ShellActions

/** The Contacts tab: the phone system's address book and colleagues in one segment, the phone's own contacts in the other. */
@Composable
fun ContactsTab(model: AppModel) {
    val context = LocalContext.current
    val kit = remember(model) { model.contactsKit(context) }
    val route by kit.nav.route.collectAsStateWithLifecycle()

    BackHandler(enabled = route != ContactsRoute.Home) { kit.nav.back() }

    when (val current = route) {
        ContactsRoute.Home -> ContactsHome(model, kit)
        is ContactsRoute.Detail -> ContactDetailScreen(model, kit, current.entryId)
        is ContactsRoute.Edit -> ContactEditScreen(model, kit, current.entryId)
        ContactsRoute.Sources -> ContactSourcesScreen(model, kit)
    }
}

/** One option of the filter sheet. */
private data class FilterOption(val filter: ContactListFilter, val title: String, val isList: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ContactsHome(model: AppModel, kit: ContactsKit) {
    val hub = kit.hub
    val view = kit.view
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by hub.entries.collectAsStateWithLifecycle()
    val states by hub.accountStates.collectAsStateWithLifecycle()
    val syncing by hub.syncingAccountIds.collectAsStateWithLifecycle()
    val usesDevice by hub.usesDeviceContacts.collectAsStateWithLifecycle()
    val deviceAccess by hub.deviceAccess.collectAsStateWithLifecycle()
    val favorites by kit.favorites.ids.collectAsStateWithLifecycle()
    val revision by hub.revision.collectAsStateWithLifecycle()
    val accounts by model.accounts.collectAsStateWithLifecycle()
    var showsFilter by remember { mutableStateOf(false) }
    val enableDevice = rememberDeviceContactsSwitch(hub)

    // A list that disappeared from the server (or was switched off) cannot stay selected.
    val filter = view.filter

    if (filter is ContactListFilter.List && states[filter.accountId]?.lists?.any { it.id == filter.listId && it.isEnabled } != true) {
        view.filter = ContactListFilter.All
    }

    fun compute() = ContactsScreenBrowsing.sections(entries, view.scope, view.filter, view.query, favorites) { accountId, listId -> hub.memberIds(listId, accountId) }

    var sections by remember { mutableStateOf(compute()) }

    LaunchedEffect(entries, view.scope, view.filter, view.query, favorites, revision) {
        val query = view.query
        val scopeNow = view.scope
        val filterNow = view.filter
        val data = entries
        val favs = favorites

        val computed = withContext(Dispatchers.Default) {
            ContactsScreenBrowsing.sections(data, scopeNow, filterNow, query, favs) { accountId, listId -> hub.memberIds(listId, accountId) }
        }
        // New rows that arrive above the first one (a sync finishing) must not push a list that is at the top out of sight.
        val atTop = view.listState.firstVisibleItemIndex == 0 && view.listState.firstVisibleItemScrollOffset == 0

        sections = computed

        if (atTop) {
            // After the new rows were laid out: the list keeps its first visible row in place, which is not the top any more.
            androidx.compose.runtime.withFrameNanos { }
            view.listState.scrollToItem(0)
        }
    }

    val isSyncFailing = states.values.any { it.isEnabled && it.lastSyncFailed }
    val canAdd = hub.writableAccountIds.isNotEmpty()
    val hasCentraleContacts = entries.any { it.source != ContactSource.Device }

    val filterOptions = buildList {
        add(FilterOption(ContactListFilter.All, stringResource(R.string.contacts_filter_pillAll)))
        add(FilterOption(ContactListFilter.Favorites, stringResource(R.string.contacts_filter_favorites)))

        if (view.scope == ContactScope.CENTRALE) {
            if (entries.any { it.source == ContactSource.InternalExtensions }) add(FilterOption(ContactListFilter.InternalOnly, stringResource(R.string.contacts_filter_internal)))

            for (account in accounts) {
                val state = states[account.id] ?: continue

                if (!state.isEnabled) continue

                for (list in state.lists.filter { it.isEnabled }) {
                    val suffix = if (accounts.size > 1) " · ${account.displayLabel}" else ""
                    add(FilterOption(ContactListFilter.List(account.id, list.id), list.name + suffix, isList = true))
                }
            }
        }
    }
    val filterTitle = filterOptions.firstOrNull { it.filter == view.filter }?.title ?: stringResource(R.string.contacts_filter_pillAll)

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.contacts_title)) }, actions = { ShellActions(model) }) },
        floatingActionButton = {
            if (canAdd && view.scope == ContactScope.CENTRALE) {
                FloatingActionButton(
                    onClick = { kit.nav.open(ContactsRoute.Edit(null)) },
                    containerColor = Brand.lime,
                    contentColor = Brand.ink,
                    modifier = Modifier.testTag("contacts-add"),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.contacts_add))
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ContactsHeader(
                scope = view.scope,
                onScope = { view.select(it) },
                filterTitle = filterTitle,
                filterActive = view.filter != ContactListFilter.All,
                onFilter = { showsFilter = true },
                query = view.query,
                onQuery = { view.query = it },
            )

            PullToRefreshBox(
                isRefreshing = syncing.isNotEmpty(),
                onRefresh = { scope.launch { hub.syncAll(force = true); hub.refreshDeviceContacts() } },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                when (view.scope) {
                    ContactScope.CENTRALE -> if (!hasCentraleContacts && view.query.isEmpty() && view.filter == ContactListFilter.All) {
                        CentraleEmpty(syncing = syncing.isNotEmpty(), canAdd = canAdd, onAdd = { kit.nav.open(ContactsRoute.Edit(null)) })
                    } else {
                        ContactList(model, kit, sections, isSyncFailing, view.query, view.filter, favorites, onRetry = { scope.launch { hub.syncAll(force = true) } })
                    }
                    ContactScope.PHONE -> if (!usesDevice || deviceAccess == DeviceContactsAccess.DENIED) {
                        PhoneOff(denied = deviceAccess == DeviceContactsAccess.DENIED, onEnable = { enableDevice(true) }, onSettings = { openAppSettings(context) })
                    } else {
                        ContactList(model, kit, sections, false, view.query, view.filter, favorites, onRetry = {})
                    }
                }
            }
        }
    }

    if (showsFilter) {
        FilterSheet(
            options = filterOptions,
            selection = view.filter,
            showsSources = view.scope == ContactScope.CENTRALE,
            onSelect = {
                view.filter = it
                showsFilter = false
            },
            onSources = {
                showsFilter = false
                kit.nav.open(ContactsRoute.Sources)
            },
            onDismiss = { showsFilter = false },
        )
    }
}

// MARK: Header

@Composable
private fun ContactsHeader(
    scope: ContactScope,
    onScope: (ContactScope) -> Unit,
    filterTitle: String,
    filterActive: Boolean,
    onFilter: () -> Unit,
    query: String,
    onQuery: (String) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("contacts-segments")) {
            val colors = SegmentedButtonDefaults.colors(
                activeContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                activeContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            SegmentedButton(
                selected = scope == ContactScope.CENTRALE,
                onClick = { onScope(ContactScope.CENTRALE) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                colors = colors,
            ) { Text(stringResource(R.string.contacts_segment_centrale)) }
            SegmentedButton(
                selected = scope == ContactScope.PHONE,
                onClick = { onScope(ContactScope.PHONE) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                colors = colors,
            ) { Text(stringResource(R.string.contacts_segment_phone)) }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill(filterTitle, filterActive, onFilter)
            SearchField(query, onQuery, Modifier.weight(1f))
        }
    }
}

@Composable
private fun FilterPill(title: String, active: Boolean, onClick: () -> Unit) {
    val description = stringResource(R.string.contacts_filter_label, title)

    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.heightIn(min = 48.dp).widthIn(max = 160.dp).semantics { contentDescription = description }.testTag("contacts-filter"),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.contacts_search), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.contacts_search_clear)) }
            }
        },
        shape = CircleShape,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {}),
        modifier = modifier.heightIn(min = 48.dp).testTag("contacts-search"),
    )
}

// MARK: List

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactList(
    model: AppModel,
    kit: ContactsKit,
    sections: List<ContactSection>,
    isSyncFailing: Boolean,
    query: String,
    filter: ContactListFilter,
    favorites: Set<String>,
    onRetry: () -> Unit,
) {
    val listState = kit.view.listState
    val scope = rememberCoroutineScope()
    val hasBanner = isSyncFailing
    // Where each letter's header sits in the lazy list (after the banner, when there is one), for the A-Z strip.
    val headerPositions = remember(sections, hasBanner) {
        var position = if (hasBanner) 1 else 0

        sections.associate { section -> (section.letter to position).also { position += 1 + section.entries.size } }
    }
    val total = sections.sumOf { it.entries.size }
    val largeText = LocalDensity.current.fontScale > 1.3f
    val showsIndex = !largeText && sections.size > 1 && query.isEmpty()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("contacts-list"), listState) {
            if (hasBanner) item { SyncBanner(onRetry) }

            for (section in sections) {
                stickyHeader(key = "h${section.letter}") { SectionHeader(section.letter, Modifier.padding(end = if (showsIndex) 28.dp else 0.dp)) }

                items(section.entries, key = { it.id }) { entry -> ContactRow(model, kit, entry, favorites, Modifier.padding(end = if (showsIndex) 22.dp else 0.dp)) }
            }

            if (sections.isEmpty()) {
                item { NoResults(query, filter) }
            } else {
                item {
                    Text(
                        countText(total),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 88.dp).testTag("contacts-count"),
                    )
                }
            }
        }

        if (showsIndex) {
            val present = sections.map { it.letter }.toSet()

            AlphabetStrip(present, Modifier.align(Alignment.CenterEnd)) { letter ->
                val target = AlphabetIndex.target(letter, sections) ?: return@AlphabetStrip
                val position = headerPositions[target] ?: return@AlphabetStrip

                scope.launch { listState.scrollToItem(position) }
            }
        }
    }
}

@Composable
private fun SectionHeader(letter: Char, modifier: Modifier = Modifier) {
    Text(
        letter.toString(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 4.dp).semantics { heading() },
    )
}

@Composable
private fun ContactRow(model: AppModel, kit: ContactsKit, entry: ContactEntry, favorites: Set<String>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val hub = kit.hub
    var menuOpen by remember { mutableStateOf(false) }
    val isFavorite = entry.id in favorites
    val tag = entry.sourceTag()
    val subtitle = entry.subtitle()
    val favoriteLabel = stringResource(R.string.contacts_favorite_label)
    val more = stringResource(R.string.contacts_row_more, entry.displayName)

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).heightIn(min = 56.dp)
                .clickable { kit.nav.open(ContactsRoute.Detail(entry.id)) }
                .padding(start = 16.dp, top = 6.dp, bottom = 6.dp)
                .semantics(mergeDescendants = true) {}
                .testTag("contact-row"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            InitialsAvatar(entry.displayName, 40, modifier = Modifier.clearAndSetSemantics {})

            Column(Modifier.weight(1f)) {
                Text(entry.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)

                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (isFavorite) {
                Icon(Icons.Filled.Star, contentDescription = favoriteLabel, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
            }

            if (tag != null) {
                Text(
                    tag,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("contact-more").semantics { contentDescription = more }) {
                Icon(Icons.Filled.MoreVert, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                entry.phones.firstOrNull()?.number?.let { number ->
                    DropdownMenuItem(text = { Text(stringResource(R.string.contacts_call)) }, onClick = {
                        menuOpen = false
                        model.call(number, null)
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.contacts_copy)) }, onClick = {
                        menuOpen = false
                        copyToClipboard(context, number)
                    })
                }

                DropdownMenuItem(
                    text = { Text(stringResource(if (isFavorite) R.string.contacts_favorite_remove else R.string.contacts_favorite_add)) },
                    onClick = {
                        menuOpen = false
                        kit.favorites.toggle(entry.id)
                    },
                )

                if (hub.canWrite(entry)) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.contacts_detail_edit)) }, onClick = {
                        menuOpen = false
                        kit.nav.open(ContactsRoute.Edit(entry.id))
                    })
                }
            }
        }
    }

    HorizontalDivider(modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("", text))

    // Android 13 and later show their own confirmation.
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, context.getString(R.string.contacts_copied), Toast.LENGTH_SHORT).show()
    }
}

// MARK: A-Z strip

@Composable
private fun AlphabetStrip(present: Set<Char>, modifier: Modifier = Modifier, onLetter: (Char) -> Unit) {
    // VoiceOver-style users read the section headers instead; a drag strip is no use without sight.
    Column(
        modifier.width(26.dp).height((AlphabetIndex.letters.size * 15).dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onLetter(AlphabetIndex.letter((down.position.y / size.height).toDouble()))

                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break

                        if (change.pressed) {
                            onLetter(AlphabetIndex.letter((change.position.y / size.height).toDouble()))
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .clearAndSetSemantics {}
            .testTag("contacts-index"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (letter in AlphabetIndex.letters) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    letter.toString(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (letter in present) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

// MARK: States

@Composable
private fun SyncBanner(onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).semantics(mergeDescendants = true) {}.testTag("contacts-sync-failed"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = Brand.amber)
        Text(stringResource(R.string.contacts_syncFailed), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

@Composable
private fun NoResults(query: String, filter: ContactListFilter) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(30.dp))
        Text(stringResource(R.string.contacts_noResults_title), style = MaterialTheme.typography.titleMedium)

        val body = when {
            query.isNotEmpty() -> stringResource(R.string.contacts_noResults_body, query)
            filter == ContactListFilter.Favorites -> stringResource(R.string.contacts_favorites_empty)
            else -> ""
        }

        if (body.isNotEmpty()) {
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun CentraleEmpty(syncing: Boolean, canAdd: Boolean, onAdd: () -> Unit) {
    if (syncing) {
        Column(Modifier.fillMaxSize().testTag("contacts-empty"), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(stringResource(R.string.contacts_syncing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        ContactsEmptyState(
            Icons.Filled.People,
            stringResource(R.string.contacts_empty_title),
            stringResource(R.string.contacts_empty_body),
            Modifier.testTag("contacts-empty"),
            actionTitle = if (canAdd) stringResource(R.string.contacts_empty_add) else null,
            onAction = if (canAdd) onAdd else null,
        )
    }
}

@Composable
private fun PhoneOff(denied: Boolean, onEnable: () -> Unit, onSettings: () -> Unit) {
    if (denied) {
        ContactsEmptyState(
            Icons.Filled.Lock,
            stringResource(R.string.contacts_phone_denied_title),
            stringResource(R.string.contacts_sources_device_denied),
            Modifier.testTag("contacts-phone-denied"),
            actionTitle = stringResource(R.string.contacts_sources_device_openSettings),
            onAction = onSettings,
        )
    } else {
        ContactsEmptyState(
            Icons.Filled.PhoneAndroid,
            stringResource(R.string.contacts_phone_off_title),
            stringResource(R.string.contacts_phone_off_message),
            Modifier.testTag("contacts-phone-off"),
            actionTitle = stringResource(R.string.contacts_phone_off_enable),
            onAction = onEnable,
        )
    }
}

// MARK: Filter sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(options: List<FilterOption>, selection: ContactListFilter, showsSources: Boolean, onSelect: (ContactListFilter) -> Unit, onSources: () -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.padding(bottom = 16.dp).testTag("contacts-filter-sheet")) {
            Text(stringResource(R.string.contacts_filter), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() })

            options.filter { !it.isList }.forEach { FilterChoice(it.title, it.filter == selection) { onSelect(it.filter) } }

            if (options.any { it.isList }) {
                Text(
                    stringResource(R.string.contacts_filter_lists).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
                options.filter { it.isList }.forEach { FilterChoice(it.title, it.filter == selection) { onSelect(it.filter) } }
            }

            if (showsSources) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onSources).padding(horizontal = 16.dp).testTag("contacts-sources"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(Icons.Filled.Tune, contentDescription = null)
                    Text(stringResource(R.string.contacts_sources), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun FilterChoice(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)

        if (selected) Icon(Icons.Filled.Check, contentDescription = null)
    }
}
