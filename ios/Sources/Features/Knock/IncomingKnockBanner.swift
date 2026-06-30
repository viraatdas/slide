import SwiftUI

/// A lightweight incoming tap banner — NOT CallKit. Shows "Someone is tapping"
/// with "Tap back" and "Call" actions. Re-pulses on each received tap (driven
/// by `knock.pulse`) and self-dismisses ~2.5s after the last tap (handled by
/// AppState's auto-clear timer).
struct IncomingKnockBanner: View {
    @EnvironmentObject private var appState: AppState
    @ObservedObject var knock: IncomingKnock

    @State private var scale: CGFloat = 1.0
    @State private var knockStageUser: User?

    var body: some View {
        HStack(spacing: Theme.Space.md) {
            ZStack {
                Circle()
                    .fill(Theme.Color.bgGrouped)
                    .overlay(Circle().stroke(Theme.Color.hairline, lineWidth: Theme.hairlineWidth))
                Text("tap")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(Theme.Color.accent)
            }
            .frame(width: 44, height: 44)

            VStack(alignment: .leading, spacing: 2) {
                // Live rhythm taps follow the same privacy rule as PushKit
                // knock calls: identity stays internal until the recipient
                // chooses the Call/answer path.
                Text("Someone")
                    .font(Theme.Font.callout)
                    .foregroundStyle(Theme.Color.text)
                    .lineLimit(1)
                Text("is tapping")
                    .font(Theme.Font.footnote)
                    .foregroundStyle(Theme.Color.textSecondary)
            }

            Spacer(minLength: Theme.Space.sm)

            if let actionableUser = userForKnockStage {
                HStack(spacing: Theme.Space.xs) {
                    Button {
                        appState.knockBack()
                        appState.clearIncomingKnock()
                        knockStageUser = actionableUser
                    } label: {
                        Text("Tap back")
                            .font(Theme.Font.buttonSmall)
                            .foregroundStyle(Theme.Color.text)
                            .padding(.horizontal, Theme.Space.sm)
                            .frame(height: 36)
                            .overlay(
                                RoundedRectangle(cornerRadius: Theme.Radius.small,
                                                 style: .continuous)
                                    .stroke(Theme.Color.hairline,
                                            lineWidth: Theme.hairlineWidth)
                            )
                    }
                    .buttonStyle(PressableButtonStyle())

                    Button {
                        appState.callFromKnock(video: false)
                    } label: {
                        Text("Call")
                            .font(Theme.Font.buttonSmall)
                            .foregroundStyle(Theme.Color.onAccent)
                            .padding(.horizontal, Theme.Space.md)
                            .frame(height: 36)
                            .background(
                                RoundedRectangle(cornerRadius: Theme.Radius.small,
                                                 style: .continuous)
                                    .fill(Theme.Color.accent)
                            )
                    }
                    .buttonStyle(PressableButtonStyle())
                }
            }
        }
        .padding(.horizontal, Theme.Space.md)
        .padding(.vertical, Theme.Space.sm)
        .background(
            RoundedRectangle(cornerRadius: Theme.Radius.large, style: .continuous)
                .fill(Theme.Color.bg)
                .overlay(
                    RoundedRectangle(cornerRadius: Theme.Radius.large, style: .continuous)
                        .stroke(Theme.Color.hairline, lineWidth: Theme.hairlineWidth)
                )
                .shadow(color: Theme.Color.text.opacity(0.06), radius: 12, y: 4)
        )
        .padding(.horizontal, Theme.Space.md)
        .scaleEffect(scale)
        // Re-pulse on every received tap.
        .onChange(of: knock.pulse) { _, _ in
            scale = 1.0
            withAnimation(.easeOut(duration: 0.10)) { scale = 1.04 }
            withAnimation(.easeOut(duration: 0.22).delay(0.10)) { scale = 1.0 }
        }
        .fullScreenCover(item: $knockStageUser) { user in
            KnockPad(user: user, startsCall: false)
                .environmentObject(appState)
        }
    }

    private var userForKnockStage: User? {
        guard let id = knock.fromUserId, !id.isEmpty else { return nil }
        return User(id: id,
                    phone: "",
                    displayName: "Someone",
                    avatarUrl: nil,
                    createdAt: nil,
                    lastSeenAt: nil)
    }
}

/// Top-aligned overlay container that presents the banner when a knock is active.
struct IncomingKnockOverlay: View {
    @EnvironmentObject private var appState: AppState

    var body: some View {
        VStack {
            if let knock = appState.incomingKnock {
                IncomingKnockBanner(knock: knock)
                    .environmentObject(appState)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
            Spacer()
        }
        .padding(.top, Theme.Space.xs)
        .animation(Theme.Motion.standard, value: appState.incomingKnock?.id)
    }
}
