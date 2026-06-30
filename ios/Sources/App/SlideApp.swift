import SwiftUI

@main
struct SlideApp: App {
    @StateObject private var appState = AppState()
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Quiet, precise system chrome: white, near-black tint.
        configureAppearance()
        // Warm up the Taptic engine so the first haptic fires without latency.
        Haptics.prepareAll()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(appState)
                .tint(Theme.Color.accent)
                .preferredColorScheme(.light) // design is white-first, no dark mode
                .task { await appState.bootstrap() }
                .onChange(of: scenePhase) { _, phase in
                    switch phase {
                    case .active:
                        Task { await appState.appBecameActive() }
                    case .background:
                        appState.appEnteredBackground()
                    case .inactive:
                        break
                    @unknown default:
                        break
                    }
                }
        }
    }

    private func configureAppearance() {
        // Warm palette (matches Theme): eggshell bg, espresso ink, taupe secondary.
        let egg = UIColor(red: 0xFA/255.0, green: 0xF6/255.0, blue: 0xEF/255.0, alpha: 1)
        let ink = UIColor(red: 0x2A/255.0, green: 0x21/255.0, blue: 0x1B/255.0, alpha: 1)
        let taupe = UIColor(red: 0x8A/255.0, green: 0x7C/255.0, blue: 0x6D/255.0, alpha: 1)
        let hairline = UIColor(red: 0xE6/255.0, green: 0xDC/255.0, blue: 0xCB/255.0, alpha: 1)

        let tab = UITabBarAppearance()
        tab.configureWithOpaqueBackground()
        tab.backgroundColor = egg
        tab.shadowColor = hairline
        let item = tab.stackedLayoutAppearance
        item.normal.titleTextAttributes = [
            .foregroundColor: taupe,
            .font: UIFont.systemFont(ofSize: 10, weight: .regular)
        ]
        item.selected.titleTextAttributes = [
            .foregroundColor: ink,
            .font: UIFont.systemFont(ofSize: 10, weight: .medium)
        ]
        item.normal.iconColor = taupe
        item.selected.iconColor = ink
        UITabBar.appearance().standardAppearance = tab
        UITabBar.appearance().scrollEdgeAppearance = tab

        let nav = UINavigationBarAppearance()
        nav.configureWithOpaqueBackground()
        nav.backgroundColor = egg
        nav.shadowColor = .clear
        nav.titleTextAttributes = [.foregroundColor: ink]
        UINavigationBar.appearance().standardAppearance = nav
        UINavigationBar.appearance().scrollEdgeAppearance = nav
    }
}
