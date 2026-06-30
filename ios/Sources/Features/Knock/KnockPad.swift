import SwiftUI

/// A big circular tap target. Pressing it starts a call-style tap invitation so
/// the other phone rings through CallKit/Telecom even when the app is closed.
///
/// Brand: pure white background, thin near-black type, generous whitespace, one
/// restrained accent (the black knock target).
struct KnockPad: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss

    /// Who we're knocking. Needs a stable user-id to relay over the WS.
    let user: User

    /// When true (default) the first tap starts a knock CALL so the other
    /// phone rings through CallKit. When false (tap-back from the banner),
    /// taps are pure taps — playful back-and-forth, never a call. The banner's
    /// separate "Call" button is the escalation path.
    var startsCall: Bool = true

    /// Drives the press-down scale + pulse-ring animation per tap.
    @State private var pressScale: CGFloat = 1.0
    @State private var ringPulse: Bool = false
    @State private var tapCount: Int = 0
    @State private var didStart = false

    private var title: String {
        let name = user.displayName ?? user.phone
        return name.isEmpty ? "Slide" : name
    }

    var body: some View {
        VStack(spacing: Theme.Space.xl) {
            Spacer().frame(height: Theme.Space.lg)

            VStack(spacing: Theme.Space.xs) {
                Text("Knock knock knock")
                    .uppercaseLabel()
                Text(title)
                    .font(Theme.Font.title)
                    .foregroundStyle(Theme.Color.text)
                    .multilineTextAlignment(.center)
            }

            Spacer()

            tapTarget

            Text(startsCall ? "Tap until they pick up." : "They feel every tap — tap away.")
                .font(Theme.Font.footnote)
                .foregroundStyle(Theme.Color.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, Theme.Space.xl)

            Spacer()

            TextLinkButton(title: "Done") {
                appState.resetKnockSession()
                dismiss()
            }
            .padding(.bottom, Theme.Space.xl)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.Color.bg.ignoresSafeArea())
        .onAppear { KnockHaptics.shared.prepare() }
        .onDisappear { appState.resetKnockSession() }
    }

    private var tapTarget: some View {
        ZStack {
            // Expanding ring that fires on each tap, then fades.
            Circle()
                .stroke(Theme.Color.accent.opacity(ringPulse ? 0 : 0.4),
                        lineWidth: Theme.hairlineWidth)
                .frame(width: 200, height: 200)
                .scaleEffect(ringPulse ? 1.25 : 1.0)
                .animation(.easeOut(duration: 0.45), value: ringPulse)

            Circle()
                .fill(Theme.Color.accent)
                .frame(width: 200, height: 200)
                .overlay(
                    Text("tap")
                        .font(.system(size: 48, weight: .medium))
                        .foregroundStyle(Theme.Color.onAccent)
                )
                .scaleEffect(pressScale)
        }
        .contentShape(Circle())
        .accessibilityLabel("Tap")
        .accessibilityHint("Starts a tap call")
        .onTapGesture { tap() }
    }

    private func tap() {
        guard !user.id.isEmpty else { return }
        tapCount += 1
        if startsCall && !didStart {
            didStart = true
            appState.startKnockCall(to: user)
            // The first physical tap is part of the rhythm too. Starting the
            // durable knock-call handles closed apps; this live event lets an
            // already-open recipient feel the exact first beat.
            appState.sendKnockTap(to: user.id, playLocalFeedback: false)
        } else {
            appState.sendKnockTap(to: user.id)
        }

        // Quick squish-and-release; toggle the ring to retrigger its animation.
        withAnimation(.easeOut(duration: 0.08)) { pressScale = 0.92 }
        withAnimation(.easeOut(duration: 0.22).delay(0.08)) { pressScale = 1.0 }
        ringPulse = false
        withAnimation { ringPulse = true }
    }
}
