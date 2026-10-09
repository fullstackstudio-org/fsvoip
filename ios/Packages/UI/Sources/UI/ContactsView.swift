// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import FSContacts
import SwiftUI
import UIKit

/// The Contacts tab: the phone system's address book and colleagues in one segment, the phone's own contacts in the other.
struct ContactsView: View {
    @ObservedObject var model: FSVoipAppModel
    @ObservedObject private var hub: ContactsHub
    @ObservedObject private var favorites = FavoriteContacts.shared
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var typeSize

    @State private var scope: ContactScope = .centrale
    @State private var filter: ContactListFilter = .all
    @State private var query = ""
    @State private var sections: [ContactSection] = []
    @State private var showsSources = false
    @State private var showsFilter = false
    @State private var showsNew = false
    @State private var editing: EditTarget?
    @FocusState private var searchFocused: Bool
    #if DEBUG
    /// Demo mode only (`-FSVoipDemoContactsScreen detail|edit|new|sources`): open a sub screen without tapping, for screenshots.
    @State private var demoEntryId: String?
    @State private var demoOpensDetail = false
    @State private var demoHandled = false
    #endif

    struct EditTarget: Identifiable {
        let id: String
    }

    init(model: FSVoipAppModel) {
        self.model = model
        hub = model.contacts
    }

    private var isSyncFailing: Bool {
        hub.accountStates.values.contains { $0.isEnabled && $0.lastSyncFailed }
    }

    private var canAdd: Bool {
        !hub.writableAccountIds.isEmpty
    }

    private var hasCentraleContacts: Bool {
        hub.entries.contains { $0.source != .device }
    }

    var body: some View {
        VStack(spacing: Theme.Spacing.m) {
            header

            content
        }
        .background(Theme.background.ignoresSafeArea())
        .navigationTitle(L10n.string("contacts.title"))
        .overlay(alignment: .bottomTrailing) { addButton }
        .refreshable { await model.syncContacts(force: true) }
        .sheet(isPresented: $showsSources) {
            ContactSourcesView(model: model)
        }
        .sheet(isPresented: $showsNew) {
            ContactEditView(model: model, mode: .new)
        }
        .sheet(item: $editing) { target in
            ContactEditView(model: model, mode: .edit(entryId: target.id))
        }
        .sheet(isPresented: $showsFilter) {
            ContactFilterSheet(
                options: filterOptions,
                selection: $filter,
                showsSources: scope == .centrale,
                onSources: {
                    showsFilter = false
                    DispatchQueue.main.async { showsSources = true }
                }
            )
            .presentationDetents([.medium, .large])
        }
        #if DEBUG
        .background(
            NavigationLink(isActive: $demoOpensDetail) {
                ContactDetailView(model: model, entryId: demoEntryId ?? "")
            } label: { EmptyView() }
            .hidden()
        )
        #endif
        .onAppear(perform: recompute)
        .onChange(of: query) { _ in recompute() }
        .onChange(of: filter) { _ in recompute() }
        .onChange(of: scope) { _ in
            filter = .all
            recompute()
        }
        .onChange(of: hub.revision) { _ in recompute() }
        .onChange(of: favorites.ids) { _ in recompute() }
    }

    // MARK: Header

    private var header: some View {
        VStack(spacing: Theme.Spacing.m) {
            SegmentedBar(
                options: [
                    .init(value: .centrale, title: L10n.string("contacts.segment.centrale")),
                    .init(value: .phone, title: L10n.string("contacts.segment.phone")),
                ],
                selection: $scope
            )
            .accessibilityIdentifier("contacts-segments")

            HStack(spacing: Theme.Spacing.s) {
                FilterButton(title: filterTitle(filter), isActive: filter != .all) { showsFilter = true }
                    .accessibilityLabel(String(format: L10n.string("contacts.filter.label"), filterTitle(filter)))
                    .accessibilityIdentifier("contacts-filter")

                searchField
            }
        }
        .padding(.horizontal, Theme.Spacing.l)
        .padding(.top, Theme.Spacing.s)
    }

    private var searchField: some View {
        HStack(spacing: Theme.Spacing.s) {
            Image(systemName: "magnifyingglass")
                .font(.subheadline)
                .foregroundStyle(Theme.textTertiary)
                .accessibilityHidden(true)

            TextField(L10n.string("contacts.search"), text: $query)
                .font(.subheadline)
                .foregroundStyle(Theme.textPrimary)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .focused($searchFocused)
                .accessibilityIdentifier("contacts-search")

            if !query.isEmpty {
                Button {
                    query = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(Theme.textTertiary)
                        .frame(minWidth: Theme.minimumTarget, minHeight: Theme.minimumTarget)
                }
                .accessibilityLabel(L10n.string("contacts.search.clear"))
            }
        }
        .padding(.horizontal, Theme.Spacing.m)
        .frame(minHeight: 36)
        .background(Theme.raised, in: Capsule())
        .overlay(Capsule().strokeBorder(Theme.separator, lineWidth: 1))
        .frame(minHeight: Theme.minimumTarget)
    }

    private var addButton: some View {
        Group {
            if canAdd, scope == .centrale {
                Button {
                    showsNew = true
                } label: {
                    Image(systemName: "plus")
                        .font(.title2.weight(.semibold))
                        .foregroundStyle(Theme.onAccent)
                        .frame(width: 56, height: 56)
                        .background(Theme.accent, in: Circle())
                        .shadow(color: .black.opacity(0.25), radius: 8, y: 3)
                }
                .padding(Theme.Spacing.l)
                .accessibilityLabel(L10n.string("contacts.add"))
                .accessibilityIdentifier("contacts-add")
            }
        }
    }

    // MARK: Content

    @ViewBuilder
    private var content: some View {
        switch scope {
        case .centrale:
            if !hasCentraleContacts, query.isEmpty, filter == .all {
                centraleEmpty
            } else {
                list
            }
        case .phone:
            if !hub.usesDeviceContacts || hub.deviceAccess == .denied {
                phoneOff
            } else {
                list
            }
        }
    }

    private var list: some View {
        ScrollViewReader { proxy in
            ZStack(alignment: .trailing) {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0, pinnedViews: [.sectionHeaders]) {
                        if isSyncFailing {
                            syncBanner
                        }

                        ForEach(sections) { section in
                            Section {
                                ForEach(section.entries) { entry in
                                    row(entry)
                                }
                            } header: {
                                sectionHeader(section.letter)
                            }
                        }

                        if sections.isEmpty {
                            noResults
                        } else {
                            Text(ContactFailure.countText(sections.reduce(0) { $0 + $1.entries.count }))
                                .font(.footnote)
                                .foregroundStyle(Theme.textTertiary)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, Theme.Spacing.l)
                                .padding(.bottom, 72)
                                .accessibilityIdentifier("contacts-count")
                        }
                    }
                    .padding(.trailing, showsIndex ? 22 : 0)
                }
                .scrollDismissesKeyboard(.interactively)
                .accessibilityIdentifier("contacts-list")

                if showsIndex {
                    indexBar(proxy: proxy)
                }
            }
        }
    }

    private var showsIndex: Bool {
        !typeSize.isAccessibilitySize && sections.count > 1 && query.isEmpty
    }

    private func sectionHeader(_ letter: Character) -> some View {
        Text(String(letter))
            .font(.footnote.weight(.semibold))
            .foregroundStyle(Theme.textTertiary)
            .padding(.horizontal, Theme.Spacing.l)
            .padding(.vertical, Theme.Spacing.xs)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.background)
            .id(letter)
            .accessibilityAddTraits(.isHeader)
    }

    private func row(_ entry: ContactEntry) -> some View {
        HStack(spacing: 0) {
            NavigationLink {
                ContactDetailView(model: model, entryId: entry.id)
            } label: {
                ContactRow(entry: entry, isFavorite: favorites.contains(entry.id))
            }
            .buttonStyle(.plain)

            rowMenu(entry)
        }
        .padding(.leading, Theme.Spacing.l)
        .padding(.trailing, Theme.Spacing.xs)
        .overlay(alignment: .bottom) {
            Rectangle().fill(Theme.separator).frame(height: 1).padding(.leading, Theme.Spacing.l + 52)
        }
    }

    private func rowMenu(_ entry: ContactEntry) -> some View {
        Menu {
            if let number = entry.phones.first?.number {
                Button {
                    model.call(number, from: nil)
                } label: {
                    Label(L10n.string("contacts.call"), systemImage: "phone")
                }

                Button {
                    UIPasteboard.general.string = number
                    Haptics.tap()
                } label: {
                    Label(L10n.string("contacts.copy"), systemImage: "doc.on.doc")
                }
            }

            Button {
                favorites.toggle(entry.id)
            } label: {
                if favorites.contains(entry.id) {
                    Label(L10n.string("contacts.favorite.remove"), systemImage: "star.slash")
                } else {
                    Label(L10n.string("contacts.favorite.add"), systemImage: "star")
                }
            }

            if hub.canWrite(entry) {
                Button {
                    editing = EditTarget(id: entry.id)
                } label: {
                    Label(L10n.string("contacts.detail.edit"), systemImage: "pencil")
                }
            }
        } label: {
            Image(systemName: "ellipsis")
                .font(.body.weight(.semibold))
                .foregroundStyle(Theme.textSecondary)
                .frame(width: Theme.minimumTarget, height: Theme.minimumTarget)
                .contentShape(Rectangle())
        }
        .accessibilityLabel(String(format: L10n.string("contacts.row.more"), entry.displayName))
        .accessibilityIdentifier("contact-more")
    }

    // MARK: Index

    private func indexBar(proxy: ScrollViewProxy) -> some View {
        let present = Set(sections.map(\.letter))

        return GeometryReader { geometry in
            VStack(spacing: 0) {
                ForEach(AlphabetIndex.letters, id: \.self) { letter in
                    Text(String(letter))
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(present.contains(letter) ? Theme.accentText : Theme.textTertiary)
                        .frame(maxWidth: .infinity)
                        .frame(height: geometry.size.height / CGFloat(AlphabetIndex.letters.count))
                }
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        jump(to: AlphabetIndex.letter(atFraction: value.location.y / max(geometry.size.height, 1)), proxy: proxy)
                    }
            )
        }
        .frame(width: 22, height: 27 * 15)
        // VoiceOver reads the section headers instead; a drag strip is no use without sight.
        .accessibilityHidden(true)
        .padding(.trailing, 2)
    }

    private func jump(to letter: Character, proxy: ScrollViewProxy) {
        guard let target = AlphabetIndex.target(for: letter, in: sections) else {
            return
        }

        if reduceMotion {
            proxy.scrollTo(target, anchor: .top)
        } else {
            Motion.run(.easeOut(duration: 0.15)) { proxy.scrollTo(target, anchor: .top) }
        }
    }

    // MARK: States

    private var syncBanner: some View {
        HStack(spacing: Theme.Spacing.m) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(Theme.busy)
                .accessibilityHidden(true)
            Text(L10n.string("contacts.syncFailed"))
                .font(.footnote)
                .foregroundStyle(Theme.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 4)
            Button(L10n.string("action.retry")) {
                Task { await model.syncContacts(force: true) }
            }
            .font(.footnote.weight(.semibold))
            .foregroundStyle(Theme.accentText)
            .frame(minHeight: Theme.minimumTarget)
        }
        .padding(.horizontal, Theme.Spacing.l)
        .accessibilityElement(children: .combine)
    }

    private var noResults: some View {
        VStack(spacing: Theme.Spacing.s) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 30))
                .foregroundStyle(Theme.textTertiary)
                .accessibilityHidden(true)
            L10n.text("contacts.noResults.title")
                .font(.headline)
                .foregroundStyle(Theme.textPrimary)
            Text(noResultsBody)
                .font(.callout)
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
        .padding(.horizontal, Theme.Spacing.l)
    }

    private var noResultsBody: String {
        if !query.isEmpty {
            return String(format: L10n.string("contacts.noResults.body"), query)
        }

        return filter == .favorites ? L10n.string("contacts.favorites.empty") : ""
    }

    @ViewBuilder
    private var centraleEmpty: some View {
        if !hub.syncingAccountIds.isEmpty {
            VStack(spacing: Theme.Spacing.m) {
                ProgressView()
                L10n.text("contacts.syncing")
                    .font(.callout)
                    .foregroundStyle(Theme.textSecondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .accessibilityIdentifier("contacts-empty")
        } else {
            EmptyState(
                symbol: "person.2",
                title: L10n.string("contacts.empty.title"),
                message: L10n.string("contacts.empty.body"),
                actionTitle: canAdd ? L10n.string("contacts.empty.add") : nil,
                action: canAdd ? { showsNew = true } : nil
            )
            .accessibilityIdentifier("contacts-empty")
        }
    }

    @ViewBuilder
    private var phoneOff: some View {
        if hub.deviceAccess == .denied {
            EmptyState(
                symbol: "lock",
                title: L10n.string("contacts.phone.denied.title"),
                message: L10n.string("contacts.sources.device.denied"),
                actionTitle: L10n.string("contacts.sources.device.openSettings"),
                action: {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
            )
        } else {
            EmptyState(
                symbol: "iphone",
                title: L10n.string("contacts.phone.off.title"),
                message: L10n.string("contacts.phone.off.message"),
                actionTitle: L10n.string("contacts.phone.off.enable"),
                action: { Task { _ = await hub.setDeviceContactsEnabled(true) } }
            )
        }
    }

    // MARK: Filter

    private var filterOptions: [ContactFilterOption] {
        var options = [ContactFilterOption(filter: .all, title: L10n.string("contacts.filter.pillAll"))]
        options.append(ContactFilterOption(filter: .favorites, title: L10n.string("contacts.filter.favorites")))

        guard scope == .centrale else {
            return options
        }

        if hub.entries.contains(where: { $0.source == .internalExtensions }) {
            options.append(ContactFilterOption(filter: .internalOnly, title: L10n.string("contacts.filter.internal")))
        }

        for account in model.accounts {
            for list in hub.accountStates[account.id].map({ $0.isEnabled ? $0.lists.filter(\.isEnabled) : [] }) ?? [] {
                let suffix = model.accounts.count > 1 ? " · \(account.displayLabel)" : ""
                options.append(ContactFilterOption(filter: .list(accountId: account.id, listId: list.id), title: list.name + suffix, isList: true))
            }
        }

        return options
    }

    private func filterTitle(_ option: ContactListFilter) -> String {
        filterOptions.first { $0.filter == option }?.title ?? L10n.string("contacts.filter.pillAll")
    }

    private func recompute() {
        // A list that disappeared from the server (or was switched off) cannot stay selected.
        if case let .list(accountId, listId) = filter, hub.accountStates[accountId]?.lists.first(where: { $0.id == listId && $0.isEnabled }) == nil {
            filter = .all
        }

        sections = ContactsBrowsing.sections(entries: hub.entries, scope: scope, filter: filter, query: query, favorites: favorites.ids) { accountId, listId in
            hub.memberIds(listId: listId, accountId: accountId)
        }

        #if DEBUG
        openDemoScreen()
        #endif
    }

    #if DEBUG
    private func openDemoScreen() {
        guard !demoHandled, hub.entries.contains(where: { $0.source == .customer }), let screen = UserDefaults.standard.string(forKey: "FSVoipDemoContactsScreen") else {
            return
        }

        demoHandled = true

        switch screen {
        case "new": showsNew = true
        case "sources": showsSources = true
        case "phone": scope = .phone
        case "filter": showsFilter = true
        default:
            demoEntryId = hub.entries.first { $0.name == "Pieter de Groot" }?.id
            demoOpensDetail = demoEntryId != nil
        }
    }
    #endif
}

struct ContactFilterOption: Identifiable {
    let filter: ContactListFilter
    let title: String
    var isList = false

    var id: ContactListFilter { filter }
}

/// "Alles ⌄": the filters of the segment, and the way to the contact sources.
struct ContactFilterSheet: View {
    let options: [ContactFilterOption]
    @Binding var selection: ContactListFilter
    let showsSources: Bool
    let onSources: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        SheetShell(title: L10n.string("contacts.filter"), onClose: { dismiss() }) {
            SettingsGroup {
                ForEach(options.filter { !$0.isList }) { option in
                    choice(option)
                }
            }

            if options.contains(where: \.isList) {
                SettingsGroup(title: L10n.string("contacts.filter.lists")) {
                    ForEach(options.filter(\.isList)) { option in
                        choice(option)
                    }
                }
            }

            if showsSources {
                SettingsGroup {
                    Button(action: onSources) {
                        SettingsRow(symbol: "slider.horizontal.3", title: L10n.string("contacts.sources"))
                    }
                    .buttonStyle(RowButtonStyle())
                    .accessibilityIdentifier("contacts-sources")
                }
            }
        }
    }

    private func choice(_ option: ContactFilterOption) -> some View {
        ChoiceRow(title: option.title, isSelected: selection == option.filter) {
            selection = option.filter
            dismiss()
        }
    }
}

struct ContactRow: View {
    let entry: ContactEntry
    var isFavorite = false

    var body: some View {
        HStack(spacing: Theme.Spacing.m) {
            InitialsAvatar(name: entry.displayName, size: 40)

            VStack(alignment: .leading, spacing: 2) {
                Text(entry.displayName)
                    .font(.body.weight(.medium))
                    .foregroundStyle(Theme.textPrimary)
                    .lineLimit(2)

                if !entry.subtitle.isEmpty {
                    Text(entry.subtitle)
                        .font(.footnote)
                        .foregroundStyle(Theme.textSecondary)
                        .lineLimit(1)
                }
            }

            Spacer(minLength: Theme.Spacing.s)

            if isFavorite {
                Image(systemName: "star.fill")
                    .font(.footnote)
                    .foregroundStyle(Theme.accentText)
                    .accessibilityLabel(L10n.string("contacts.favorite.label"))
            }

            if let tag = entry.sourceTag {
                Text(tag)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(Theme.textSecondary)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(Capsule().fill(Theme.raised))
            }
        }
        .padding(.vertical, Theme.Spacing.s)
        .frame(minHeight: 56)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("contact-row")
    }
}
