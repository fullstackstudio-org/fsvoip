// SPDX-License-Identifier: AGPL-3.0-or-later
import SwiftUI
import UI
import UIKit

@main
struct FSVoipApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
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
                // Un-registering takes a round trip; ask iOS for a few seconds before it suspends the app.
                BackgroundWork.run(seconds: 5) {
                    holder.model.didEnterBackground()
                }
            default:
                break
            }
        }
    }
}

/// Keeps the services alive for the lifetime of the app (one SIP engine, one CallKit provider). The services are
/// created by the app delegate at launch; this only hands the model to SwiftUI.
@MainActor
private final class ModelHolder: ObservableObject {
    var model: UI.FSVoipAppModel {
        AppServices.shared.model
    }
}

/// Runs `body` and keeps the app awake for up to `seconds` afterwards (a background task), so network work it started
/// can finish.
@MainActor
enum BackgroundWork {
    static func run(seconds: TimeInterval, _ body: () -> Void) {
        let application = UIApplication.shared
        var identifier = UIBackgroundTaskIdentifier.invalid

        identifier = application.beginBackgroundTask(withName: "fsvoip.background") {
            application.endBackgroundTask(identifier)
            identifier = .invalid
        }

        body()

        DispatchQueue.main.asyncAfter(deadline: .now() + seconds) {
            if identifier != .invalid {
                application.endBackgroundTask(identifier)
                identifier = .invalid
            }
        }
    }
}
