// SPDX-License-Identifier: AGPL-3.0-or-later
import CallController
import Core
import SipEngine
import SwiftUI
import UIKit

struct DialerView: View {
    @ObservedObject var model: FSVoipAppModel
    @State private var input = DialerInput()
    @State private var chosenAccountId: String?
    @State private var showsChooser = false

    private var accountId: String? {
        if let chosenAccountId, model.account(id: chosenAccountId) != nil {
            return chosenAccountId
        }

        return model.defaultOutgoingAccountId
    }

    var body: some View {
        GeometryReader { geometry in
            let key = Self.keySize(for: geometry.size)

            VStack(spacing: 0) {
                if let account = accountId.flatMap({ model.account(id: $0) }) {
                    UnavailableBanner(model: model, account: account)
                }

                Spacer(minLength: 8)

                numberDisplay
                    .frame(height: key * 1.15)

                Spacer(minLength: 8)

                Keypad(keySize: key) { key in
                    Haptics.tap()
                    input.press(key)
                } onLongPressZero: {
                    input.longPressZero()
                }

                HStack {
                    Color.clear.frame(width: key, height: key)
                    Spacer()
                    CallButton(size: key) { placeCall() }
                        .disabled(accountId == nil)
                        .accessibilityIdentifier("call-button")
                    Spacer()
                    deleteButton(size: key)
                }
                .frame(width: key * 3 + Keypad.spacing(for: key) * 2)
                .padding(.top, Keypad.spacing(for: key))

                if let account = accountId.flatMap({ model.account(id: $0) }) {
                    OutboundBar(model: model, outbound: model.outbound, account: account, opensChooser: { showsChooser = true })
                        .padding(.horizontal, Theme.Spacing.l)
                        .padding(.top, Theme.Spacing.m)
                }

                Spacer().frame(height: Theme.Spacing.l)
            }
            .frame(maxWidth: .infinity)
        }
        .background(Theme.background)
        .navigationTitle(L10n.string("tab.dialer"))
        .task(id: accountId.map { "\($0)/\(model.capabilities(for: $0)?.callerChoice == true)" }) {
            // Names and the default of the numbers; only asked when the PBX supports the choice, and never while dialling.
            if let account = accountId.flatMap({ model.account(id: $0) }) {
                await model.outbound.load(account)
            }
        }
        .sheet(isPresented: $showsChooser) {
            OutboundChooserSheet(model: model, outbound: model.outbound, accountId: accountId) { chosenAccountId = $0 }
        }
    }

    private var numberDisplay: some View {
        VStack(spacing: 6) {
            Text(input.isEmpty ? L10n.string("dialer.placeholder") : input.number)
                .font(input.isEmpty ? .body : Brand.digits(input.number.count > 13 ? 30 : 40))
                .foregroundStyle(input.isEmpty ? Theme.textTertiary : Theme.textPrimary)
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .frame(maxWidth: .infinity, minHeight: input.isEmpty ? 44 : 0)
                .padding(.horizontal, Theme.Spacing.m)
                .background(input.isEmpty ? Theme.raised : Color.clear, in: Theme.card(Theme.Radius.s))
                .padding(.horizontal, 24)
                .accessibilityIdentifier("dialed-number")
                .accessibilityLabel(input.isEmpty ? L10n.string("dialer.empty") : input.number)

            if let name = model.name(forNumber: input.number) {
                Text(name)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else {
                Text(" ").font(.subheadline)
            }
        }
        .frame(maxWidth: .infinity)
        .contentShape(Rectangle())
        .contextMenu {
            Button {
                if let text = UIPasteboard.general.string {
                    input.paste(text)
                }
            } label: {
                Label(L10n.string("dialer.paste"), systemImage: "doc.on.clipboard")
            }

            if !input.isEmpty {
                Button {
                    UIPasteboard.general.string = input.number
                } label: {
                    Label(L10n.string("dialer.copy"), systemImage: "doc.on.doc")
                }
            }
        }
    }

    private func deleteButton(size: CGFloat) -> some View {
        Button {
            Haptics.tap()
            input.deleteLast()
        } label: {
            Image(systemName: "delete.left")
                .font(.system(size: size * 0.3, weight: .regular))
                .foregroundStyle(.secondary)
                .frame(width: size, height: size)
        }
        .opacity(input.isEmpty ? 0 : 1)
        .disabled(input.isEmpty)
        .simultaneousGesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in input.clear() })
        .accessibilityLabel(L10n.string("dialer.delete"))
        .accessibilityIdentifier("delete-button")
    }

    private func placeCall() {
        if input.isEmpty {
            // Like a desk phone: the call button on an empty display brings back the last number dialled.
            if let last = model.recents.first(where: { $0.direction == .outgoing && !$0.number.isEmpty }) {
                input.paste(last.number)
            }

            return
        }

        if model.call(input.number, from: accountId) {
            input.clear()
        } else {
            Haptics.warning()
        }
    }

    static func keySize(for size: CGSize) -> CGFloat {
        // Fit four rows of keys plus the call row and the display into the height; never wider than the screen.
        let byHeight = (size.height - 190) / 6.4
        let byWidth = (size.width - 96) / 3

        return max(56, min(84, byHeight, byWidth))
    }
}

/// Under the call button, always there: the number this call goes out with (`[icoon] [Naam · nummer ⌄]`). Tapping it opens the chooser.
/// Without the capability, or with a single number, it is a label; there is no chevron then and nothing to open.
struct OutboundBar: View {
    @ObservedObject var model: FSVoipAppModel
    @ObservedObject var outbound: OutboundChoiceModel
    let account: StoredAccount
    let opensChooser: () -> Void

    private var state: RegistrationState {
        model.registration(for: account.id)
    }

    private var choosable: Bool {
        outbound.canChoose(account.id) || model.accounts.count > 1
    }

    var body: some View {
        VStack(spacing: Theme.Spacing.xs) {
            if choosable {
                Button(action: opensChooser) { content(chevron: true) }
                    .buttonStyle(.plain)
                    .accessibilityHint(L10n.string("outbound.bar.hint"))
                    .accessibilityIdentifier("outbound-bar")
            } else {
                content(chevron: false)
                    .accessibilityIdentifier("outbound-bar")
            }

            if state != .registered {
                Text(state.label)
                    .font(.footnote)
                    .foregroundStyle(Theme.textSecondary)
                    .lineLimit(2)
            }
        }
        .frame(maxWidth: .infinity)
    }

    private func content(chevron: Bool) -> some View {
        HStack(spacing: Theme.Spacing.s) {
            Image(systemName: "phone.arrow.up.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Theme.accentText)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 0) {
                if model.accounts.count > 1 {
                    Text(account.displayLabel)
                        .font(.caption)
                        .foregroundStyle(Theme.textSecondary)
                        .lineLimit(1)
                }

                Text(outbound.label(account.id))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.textPrimary)
                    .monospacedDigit()
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if chevron {
                Image(systemName: "chevron.up.chevron.down")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(Theme.textSecondary)
                    .accessibilityHidden(true)
            }
        }
        .padding(.horizontal, Theme.Spacing.m)
        .frame(maxWidth: .infinity, minHeight: Theme.minimumTarget, alignment: .leading)
        .background(Theme.raised, in: Theme.card(Theme.Radius.s))
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel(String(format: L10n.string("outbound.bar.accessibility"), outbound.label(account.id)))
    }
}

/// The sheet behind the bar: with several accounts first the account, then the number. Choosing a number sets it for the next
/// call of that account and closes the sheet; no network, no waiting.
struct OutboundChooserSheet: View {
    @ObservedObject var model: FSVoipAppModel
    @ObservedObject var outbound: OutboundChoiceModel
    let accountId: String?
    let selectAccount: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        SheetShell(title: L10n.string("outbound.sheet.title"), onClose: { dismiss() }) {
            VStack(alignment: .leading, spacing: Theme.Spacing.l) {
                if model.accounts.count > 1 {
                    SettingsGroup(title: L10n.string("outbound.sheet.account")) {
                        ForEach(model.accounts) { account in
                            ChoiceRow(
                                title: account.displayLabel,
                                subtitle: model.registration(for: account.id).label,
                                isSelected: account.id == accountId
                            ) {
                                selectAccount(account.id)
                                Task { await outbound.load(account) }
                            }
                            .accessibilityIdentifier("outbound-account-\(account.id)")
                        }
                    }
                }

                if let accountId, outbound.canChoose(accountId) {
                    SettingsGroup(title: L10n.string("outbound.sheet.number"), footer: L10n.string("outbound.sheet.footer")) {
                        ForEach(outbound.numbers(for: accountId)) { number in
                            ChoiceRow(title: OutboundChoiceModel.title(for: number), isSelected: outbound.selected(accountId)?.number == number.number) {
                                outbound.select(number.number, accountId: accountId)
                                dismiss()
                            }
                            .accessibilityIdentifier("outbound-number-\(number.number)")
                        }
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

/// "Je bent niet beschikbaar": do-not-disturb is on, so incoming calls do not reach this phone. One tap makes you available again.
struct UnavailableBanner: View {
    @ObservedObject var model: FSVoipAppModel
    let account: StoredAccount

    var body: some View {
        if let hub = model.availability {
            Content(hub: hub, account: account)
        }
    }

    private struct Content: View {
        @ObservedObject var hub: AvailabilityHub
        let account: StoredAccount

        var body: some View {
            if hub.isAvailable(account.id) == false {
                HStack(spacing: Theme.Spacing.m) {
                    Image(systemName: "moon.fill")
                        .foregroundStyle(Theme.danger)
                        .accessibilityHidden(true)

                    VStack(alignment: .leading, spacing: 2) {
                        L10n.text("dialer.unavailable.title")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Theme.textPrimary)
                        L10n.text("dialer.unavailable.message")
                            .font(.footnote)
                            .foregroundStyle(Theme.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    Spacer(minLength: Theme.Spacing.s)

                    Button {
                        Task { await hub.setAvailable(true, account: account) }
                    } label: {
                        L10n.text("dialer.unavailable.action")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Theme.accentText)
                            .frame(minHeight: Theme.minimumTarget)
                    }
                    .disabled(hub.saving.contains(account.id))
                    .accessibilityIdentifier("unavailable-action")
                }
                .padding(.horizontal, Theme.Spacing.m)
                .padding(.vertical, Theme.Spacing.s)
                .background(Theme.raised, in: Theme.card(Theme.Radius.s))
                .overlay(Theme.card(Theme.Radius.s).strokeBorder(Theme.danger.opacity(0.5), lineWidth: 1))
                .accessibilityElement(children: .contain)
                .padding(.horizontal, Theme.Spacing.l)
                .padding(.top, Theme.Spacing.s)
                .accessibilityIdentifier("unavailable-banner")
            }
        }
    }
}

struct Keypad: View {
    let keySize: CGFloat
    let onKey: (Character) -> Void
    var onLongPressZero: (() -> Void)?

    static let rows: [[Character]] = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], ["*", "0", "#"]]

    static func spacing(for key: CGFloat) -> CGFloat {
        key * 0.28
    }

    var body: some View {
        VStack(spacing: Self.spacing(for: keySize) * 0.6) {
            ForEach(Self.rows, id: \.self) { row in
                HStack(spacing: Self.spacing(for: keySize)) {
                    ForEach(row, id: \.self) { key in
                        KeypadKey(key: key, size: keySize) { onKey(key) }
                            .simultaneousGesture(
                                LongPressGesture(minimumDuration: 0.45).onEnded { _ in
                                    if key == "0", let onLongPressZero {
                                        Haptics.tap()
                                        onLongPressZero()
                                    }
                                }
                            )
                    }
                }
            }
        }
    }
}

struct KeypadKey: View {
    let key: Character
    let size: CGFloat
    var dark = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: -2) {
                Text(String(key))
                    .font(Brand.digits(key == "*" ? size * 0.5 : size * 0.42))
                    .offset(y: key == "*" ? size * 0.06 : 0)
                let letters = KeypadLetters.letters(for: key)
                Text(letters.isEmpty ? " " : letters)
                    .font(.system(size: size * 0.13, weight: .semibold, design: .rounded))
                    .tracking(size * 0.025)
                    .opacity(letters.isEmpty ? 0 : 0.7)
            }
            .foregroundStyle(dark ? Color.white : Color.primary)
            .frame(width: size, height: size)
            .background(Circle().fill(dark ? Color.white.opacity(0.12) : Color(.secondarySystemFill)))
        }
        .buttonStyle(KeyPressStyle())
        .accessibilityLabel(String(key))
        .accessibilityIdentifier("key-\(key)")
    }
}

private struct KeyPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .brightness(configuration.isPressed ? 0.12 : 0)
            .scaleEffect(configuration.isPressed ? 0.94 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// The lime call button: the one place the accent is at full strength.
struct CallButton: View {
    let size: CGFloat
    let action: () -> Void
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        Button(action: action) {
            Image(systemName: "phone.fill")
                .font(.system(size: size * 0.38, weight: .semibold))
                .foregroundStyle(Brand.ink)
                .frame(width: size, height: size)
                .background(Circle().fill(Brand.lime))
                .opacity(isEnabled ? 1 : 0.4)
        }
        .buttonStyle(KeyPressStyle())
        .accessibilityLabel(L10n.string("dialer.call"))
    }
}
