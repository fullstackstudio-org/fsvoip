// SPDX-License-Identifier: AGPL-3.0-or-later
import SwiftUI
import UI

@main
struct FSVoipApp: App {
    @StateObject private var holder = ModelHolder()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView(model: holder.model)
                // fsvoip://pair?t=...
                .onOpenURL { holder.model.handleIncoming(url: $0) }
                // https://fullstackstudio.nl/fsvoip/pair?t=...  (universal link)
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                    if let url = activity.webpageURL {
                        holder.model.handleIncoming(url: url)
                    }
                }
        }
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .active:
                holder.model.didBecomeActive()
            case .background:
                holder.model.didEnterBackground()
            default:
                break
            }
        }
    }
}

/// Keeps the services alive for the lifetime of the app (one SIP engine, one CallKit provider).
@MainActor
private final class ModelHolder: ObservableObject {
    let services = AppServices()

    var model: FSVoipAppModel {
        services.model
    }
}
