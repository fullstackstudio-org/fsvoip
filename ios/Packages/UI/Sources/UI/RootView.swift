// SPDX-License-Identifier: AGPL-3.0-or-later
import CallController
import Core
import SwiftUI

public struct RootView: View {
    @ObservedObject private var model: FSVoipAppModel
    @ObservedObject private var phone: PhoneController

    public init(model: FSVoipAppModel) {
        self.model = model
        phone = model.phone
    }

    private var callOnScreen: CallSession? {
        phone.activeSession ?? phone.lastEnded
    }

    public var body: some View {
        ZStack {
            Group {
                if model.accounts.isEmpty {
                    OnboardingView(model: model)
                } else {
                    MainTabView(model: model)
                }
            }
            .sheet(isPresented: pairingSheetPresented) {
                PairingSheet(model: model)
            }

            if let session = callOnScreen {
                InCallView(model: model, phone: phone, session: session)
                    .transition(.move(edge: .bottom))
                    .zIndex(1)
            }
        }
        .animation(.spring(response: 0.38, dampingFraction: 0.9), value: callOnScreen?.id)
        .overlay(alignment: .top) {
            if let notice = model.notice {
                NoticeBanner(notice: notice) { model.notice = nil }
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .task(id: notice.id) {
                        try? await Task.sleep(nanoseconds: 5_000_000_000)

                        if model.notice?.id == notice.id {
                            model.notice = nil
                        }
                    }
            }
        }
        .animation(.easeOut(duration: 0.25), value: model.notice)
        .onChange(of: phone.activeSession?.id) { id in
            // A call takes the screen: put the scanner away.
            if id != nil, model.isScannerPresented, !model.pairing.isActive {
                model.isScannerPresented = false
            }
        }
    }

    private var pairingSheetPresented: Binding<Bool> {
        Binding(
            get: { model.isScannerPresented || model.pairing.isActive },
            set: { presented in
                if !presented {
                    model.isScannerPresented = false
                    model.closePairing()
                }
            }
        )
    }
}

struct MainTabView: View {
    @ObservedObject var model: FSVoipAppModel

    var body: some View {
        TabView(selection: $model.selectedTab) {
            NavigationStack {
                DialerView(model: model)
            }
            .tabItem { Label(L10n.string("tab.dialer"), systemImage: "circle.grid.3x3.fill") }
            .tag(FSVoipAppModel.Tab.dialer)

            NavigationStack {
                RecentsView(model: model)
            }
            .tabItem { Label(L10n.string("tab.recents"), systemImage: "clock.fill") }
            .tag(FSVoipAppModel.Tab.recents)

            NavigationStack {
                SettingsView(model: model)
            }
            .tabItem { Label(L10n.string("tab.settings"), systemImage: "gearshape.fill") }
            .tag(FSVoipAppModel.Tab.settings)
        }
        .tint(Color.primary)
    }
}
