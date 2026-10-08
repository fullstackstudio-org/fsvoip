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
                LineSelector(model: model, selectedId: accountId) { chosenAccountId = $0 }
                    .padding(.top, 8)

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
                .padding(.bottom, 28)
            }
            .frame(maxWidth: .infinity)
        }
        .navigationTitle(L10n.string("tab.dialer"))
        .toolbar(.hidden, for: .navigationBar)
    }

    private var numberDisplay: some View {
        VStack(spacing: 6) {
            Text(input.isEmpty ? " " : input.number)
                .font(Brand.digits(input.number.count > 13 ? 30 : 40))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .padding(.horizontal, 24)
                .accessibilityIdentifier("dialed-number")
                .accessibilityLabel(input.isEmpty ? L10n.string("dialer.empty") : input.number)

            if let name = model.name(forNumber: input.number) {
                Text(name)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else if input.isEmpty {
                L10n.text("dialer.hint")
                    .font(.subheadline)
                    .foregroundStyle(.tertiary)
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
        let byHeight = (size.height - 120) / 6.4
        let byWidth = (size.width - 96) / 3

        return max(56, min(84, byHeight, byWidth))
    }
}

/// Which line (paired account) the call goes out on, with its light.
struct LineSelector: View {
    @ObservedObject var model: FSVoipAppModel
    let selectedId: String?
    let select: (String) -> Void

    var body: some View {
        if let selectedId, let account = model.account(id: selectedId) {
            if model.accounts.count > 1 {
                Menu {
                    ForEach(model.accounts) { option in
                        Button {
                            select(option.id)
                        } label: {
                            Label(option.displayLabel + " · " + model.registration(for: option.id).label, systemImage: option.id == selectedId ? "checkmark" : "phone")
                        }
                    }
                } label: {
                    chip(account, chevron: true)
                }
                .accessibilityIdentifier("line-selector")
            } else {
                chip(account, chevron: false)
            }
        }
    }

    private func chip(_ account: StoredAccount, chevron: Bool) -> some View {
        let state = model.registration(for: account.id)

        return HStack(spacing: 8) {
            StatusLight(state: state)
            Text(account.displayLabel)
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
                .foregroundStyle(.primary)

            if state != .registered {
                Text(state.label)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            if chevron {
                Image(systemName: "chevron.up.chevron.down")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 9)
        .background(Color(.secondarySystemBackground), in: Capsule())
        .accessibilityElement(children: .combine)
        .accessibilityLabel(String(format: L10n.string("dialer.lineAccessibility"), account.displayLabel, state.label))
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
