// SPDX-License-Identifier: AGPL-3.0-or-later
import CallController
import SipEngine
import SwiftUI

/// The call screen inside the app. Every button goes through the system call UI (CallKit), so the lock screen, the
/// green status pill and this screen always agree.
struct InCallView: View {
    @ObservedObject var model: FSVoipAppModel
    @ObservedObject var phone: PhoneController
    let session: CallSession
    @State private var showsKeypad = false
    @State private var sentDigits = ""
    @State private var isParking = false

    private var isEnded: Bool {
        session.phase.isEnded
    }

    var body: some View {
        ZStack {
            LinearGradient(colors: [Brand.inkRaised, Brand.ink], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()

            VStack(spacing: 0) {
                header
                    .padding(.top, 28)

                Spacer(minLength: 16)

                if session.phase == .incoming {
                    incomingActions
                } else if showsKeypad {
                    dtmfKeypad
                } else {
                    controls
                }

                Spacer(minLength: 16)

                if session.phase != .incoming {
                    endButton
                        .padding(.bottom, 24)
                }
            }
            .padding(.horizontal, 28)
        }
        .foregroundStyle(.white)
        .environment(\.colorScheme, .dark)
        .accessibilityIdentifier("in-call-screen")
        .onChange(of: session.id) { _ in
            showsKeypad = false
            sentDigits = ""
        }
    }

    // MARK: Header

    private var header: some View {
        VStack(spacing: 10) {
            // Which line this call is on: the signature of the app, also in a call.
            HStack(spacing: 7) {
                Circle()
                    .fill(isEnded ? Color.white.opacity(0.3) : Brand.lime)
                    .frame(width: 7, height: 7)
                Text(String(format: L10n.string(session.direction == .incoming ? "call.line.incoming" : "call.line.outgoing"), session.accountLabel))
                    .font(.footnote.weight(.semibold))
                    .lineLimit(1)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Color.white.opacity(0.08), in: Capsule())

            Text(session.remoteTitle ?? L10n.string("call.anonymous"))
                .font(.system(size: 34, weight: .semibold))
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .minimumScaleFactor(0.6)
                .padding(.top, 14)
                .accessibilityIdentifier("call-title")

            if let number = session.remoteNumber, session.remoteName != nil {
                Text(number)
                    .font(.body)
                    .monospacedDigit()
                    .foregroundStyle(.white.opacity(0.6))
            }

            status
                .font(.body.monospacedDigit())
                .foregroundStyle(.white.opacity(0.7))
                .padding(.top, 2)

            // The number this outgoing call goes out with, when the user chose one in the dialler.
            if session.direction == .outgoing, let via = session.viaNumber {
                Text(String(format: L10n.string("call.via"), PbxVocabulary.formatNumber(via)))
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(.white.opacity(0.6))
                    .accessibilityIdentifier("call-via")
            }

            if showsKeypad, !sentDigits.isEmpty {
                Text(sentDigits)
                    .font(Brand.digits(26))
                    .lineLimit(1)
                    .truncationMode(.head)
                    .padding(.top, 6)
            }
        }
    }

    @ViewBuilder
    private var status: some View {
        switch session.phase {
        case .starting:
            L10n.text("call.status.starting")
        case .ringing:
            L10n.text("call.status.ringing")
        case .incoming:
            L10n.text("call.status.incoming")
        case .connecting:
            L10n.text("call.status.connecting")
        case .active:
            if let start = session.connectedAt {
                Text(timerInterval: start ... Date.distantFuture, countsDown: false)
            } else {
                L10n.text("call.status.connecting")
            }
        case .held:
            L10n.text("call.status.held")
        case .heldByRemote:
            L10n.text("call.status.heldByRemote")
        case let .ended(reason):
            Text(Self.endText(reason))
        }
    }

    // MARK: Controls

    private var controls: some View {
        let connected = session.phase.isConnected || session.phase == .connecting

        // A plain Grid, not a LazyVGrid: lazy items are placed on their own and did not slide up with the call screen.
        return Grid(horizontalSpacing: 24, verticalSpacing: 28) {
            GridRow {
                ControlButton(symbol: session.isMuted ? "mic.slash.fill" : "mic.fill", titleKey: "call.mute", isOn: session.isMuted) {
                    phone.setMuted(session.id, !session.isMuted)
                }
                .accessibilityIdentifier("mute-button")

                ControlButton(symbol: "circle.grid.3x3.fill", titleKey: "call.keypad", isOn: false) {
                    showsKeypad = true
                }
                .disabled(!connected)
                .accessibilityIdentifier("keypad-button")
            }

            GridRow {
                ControlButton(symbol: phone.isSpeakerOn ? "speaker.wave.3.fill" : "speaker.wave.2.fill", titleKey: "call.speaker", isOn: phone.isSpeakerOn) {
                    phone.setSpeaker(!phone.isSpeakerOn)
                }
                .accessibilityIdentifier("speaker-button")

                ControlButton(symbol: "pause.fill", titleKey: "call.hold", isOn: session.isOnHold) {
                    phone.setHeld(session.id, !session.isOnHold)
                }
                .disabled(!session.phase.isConnected)
                .accessibilityIdentifier("hold-button")
            }

            // Parking: put the call on a numbered slot for the whole team. Only when the PBX offers it and the call is connected.
            if model.canPark(session.accountId.rawValue) {
                GridRow {
                    ControlButton(symbol: "parkingsign", titleKey: "call.park", isOn: isParking) {
                        park()
                    }
                    .disabled(!session.phase.isConnected || isParking)
                    .accessibilityIdentifier("park-button")
                    .accessibilityHint(L10n.string("call.park.hint"))

                    Color.clear
                        .gridCellUnsizedAxes([.horizontal, .vertical])
                }
            }
        }
        .frame(maxWidth: .infinity)
        .disabled(isEnded)
        .opacity(isEnded ? 0.4 : 1)
    }

    private func park() {
        guard !isParking else { return }

        isParking = true

        Task {
            await model.parkCall(session)
            isParking = false
        }
    }

    private var dtmfKeypad: some View {
        VStack(spacing: 20) {
            Keypad(keySize: 70, onKey: { key in
                Haptics.tap()
                sentDigits.append(key)
                phone.sendDTMF(session.id, String(key))
            })
            .environment(\.colorScheme, .dark)

            Button(L10n.string("call.keypad.hide")) {
                showsKeypad = false
            }
            .font(.callout.weight(.semibold))
            .foregroundStyle(.white.opacity(0.8))
        }
    }

    private var incomingActions: some View {
        HStack {
            RoundAction(symbol: "phone.down.fill", titleKey: "call.decline", fill: Brand.hangUp, glyph: .white) {
                phone.hangUp(session.id)
            }
            .accessibilityIdentifier("decline-button")

            Spacer()

            RoundAction(symbol: "phone.fill", titleKey: "call.answer", fill: Brand.lime, glyph: Brand.ink) {
                phone.answer(session.id)
            }
            .accessibilityIdentifier("answer-button")
        }
        .padding(.horizontal, 12)
    }

    private var endButton: some View {
        RoundAction(symbol: "phone.down.fill", titleKey: "call.end", fill: Brand.hangUp, glyph: .white) {
            phone.hangUp(session.id)
        }
        .disabled(isEnded)
        .opacity(isEnded ? 0.4 : 1)
        .accessibilityIdentifier("end-button")
    }

    static func endText(_ reason: CallEndReason) -> String {
        switch reason {
        case .busy:
            return L10n.string("call.ended.busy")
        case .unanswered:
            return L10n.string("call.ended.unanswered")
        case .failed:
            return L10n.string("call.ended.failed")
        case .declined, .localHangup, .remoteHangup:
            return L10n.string("call.ended")
        }
    }
}

private struct ControlButton: View {
    let symbol: String
    let titleKey: LocalizedStringKey
    let isOn: Bool
    let action: () -> Void
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: symbol)
                    .font(.system(size: 26, weight: .medium))
                    .foregroundStyle(isOn ? Brand.ink : .white)
                    .frame(width: 72, height: 72)
                    .background(Circle().fill(isOn ? Color.white : Color.white.opacity(0.12)))
                L10n.text(titleKey)
                    .font(.footnote)
                    .foregroundStyle(.white.opacity(0.85))
            }
            .opacity(isEnabled ? 1 : 0.35)
        }
        .buttonStyle(.plain)
        // Equal columns, as the grid had before; only the button itself takes the tap.
        .frame(maxWidth: .infinity)
        .accessibilityAddTraits(isOn ? .isSelected : [])
    }
}

private struct RoundAction: View {
    let symbol: String
    let titleKey: LocalizedStringKey
    let fill: Color
    let glyph: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: symbol)
                    .font(.system(size: 30, weight: .semibold))
                    .foregroundStyle(glyph)
                    .frame(width: 76, height: 76)
                    .background(Circle().fill(fill))
                L10n.text(titleKey)
                    .font(.footnote)
                    .foregroundStyle(.white.opacity(0.85))
            }
        }
        .buttonStyle(.plain)
    }
}
