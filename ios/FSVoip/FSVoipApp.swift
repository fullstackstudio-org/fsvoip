// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI
import UI

@main
struct FSVoipApp: App {
    @StateObject private var model = FSVoipAppModel()
    // Composition root: the SIP engine and the system call UI. Created here, started in Task 5.
    private let services = AppServices()

    var body: some Scene {
        WindowGroup {
            RootView(model: model)
                // fsvoip://pair?t=...
                .onOpenURL { model.handleIncoming(url: $0) }
                // https://fullstackstudio.nl/fsvoip/pair?t=...  (universal link)
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                    if let url = activity.webpageURL {
                        model.handleIncoming(url: url)
                    }
                }
        }
    }
}
