import SwiftUI
import Combine

@MainActor
final class InCallViewModel: ObservableObject {
    @Published var connectionState: CallConnectionState = .idle
    @Published var elapsed: TimeInterval = 0
    @Published var isMuted = false
    @Published var isVideoEnabled: Bool
    @Published var hasRemoteVideo = false
    @Published var remoteParticipants: [RemoteParticipant] = []
    /// True once the other person has actually joined the room. Until then the
    /// call is still "ringing" from the user's perspective, even though we're
    /// already connected to the media server ourselves.
    @Published var remoteJoined = false

    let service: CallService
    let isGroup: Bool
    let isKnock: Bool
    let isOutgoing: Bool
    private let memberNames: [String]
    private weak var call: ActiveCall?
    private var timer: Timer?
    private var startedAt: Date?
    private var delegateBox: Delegate?
    private var sessionCancellable: AnyCancellable?
    private var hasStarted = false
    private var hasJoinedSession = false
    private var hasEnded = false

    init(call: ActiveCall) {
        self.call = call
        self.isVideoEnabled = call.isVideo
        self.isGroup = call.isGroup
        self.isKnock = call.isKnock
        self.isOutgoing = call.direction == .outgoing
        self.memberNames = call.memberNames
        self.service = CallServiceFactory.make()
        // Seed the mock roster so the simulator renders a real group grid.
        if let mock = service as? MockCallService {
            mock.mockMemberNames = call.memberNames
        }
        let box = Delegate(owner: self)
        self.delegateBox = box
        self.service.delegate = box
        // A create/accept request can legitimately take up to the API's 20s
        // timeout. Observe the session instead of polling for only four seconds
        // and silently never joining when a slow request eventually succeeds.
        self.sessionCancellable = call.$session
            .compactMap { $0 }
            .sink { [weak self] session in
                Task { @MainActor in self?.sessionBecameAvailable(session) }
            }
    }

    /// Participants with display names filled in from the call's member list
    /// (the SFU doesn't carry names, so we map by position for the roster).
    var displayParticipants: [RemoteParticipant] {
        remoteParticipants.enumerated().map { idx, p in
            var copy = p
            if copy.displayName.isEmpty, idx < memberNames.count {
                copy.displayName = memberNames[idx]
            }
            return copy
        }
    }

    var timerText: String {
        let total = Int(elapsed)
        let m = total / 60, s = total % 60
        return String(format: "%02d:%02d", m, s)
    }

    var statusText: String {
        switch connectionState {
        case .idle, .connecting: return "Connecting…"
        case .reconnecting: return "Reconnecting…"
        case .failed: return "Call failed"
        case .connected:
            // We're in the room, but the call hasn't "started" until the other
            // person joins — show ringing, not a ticking timer.
            if remoteJoined { return timerText }
            if isOutgoing { return isKnock ? "Knocking…" : "Ringing…" }
            return "Connecting…"
        case .ended: return "Call ended"
        }
    }

    func start() {
        guard !hasStarted else { return }
        hasStarted = true
        guard let call else { return }
        if let session = call.session {
            sessionBecameAvailable(session)
        }
    }

    private func sessionBecameAvailable(_ session: CallSession) {
        guard hasStarted, !hasJoinedSession, !hasEnded else { return }
        hasJoinedSession = true
        service.join(session: session, videoEnabled: isVideoEnabled)
    }

    func toggleMute() {
        isMuted.toggle()
        service.setMuted(isMuted)
        if let uuid = call?.uuid {
            CallKitManager.shared.setMuted(uuid: uuid, muted: isMuted)
        }
    }

    func setMutedFromCallKit(_ muted: Bool) {
        guard muted != isMuted else { return }
        isMuted = muted
        service.setMuted(muted)
    }

    func toggleVideo() {
        isVideoEnabled.toggle()
        service.setVideoEnabled(isVideoEnabled)
    }

    func setVideoEnabledFromCallState(_ enabled: Bool) {
        guard enabled != isVideoEnabled else { return }
        isVideoEnabled = enabled
        service.setVideoEnabled(enabled)
    }

    func flipCamera() { service.flipCamera() }

    func end() {
        guard !hasEnded else { return }
        hasEnded = true
        timer?.invalidate()
        sessionCancellable?.cancel()
        service.leave()
    }

    fileprivate func handleStateChange(_ state: CallConnectionState) {
        connectionState = state
        switch state {
        case .connected:
            call?.status = .active
        case .failed:
            Haptics.error()
            call?.status = .failed
            if let uuid = call?.uuid {
                CallKitManager.shared.reportCallEnded(uuid: uuid, reason: .failed)
            }
            resolveBackendIfNeeded()
        case .ended:
            if remoteJoined, call?.status != .ended { SoundEffects.ended() }
        default:
            break
        }
    }

    private func resolveBackendIfNeeded() {
        guard let callId = call?.callIdForBackendResolution() else { return }
        Task { await APIClient.shared.leaveCallBestEffort(id: callId) }
    }

    fileprivate func remoteVideoAvailable() {
        hasRemoteVideo = service.hasRemoteVideo
    }

    fileprivate func participantsChanged(_ participants: [RemoteParticipant]) {
        remoteParticipants = participants
        // First remote participant = the moment the call truly begins: start
        // the timer and celebrate with a haptic + a tiny chime.
        if !participants.isEmpty, !remoteJoined {
            remoteJoined = true
            Haptics.success()
            SoundEffects.connected()
            if startedAt == nil {
                startedAt = Date()
                startTimer()
            }
        }
    }

    private func startTimer() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let start = self.startedAt else { return }
                self.elapsed = Date().timeIntervalSince(start)
            }
        }
    }

    /// Bridges the non-isolated delegate callbacks back to the main actor.
    private final class Delegate: CallServiceDelegate {
        weak var owner: InCallViewModel?
        init(owner: InCallViewModel) { self.owner = owner }
        func callService(_ service: CallService, didChange state: CallConnectionState) {
            Task { @MainActor in self.owner?.handleStateChange(state) }
        }
        func callServiceRemoteVideoBecameAvailable(_ service: CallService) {
            Task { @MainActor in self.owner?.remoteVideoAvailable() }
        }
        func callService(_ service: CallService, didUpdateParticipants participants: [RemoteParticipant]) {
            Task { @MainActor in self.owner?.participantsChanged(participants) }
        }
    }
}
