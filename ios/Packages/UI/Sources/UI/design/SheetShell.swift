// SPDX-License-Identifier: AGPL-3.0-or-later
import SwiftUI

/// What the footer of a sheet does: `Annuleren | Opslaan`.
struct SheetFooter {
    var saveTitle: String = L10n.string("action.save")
    var cancelTitle: String = L10n.string("action.cancel")
    /// `false` while the form is incomplete or invalid.
    var canSave = true
    /// What Annuleren does; `nil` closes the sheet like the close button.
    var onCancel: (() -> Void)?
    var onSave: () -> Void
}

/// Whether leaving a sheet page needs the "discard changes?" question first.
enum SheetDismissal {
    enum Decision: Equatable {
        case proceed
        case confirmDiscard
    }

    /// Leaving while a save runs is not offered (the buttons are disabled); an untouched form just closes.
    static func decide(isDirty: Bool, isSaving: Bool) -> Decision {
        isDirty && !isSaving ? .confirmDiscard : .proceed
    }
}

/// The chrome of one page of a sheet: a bar with back (only when there is a step back), the title and close, a flat body, and an
/// optional fixed footer with Annuleren and Opslaan. Back, close and Annuleren all ask "Niet-opgeslagen wijzigingen weggooien?"
/// while `isDirty`.
struct SheetShell<Content: View>: View {
    let title: String
    var back: (() -> Void)?
    let onClose: () -> Void
    var footer: SheetFooter?
    var isSaving = false
    var isDirty = false
    @ViewBuilder let content: () -> Content

    @State private var pendingExit: (() -> Void)?

    var body: some View {
        VStack(spacing: 0) {
            bar
            ScrollView {
                content()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, Theme.Spacing.l)
                    .padding(.top, Theme.Spacing.s)
                    .padding(.bottom, Theme.Spacing.xl)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .background(Theme.sheet.ignoresSafeArea())
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if let footer {
                footerBar(footer)
            }
        }
        .interactiveDismissDisabled(isDirty)
        .confirmationDialog(L10n.string("sheet.discard.title"), isPresented: confirmationBinding, titleVisibility: .visible) {
            Button(L10n.string("sheet.discard.confirm"), role: .destructive) {
                let exit = pendingExit
                pendingExit = nil
                exit?()
            }
            Button(L10n.string("sheet.discard.keep"), role: .cancel) {
                pendingExit = nil
            }
        }
    }

    private var confirmationBinding: Binding<Bool> {
        Binding(get: { pendingExit != nil }, set: { if !$0 { pendingExit = nil } })
    }

    private func leave(_ exit: @escaping () -> Void) {
        switch SheetDismissal.decide(isDirty: isDirty, isSaving: isSaving) {
        case .proceed: exit()
        case .confirmDiscard: pendingExit = exit
        }
    }

    // MARK: Bar

    private var bar: some View {
        HStack(spacing: Theme.Spacing.s) {
            if let back {
                barButton(symbol: "chevron.left", label: L10n.string("action.back")) { leave(back) }
                    .accessibilityIdentifier("sheet-back")
            } else {
                Color.clear.frame(width: Theme.minimumTarget, height: Theme.minimumTarget)
            }

            Text(title)
                .font(.headline)
                .foregroundStyle(Theme.textPrimary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity)
                .accessibilityAddTraits(.isHeader)

            barButton(symbol: "xmark", label: L10n.string("action.close")) { leave(onClose) }
                .accessibilityIdentifier("sheet-close")
        }
        .padding(.horizontal, Theme.Spacing.s)
        .padding(.top, Theme.Spacing.s)
        .padding(.bottom, Theme.Spacing.xs)
    }

    private func barButton(symbol: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.body.weight(.semibold))
                .foregroundStyle(Theme.textPrimary)
                .frame(width: Theme.minimumTarget, height: Theme.minimumTarget)
                .contentShape(Rectangle())
        }
        .accessibilityLabel(label)
    }

    // MARK: Footer

    private func footerBar(_ footer: SheetFooter) -> some View {
        VStack(spacing: 0) {
            Rectangle().fill(Theme.separator).frame(height: 1)

            HStack(spacing: Theme.Spacing.m) {
                Button(footer.cancelTitle) { leave(footer.onCancel ?? onClose) }
                    .buttonStyle(SecondaryButtonStyle())
                    .disabled(isSaving)
                    .accessibilityIdentifier("sheet-cancel")

                Button {
                    footer.onSave()
                } label: {
                    if isSaving {
                        ProgressView().tint(Theme.onAccent)
                    } else {
                        Text(footer.saveTitle)
                    }
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(!footer.canSave || isSaving)
                .accessibilityIdentifier("sheet-save")
            }
            .padding(Theme.Spacing.l)
        }
        .background(Theme.sheet)
    }
}
