// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

// MARK: - Notices at the top of a screen

/// What the user needs to know before touching anything: frozen, not current, still being applied, or a message.
struct PbxNotices: View {
    @ObservedObject var model: PbxSectionModel

    var body: some View {
        if model.isReadOnly {
            PbxNotice(symbol: "lock.fill", tint: .secondary, title: L10n.string("pbx.readOnly.title"), message: L10n.string("pbx.readOnly.message"))
                .accessibilityIdentifier("pbx-readonly-banner")
        }

        if model.isOutdated {
            PbxNotice(symbol: "wifi.slash", tint: Brand.amber, title: L10n.string("pbx.outdated.title"), message: L10n.string("pbx.outdated.message"))
                .accessibilityIdentifier("pbx-outdated-banner")
        }

        if model.hasPendingSync {
            HStack(spacing: 12) {
                ProgressView()
                VStack(alignment: .leading, spacing: 2) {
                    Text(L10n.string("pbx.pending.title"))
                        .font(.subheadline.weight(.semibold))
                    Text(L10n.string("pbx.pending.message"))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("pbx-pending-banner")
        } else if model.syncTimedOut {
            PbxNotice(symbol: "hourglass", tint: Brand.amber, title: L10n.string("pbx.pending.timeout.title"), message: L10n.string("pbx.pending.timeout.message"))
        }

        if let banner = model.banner {
            PbxNotice(symbol: banner.isError ? "exclamationmark.circle.fill" : "info.circle.fill", tint: banner.isError ? Brand.hangUp : Color.secondary, title: banner.text, message: nil)
                .accessibilityIdentifier("pbx-banner")
        }

        if let failure = model.loadFailure {
            PbxNotice(symbol: "exclamationmark.circle.fill", tint: Brand.hangUp, title: failure.message, message: nil)
        }
    }
}

/// The notices as their own list section, only when there is something to say (an empty section is a gap).
struct PbxNoticesSection: View {
    @ObservedObject var model: PbxSectionModel

    var body: some View {
        if model.hasNotices {
            Section {
                PbxNotices(model: model)
            }
        }
    }
}

struct PbxNotice: View {
    let symbol: String
    let tint: Color
    let title: String
    let message: String?

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol)
                .foregroundStyle(tint)
                .frame(width: 22)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .fixedSize(horizontal: false, vertical: true)
                if let message {
                    Text(message)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// An inline error of a form ("these numbers are blocked").
struct PbxFormError: View {
    let failure: PbxFailure?

    var body: some View {
        if let failure {
            Section {
                PbxNotice(symbol: "exclamationmark.circle.fill", tint: Brand.hangUp, title: failure.message, message: nil)
            }
            .accessibilityIdentifier("pbx-form-error")
        }
    }
}

/// "Wordt bijgewerkt" next to an object whose latest settings the centrale has not applied yet.
struct PbxSyncBadge: View {
    let sync: SyncState

    var body: some View {
        switch sync {
        case .pending:
            Label(L10n.string("pbx.sync.pending"), systemImage: "arrow.triangle.2.circlepath")
                .font(.caption.weight(.medium))
                .foregroundStyle(Theme.busy)
        case .error:
            Label(L10n.string("pbx.sync.error"), systemImage: "exclamationmark.triangle.fill")
                .font(.caption.weight(.medium))
                .foregroundStyle(Theme.danger)
        case .ok, .unknown:
            EmptyView()
        }
    }
}

// MARK: - Choosing where a call goes

/// A row "label ... current choice >" that opens the picker.
struct TargetRow: View {
    let title: String
    @Binding var target: PbxTarget?
    let options: [PbxTargetOption]
    /// What nil reads as ("Niet ingesteld").
    var noneLabel = L10n.string("pbx.target.none")
    var allowsNone = true
    var footer: String?

    var body: some View {
        NavigationLink {
            TargetPickerView(title: title, selection: $target, options: options, noneLabel: noneLabel, allowsNone: allowsNone, footer: footer)
        } label: {
            LabeledContent(title) {
                Text(target.map { PbxVocabulary.describe($0, options: options) } ?? noneLabel)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.trailing)
            }
        }
    }
}

/// One list for every kind of destination: toestel, belgroep, wachtrij, keuzemenu, openingstijden, voicemail, an external
/// number or hang up.
struct TargetPickerView: View {
    let title: String
    @Binding var selection: PbxTarget?
    let options: [PbxTargetOption]
    var noneLabel: String
    var allowsNone: Bool
    var footer: String?

    @Environment(\.dismiss) private var dismiss
    @State private var externalNumber = ""
    @State private var typingNumber = false

    private var hasExternal: Bool {
        options.contains { $0.type == .external }
    }

    var body: some View {
        List {
            if allowsNone {
                Section {
                    choiceRow(noneLabel, selected: selection == nil) { choose(nil) }
                } footer: {
                    if let footer { Text(footer) }
                }
            }

            ForEach(PbxVocabulary.pickerOrder, id: \.self) { type in
                let group = options.filter { $0.type == type }

                if !group.isEmpty {
                    Section(PbxVocabulary.groupName(type)) {
                        ForEach(group, id: \.value) { option in
                            choiceRow(PbxVocabulary.name(of: option), selected: TargetChoice.option(for: selection, in: options)?.value == option.value) {
                                choose(TargetChoice.target(for: option))
                            }
                        }
                    }
                }
            }

            Section {
                choiceRow(L10n.string("pbx.type.hangup"), selected: selection?.type == .hangup) { choose(.hangup) }
            }

            if hasExternal {
                Section {
                    choiceRow(L10n.string("pbx.external.title"), selected: selection?.type == .external) {
                        typingNumber = true
                    }

                    if typingNumber || selection?.type == .external {
                        TextField(L10n.string("pbx.external.placeholder"), text: $externalNumber)
                            .keyboardType(.phonePad)
                            .textContentType(.telephoneNumber)
                            .accessibilityIdentifier("pbx-external-number")

                        Button(L10n.string("pbx.external.use")) {
                            let number = externalNumber.trimmingCharacters(in: .whitespacesAndNewlines)

                            if !number.isEmpty {
                                choose(.external(number))
                            }
                        }
                        .disabled(externalNumber.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                } footer: {
                    Text(L10n.string("pbx.external.footer"))
                }
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if selection?.type == .external {
                externalNumber = selection?.number ?? ""
            }
        }
    }

    private func choose(_ target: PbxTarget?) {
        selection = target
        dismiss()
    }

    private func choiceRow(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(label)
                    .foregroundStyle(.primary)
                    .multilineTextAlignment(.leading)
                Spacer()
                if selected {
                    Image(systemName: "checkmark")
                        .foregroundStyle(.primary)
                        .accessibilityHidden(true)
                }
            }
        }
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

// MARK: - Saving

/// The save button of a form: waits while the request runs.
struct PbxSaveButton: View {
    let isSaving: Bool
    let isEnabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            if isSaving {
                ProgressView()
            } else {
                Text(L10n.string("action.save"))
                    .bold()
            }
        }
        .disabled(!isEnabled || isSaving)
        .accessibilityIdentifier("pbx-save")
    }
}
