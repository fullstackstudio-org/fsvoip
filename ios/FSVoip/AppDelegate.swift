// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Launch, push and notification callbacks. Everything is handed to `AppServices` / the app model; no logic here.

import Core
import UIKit
import UserNotifications

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    private let logger = FSLogger(category: "push")

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        MainActor.assumeIsolated {
            // 🚨 Create the services and the PushKit registry NOW: a VoIP push that launched the app is only delivered
            // once the registry exists, and it must be reported to CallKit straight away.
            let services = AppServices.shared
            UNUserNotificationCenter.current().delegate = self
            services.startPush(application)
        }

        return true
    }

    // MARK: Regular APNs token (notices such as "unpaired")

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        MainActor.assumeIsolated {
            AppServices.shared.didRegisterAlertToken(deviceToken)
        }
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        logger.notice("No APNs token: \(error.localizedDescription)")
    }

    /// `content-available` notice in the background (or foreground): "unpaired" removes the account at once.
    func application(_ application: UIApplication, didReceiveRemoteNotification userInfo: [AnyHashable: Any], fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
        MainActor.assumeIsolated {
            AppServices.shared.model.handleNotification(payload: userInfo)
        }

        completionHandler(.newData)
    }

    // MARK: Notification center

    /// In the foreground the app shows its own notice, not the banner. A notice from the portal is the exception: it is shown as a
    /// normal banner (a new order should not go unseen because the app is open).
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        let payload = notification.request.content.userInfo

        DispatchQueue.main.async {
            MainActor.assumeIsolated {
                let model = AppServices.shared.model

                if model.showsBannerInForeground(payload: payload) {
                    completionHandler([.banner, .list, .sound])
                } else {
                    model.handleNotification(payload: payload)
                    completionHandler([])
                }
            }
        }
    }

    /// The user tapped the notice: a portal notice opens the screen its link maps to, "unpaired" / "refresh" are handled as usual.
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        let payload = response.notification.request.content.userInfo

        DispatchQueue.main.async {
            MainActor.assumeIsolated {
                AppServices.shared.model.openNotification(payload: payload)
            }

            completionHandler()
        }
    }
}
