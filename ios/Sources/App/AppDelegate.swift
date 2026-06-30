import UIKit
import UserNotifications

#if canImport(FirebaseAuth)
import FirebaseAuth
import FirebaseCore
#endif

/// App delegate exists so Firebase Phone Auth can receive the silent APNs push /
/// URL-scheme callbacks it uses to confirm the device (anti-abuse) before
/// sending the SMS. No-ops cleanly when Firebase isn't bundled.
final class AppDelegate: NSObject, UIApplicationDelegate {
    /// The app is portrait-only except during an active call, where rotating
    /// to show someone something is natural. Toggled by CallContainerView.
    static var allowLandscape = false

    func application(_ application: UIApplication,
                     supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        Self.allowLandscape ? [.portrait, .landscapeLeft, .landscapeRight] : .portrait
    }

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        #if canImport(FirebaseAuth)
        if Config.useFirebaseAuth {
            FirebaseAuthService.configureIfNeeded()
        }
        #endif
        // Register for PushKit VoIP pushes so incoming calls/knocks ring via
        // CallKit even when the app is backgrounded, locked, or killed. iOS
        // launches the app into the background to deliver a VoIP push, so this
        // must be set up at launch.
        PushService.shared.start()

        // Register for STANDARD remote notifications too. Firebase Phone Auth
        // verifies the device with a silent APNs push; without this token it
        // falls back to a reCAPTCHA web page (which was erroring). VoIP/PushKit
        // tokens are separate and do NOT satisfy Firebase, so this is required.
        application.registerForRemoteNotifications()
        // Foreground alert pushes are suppressed — the live WS already drives
        // in-app banners/haptics; system banners would double up.
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func application(_ application: UIApplication,
                     didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        #if canImport(FirebaseAuth)
        // Hand the APNs token to Firebase for phone-auth verification. On a
        // TestFlight/App Store build the token is production; .unknown lets
        // Firebase auto-detect the environment.
        if Config.useFirebaseAuth {
            Auth.auth().setAPNSToken(deviceToken, type: .unknown)
            // Unblocks FirebaseAuthService.sendCode, which waits briefly for this
            // so verification runs silently instead of via the reCAPTCHA page.
            Task { @MainActor in FirebaseAuthService.apnsTokenReady = true }
        }
        #endif
        // Also register with our backend (kind "apns") so it can send alert
        // pushes: knock taps while backgrounded, missed knocks.
        let hex = deviceToken.map { String(format: "%02x", $0) }.joined()
        let previous = PushService.shared.standardTokenHex
        PushService.shared.standardTokenHex = hex
        if TokenStore.shared.isAuthenticated {
            Task {
                if let previous, previous != hex {
                    try? await APIClient.shared.unregisterPushToken(previous)
                }
                try? await APIClient.shared.registerStandardPushToken(hex)
            }
        }
    }

    func application(_ application: UIApplication,
                     didFailToRegisterForRemoteNotificationsWithError error: Error) {
        // No APNs token (e.g. simulator, or push not provisioned). Firebase will
        // fall back to the reCAPTCHA flow, which needs the URL scheme we set in
        // project.yml. Log so this is diagnosable but don't crash.
        #if DEBUG
        print("APNs registration failed: \(error.localizedDescription)")
        #endif
    }

    func application(_ application: UIApplication,
                     didReceiveRemoteNotification notification: [AnyHashable: Any],
                     fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
        #if canImport(FirebaseAuth)
        if Config.useFirebaseAuth, Auth.auth().canHandleNotification(notification) {
            completionHandler(.noData)
            return
        }
        #endif
        if handleCallTerminal(notification) {
            completionHandler(.newData)
            return
        }
        completionHandler(.noData)
    }

    func application(_ app: UIApplication, open url: URL,
                     options _: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
        #if canImport(FirebaseAuth)
        if Config.useFirebaseAuth, Auth.auth().canHandle(url) { return true }
        #endif
        return false
    }

    @discardableResult
    private func handleCallTerminal(_ notification: [AnyHashable: Any]) -> Bool {
        let type = notification["type"] as? String
        let callId = notification["callId"] as? String
        guard let type, let callId, !callId.isEmpty,
              ["call_ended", "call_declined", "call_accepted"].contains(type) else {
            return false
        }
        PushService.shared.receiveTerminalPush(type: type, callId: callId)
        return true
    }
}

extension AppDelegate: UNUserNotificationCenterDelegate {
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        handleCallTerminal(notification.request.content.userInfo)
        completionHandler([])
    }
}
