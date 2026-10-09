// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import FSContacts
import SwiftUI

/// The Contacts tab: the customer's address book, the phone's own contacts and the colleagues of the PBX in one list.
struct ContactsView: View {
    @ObservedObject var model: FSVoipAppModel
    @ObservedObject private var hub: ContactsHub

    @State private var query = ""
    @State private var filter: ContactFilter = .all
    @State private var sections: [ContactSection] = []
    @State private var showsSources = false
    @State private var showsNew = false
    #if DEBUG
    /// Demo mode only (`-FSVoipDemoContactsScreen detail|edit|new|sources`): open a sub screen without tapping, for screenshots.
    @State private var demoEntryId: String?
    @State private var demoOpensDetail = false
    @State private var demoHandled = false
    #endif

    init(model: FSVoipAppModel) {
        self.model = model
        hub = model.contacts
    }

    private var isEmptyEverywhere: Bool {
        hub.entries.isEmpty
    }

    private var isSyncFailing: Bool {
        hub.accountStates.values.contains { $0.isEnabled && $0.lastSyncFailed }
    }

    private var canAdd: Bool {
        !hub.writableAccountIds.isEmpty
    }

    var body: some View {
        Group {
            if isEmptyEverywhere, query.isEmpty {
                emptyState
            } else {
                list
            }
        }
        .navigationTitle(L10n.string("contacts.title"))
        .toolbar { toolbar }
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: L10n.string("contacts.search"))
        .refreshable { await model.syncContacts(force: true) }
        .sheet(isPresented: $showsSources) {
            ContactSourcesView(model: model)
        }
        .sheet(isPresented: $showsNew) {
            ContactEditView(model: model, mode: .new)
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
        .onChange(of: hub.revision) { _ in recompute() }
    }

    // MARK: List

    private var list: some View {
        List {
            if isSyncFailing {
                syncBanner
            }

            if filter != .all {
                activeFilter
            }

            ForEach(sections) { section in
                Section {
                    ForEach(section.entries) { entry in
                        NavigationLink {
                            ContactDetailView(model: model, entryId: entry.id)
                        } label: {
                            ContactRow(entry: entry)
                        }
                    }
                } header: {
                    Text(String(section.letter))
                }
            }

            if sections.isEmpty {
                noResults
            } else {
                Text(ContactFailure.countText(sections.reduce(0) { $0 + $1.entries.count }))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
                    .listRowSeparator(.hidden)
                    .accessibilityIdentifier("contacts-count")
            }
        }
        .listStyle(.plain)
        .accessibilityIdentifier("contacts-list")
    }

    private var syncBanner: some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(Brand.amber)
                .accessibilityHidden(true)
            Text(L10n.string("contacts.syncFailed"))
                .font(.footnote)
            Spacer(minLength: 4)
            Button(L10n.string("action.retry")) {
                Task { await model.syncContacts(force: true) }
            }
            .font(.footnote.weight(.semibold))
        }
        .accessibilityElement(children: .combine)
        .listRowSeparator(.hidden)
    }

    private var activeFilter: some View {
        HStack {
            Label(filterTitle(filter), systemImage: "line.3.horizontal.decrease.circle.fill")
                .font(.subheadline.weight(.semibold))
            Spacer()
            Button(L10n.string("action.close")) { filter = .all }
                .font(.subheadline)
        }
        .listRowSeparator(.hidden)
        .accessibilityElement(children: .combine)
    }

    private var noResults: some View {
        VStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 30))
                .foregroundStyle(.tertiary)
                .accessibilityHidden(true)
            L10n.text("contacts.noResults.title")
                .font(.headline)
            Text(query.isEmpty ? "" : String(format: L10n.string("contacts.noResults.body"), query))
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
        .listRowSeparator(.hidden)
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            if hub.syncingAccountIds.isEmpty {
                Image(systemName: "person.2")
                    .font(.system(size: 34))
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
                L10n.text("contacts.empty.title")
                    .font(.headline)
                L10n.text("contacts.empty.body")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                VStack(spacing: 10) {
                    if canAdd {
                        Button(L10n.string("contacts.empty.add")) { showsNew = true }
                            .buttonStyle(PrimaryButtonStyle())
                    }

                    Button(L10n.string("contacts.empty.sources")) { showsSources = true }
                        .buttonStyle(SecondaryButtonStyle())
                }
                .padding(.top, 12)
                .padding(.horizontal, 20)
            } else {
                ProgressView()
                L10n.text("contacts.syncing")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityIdentifier("contacts-empty")
    }

    // MARK: Toolbar and filter

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .navigationBarLeading) {
            Menu {
                filterMenu
            } label: {
                Image(systemName: filter == .all ? "line.3.horizontal.decrease.circle" : "line.3.horizontal.decrease.circle.fill")
            }
            .accessibilityLabel(L10n.string("contacts.filter"))
            .accessibilityIdentifier("contacts-filter")
        }

        ToolbarItemGroup(placement: .navigationBarTrailing) {
            Button {
                showsSources = true
            } label: {
                Image(systemName: "slider.horizontal.3")
            }
            .accessibilityLabel(L10n.string("contacts.sources"))
            .accessibilityIdentifier("contacts-sources")

            if canAdd {
                Button {
                    showsNew = true
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel(L10n.string("contacts.add"))
                .accessibilityIdentifier("contacts-add")
            }
        }
    }

    @ViewBuilder
    private var filterMenu: some View {
        filterButton(.all)

        if hub.entries.contains(where: { $0.source == .customer }) {
            filterButton(.customer)
        }

        if hub.usesDeviceContacts {
            filterButton(.device)
        }

        if hub.entries.contains(where: { $0.source == .internalExtensions }) {
            filterButton(.colleagues)
        }

        let lists = listFilters

        if !lists.isEmpty {
            Section(L10n.string("contacts.filter.lists")) {
                ForEach(lists, id: \.self) { filterButton($0) }
            }
        }
    }

    private func filterButton(_ option: ContactFilter) -> some View {
        Button {
            filter = option
        } label: {
            if filter == option {
                Label(filterTitle(option), systemImage: "checkmark")
            } else {
                Text(filterTitle(option))
            }
        }
    }

    private var listFilters: [ContactFilter] {
        model.accounts.flatMap { account in
            (hub.accountStates[account.id].map { $0.isEnabled ? $0.lists.filter(\.isEnabled) : [] } ?? [])
                .map { ContactFilter.list(accountId: account.id, listId: $0.id) }
        }
    }

    private func filterTitle(_ option: ContactFilter) -> String {
        switch option {
        case .all: return L10n.string("contacts.filter.all")
        case .customer: return L10n.string("contacts.filter.customer")
        case .device: return L10n.string("contacts.filter.device")
        case .colleagues: return L10n.string("contacts.filter.colleagues")
        case let .list(accountId, listId):
            let name = hub.accountStates[accountId]?.lists.first { $0.id == listId }?.name ?? ""

            return model.accounts.count > 1 ? "\(name) · \(model.account(id: accountId)?.displayLabel ?? "")" : name
        }
    }

    private func recompute() {
        // A list that disappeared from the server (or was switched off) cannot stay selected.
        if case let .list(accountId, listId) = filter, hub.accountStates[accountId]?.lists.first(where: { $0.id == listId && $0.isEnabled }) == nil {
            filter = .all
        }

        sections = ContactBrowsing.sections(entries: hub.entries, filter: filter, query: query) { accountId, listId in
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
        default:
            demoEntryId = hub.entries.first { $0.name == "Pieter de Groot" }?.id
            demoOpensDetail = demoEntryId != nil
        }
    }
    #endif
}

struct ContactRow: View {
    let entry: ContactEntry

    var body: some View {
        HStack(spacing: 12) {
            ContactAvatar(name: entry.displayName)

            VStack(alignment: .leading, spacing: 2) {
                Text(entry.displayName)
                    .font(.body.weight(.medium))
                    .lineLimit(1)

                if !entry.subtitle.isEmpty {
                    Text(entry.subtitle)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }

            Spacer(minLength: 8)

            if let tag = entry.sourceTag {
                Text(tag)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(Capsule().fill(Color(.secondarySystemFill)))
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("contact-row")
    }
}
