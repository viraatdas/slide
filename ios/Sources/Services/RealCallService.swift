import Foundation
import SwiftUI
import AVFoundation

#if canImport(LiveKit)
import LiveKit

/// Real media via the self-hosted **LiveKit** SFU. The control plane (`/calls`)
/// returns `session.sfuUrl` (LiveKit ws URL) + `session.joinToken` (a LiveKit
/// access token scoped to room = call id); both participants join the same room.
///
/// Replaces the old custom-SFU `RTCPeerConnection` client (webrtc-rs SFU couldn't
/// complete DTLS over real networks). LiveKit handles ICE/DTLS/TURN + a TCP
/// fallback, so calls connect even on UDP-restricted networks.
final class RealCallService: NSObject, CallService, @unchecked Sendable {
    weak var delegate: CallServiceDelegate?

    /// The LiveKit room. Held for the lifetime of the call; the SwiftUI video
    /// views observe it (and its participants) for track updates.
    ///
    /// Audio tuning: full voice processing (echo cancellation + noise
    /// suppression + auto gain) and DTX off — DTX stops sending packets during
    /// silence, which can make quiet speech sound gated/choppy on flaky links.
    /// Continuous Opus at a steady bitrate sounds noticeably smoother.
    /// Video tuning: capture 720p@30 from the front camera and publish with
    /// simulcast so the SFU can serve each receiver the best layer for their
    /// link instead of one compromise stream.
    let room = Room(roomOptions: RoomOptions(
        defaultCameraCaptureOptions: CameraCaptureOptions(
            position: .front,
            dimensions: .h720_169,
            fps: 30),
        defaultAudioCaptureOptions: AudioCaptureOptions(
            echoCancellation: true,
            autoGainControl: true,
            noiseSuppression: true,
            highpassFilter: true),
        defaultVideoPublishOptions: VideoPublishOptions(simulcast: true),
        defaultAudioPublishOptions: AudioPublishOptions(dtx: false),
        adaptiveStream: true,
        dynacast: true))

    private(set) var connectionState: CallConnectionState = .idle {
        didSet {
            let state = connectionState
            DispatchQueue.main.async {
                if self.isLeavingSnapshot, state != .ended { return }
                self.delegate?.callService(self, didChange: state)
            }
        }
    }
    private(set) var hasRemoteVideo = false
    private(set) var isMuted = false
    private(set) var isVideoEnabled = true
    private(set) var isUsingFrontCamera = true
    private(set) var remoteParticipants: [RemoteParticipant] = []
    private let lifecycleLock = NSLock()
    private var lifecycleGeneration = 0
    private var isLeaving = false
    private var joinTask: Task<Void, Never>?

    override init() {
        super.init()
        room.add(delegate: self)
    }

    // MARK: - Join

    func join(session: CallSession, videoEnabled: Bool) {
        let generation = beginJoin()
        isVideoEnabled = videoEnabled
        connectionState = .connecting
        let url = session.sfuUrl
        let token = session.joinToken
        let task = Task { [weak self] in
            guard let self else { return }
            do {
                try await self.room.connect(url: url, token: token)
                guard !Task.isCancelled, self.isCurrentJoin(generation) else {
                    await self.room.disconnect()
                    return
                }
                try await self.room.localParticipant.setMicrophone(enabled: !self.isMuted)
                guard !Task.isCancelled, self.isCurrentJoin(generation) else {
                    await self.room.disconnect()
                    return
                }
                if videoEnabled {
                    // Camera denial should degrade a video invitation to audio,
                    // not tear down a successfully connected/mic-enabled call.
                    // Permission UI is handled before outgoing call creation;
                    // a cold incoming answer cannot present it in background.
                    if AVCaptureDevice.authorizationStatus(for: .video) == .authorized {
                        do {
                            try await self.room.localParticipant.setCamera(enabled: true)
                            guard !Task.isCancelled, self.isCurrentJoin(generation) else {
                                await self.room.disconnect()
                                return
                            }
                            Self.preferSpeakerIfOnEarpiece()
                        } catch {
                            if self.isCurrentJoin(generation) {
                                self.isVideoEnabled = false
                            }
                        }
                    } else {
                        if self.isCurrentJoin(generation) {
                            self.isVideoEnabled = false
                        }
                    }
                }
                guard !Task.isCancelled, self.isCurrentJoin(generation) else {
                    await self.room.disconnect()
                    return
                }
            } catch {
                await self.room.disconnect()
                if self.isCurrentJoin(generation) {
                    self.connectionState = .failed("Couldn't connect")
                }
            }
        }
        joinTask = task
    }

    private func beginJoin() -> Int {
        lifecycleLock.lock()
        defer { lifecycleLock.unlock() }
        lifecycleGeneration += 1
        isLeaving = false
        return lifecycleGeneration
    }

    private func invalidateJoin() {
        lifecycleLock.lock()
        lifecycleGeneration += 1
        isLeaving = true
        lifecycleLock.unlock()
    }

    private func isCurrentJoin(_ generation: Int) -> Bool {
        lifecycleLock.lock()
        defer { lifecycleLock.unlock() }
        return !isLeaving && lifecycleGeneration == generation
    }

    private func currentGenerationIfActive() -> Int? {
        lifecycleLock.lock()
        defer { lifecycleLock.unlock() }
        return isLeaving ? nil : lifecycleGeneration
    }

    private var isLeavingSnapshot: Bool {
        lifecycleLock.lock()
        defer { lifecycleLock.unlock() }
        return isLeaving
    }

    // MARK: - Controls

    func setMuted(_ muted: Bool) {
        guard let generation = currentGenerationIfActive() else { return }
        isMuted = muted
        Task { [weak self] in
            guard let self, self.isCurrentJoin(generation) else { return }
            try? await self.room.localParticipant.setMicrophone(enabled: !muted)
        }
    }

    func setVideoEnabled(_ enabled: Bool) {
        guard let generation = currentGenerationIfActive() else { return }
        isVideoEnabled = enabled
        Task { [weak self] in
            guard let self, self.isCurrentJoin(generation) else { return }
            try? await self.room.localParticipant.setCamera(enabled: enabled)
            guard self.isCurrentJoin(generation) else { return }
            if enabled { Self.preferSpeakerIfOnEarpiece() }
        }
    }

    /// Video belongs on speakerphone — but never yank audio off headphones/BT.
    static func preferSpeakerIfOnEarpiece() {
        let session = AVAudioSession.sharedInstance()
        if session.currentRoute.outputs.first?.portType == .builtInReceiver {
            try? session.overrideOutputAudioPort(.speaker)
        }
    }

    func flipCamera() {
        guard let generation = currentGenerationIfActive() else { return }
        // Set an explicit target instead of LiveKit's toggle — the toggle
        // derives "current" from device state and throws .unspecified during
        // capture (re)starts, which made flip a silent no-op.
        let target: AVCaptureDevice.Position = isUsingFrontCamera ? .back : .front
        isUsingFrontCamera.toggle()
        Task { [weak self] in
            guard let self,
                  self.isCurrentJoin(generation),
                  let track = self.room.localParticipant.firstCameraVideoTrack as? LocalVideoTrack,
                  let capturer = track.capturer as? CameraCapturer else { return }
            do {
                try await capturer.set(cameraPosition: target)
                guard self.isCurrentJoin(generation) else { return }
            } catch {
                // Capture restart failed — revert so the next tap retries.
                if self.isCurrentJoin(generation) {
                    self.isUsingFrontCamera = (target != .front)
                }
            }
        }
    }

    // MARK: - Video views

    func makeLocalVideoView() -> AnyView {
        AnyView(LiveKitLocalVideoView(participant: room.localParticipant))
    }
    func makeRemoteVideoView() -> AnyView {
        AnyView(LiveKitRemoteVideoView(room: room, participantId: nil))
    }
    func makeRemoteVideoView(for participantId: String) -> AnyView {
        AnyView(LiveKitRemoteVideoView(room: room, participantId: participantId))
    }

    func leave() {
        guard !isLeavingSnapshot else { return }
        invalidateJoin()
        joinTask?.cancel()
        joinTask = nil
        Task { @MainActor in CallPiPController.shared.detach() }
        Task { await room.disconnect() }
        remoteParticipants = []
        hasRemoteVideo = false
        connectionState = .ended
    }

    // MARK: - Roster

    private func rebuildParticipants() {
        guard !isLeavingSnapshot else { return }
        let ps = Array(room.remoteParticipants.values)
        let mapped = ps.map { p in
            RemoteParticipant(
                id: p.identity?.stringValue ?? p.sid?.stringValue ?? UUID().uuidString,
                displayName: p.name ?? "",
                hasVideo: p.firstCameraVideoTrack != nil,
                isAudioMuted: p.firstAudioPublication.map { $0.isMuted } ?? false)
        }
        remoteParticipants = mapped
        DispatchQueue.main.async {
            self.delegate?.callService(self, didUpdateParticipants: mapped)
        }
        refreshRemoteVideoState()
    }

    private func refreshRemoteVideoState() {
        guard !isLeavingSnapshot else { return }
        let anyVideo = room.remoteParticipants.values.contains { $0.firstCameraVideoTrack != nil }
        hasRemoteVideo = anyVideo
        let pipTrack = room.remoteParticipants.values.compactMap { $0.firstCameraVideoTrack }.first
        DispatchQueue.main.async {
            self.delegate?.callServiceRemoteVideoBecameAvailable(self)
            // Keep the PiP layer fed by the primary remote feed.
            if let pipTrack {
                CallPiPController.shared.attachIfNeeded(track: pipTrack)
            }
        }
    }

    func makePiPAnchorView() -> AnyView? {
        AnyView(PiPAnchorView())
    }
}

// MARK: - RoomDelegate

extension RealCallService: RoomDelegate {
    func room(_ room: Room, didUpdateConnectionState connectionState: ConnectionState,
              from oldConnectionState: ConnectionState) {
        switch connectionState {
        case .connecting:
            guard !isLeavingSnapshot else { return }
            self.connectionState = .connecting
        case .reconnecting:
            guard !isLeavingSnapshot else { return }
            self.connectionState = .reconnecting
        case .connected:
            guard !isLeavingSnapshot else { return }
            self.connectionState = .connected
        case .disconnected:
            // Only an explicit local leave is a clean end. Losing the room after
            // it was connected is recoverable/failable UI, not a terminal event
            // that leaves the call screen stuck with no retry affordance.
            self.connectionState = isLeavingSnapshot ? .ended : .failed("Disconnected")
        case .disconnecting:
            break
        @unknown default:
            break
        }
    }

    func room(_ room: Room, participantDidConnect participant: LiveKit.RemoteParticipant) {
        rebuildParticipants()
    }

    func room(_ room: Room, participantDidDisconnect participant: LiveKit.RemoteParticipant) {
        rebuildParticipants()
    }

    func room(_ room: Room, participant: LiveKit.RemoteParticipant,
              didSubscribeTrack publication: RemoteTrackPublication) {
        rebuildParticipants()
    }

    func room(_ room: Room, participant: LiveKit.RemoteParticipant,
              didUnsubscribeTrack publication: RemoteTrackPublication) {
        rebuildParticipants()
    }

    func room(_ room: Room, participant: LiveKit.RemoteParticipant,
              didUnpublishTrack publication: RemoteTrackPublication) {
        rebuildParticipants()
    }

    func room(_ room: Room, participant: Participant,
              trackPublication: TrackPublication, didUpdateIsMuted isMuted: Bool) {
        rebuildParticipants()
    }
}

// MARK: - SwiftUI video bridges
// Observe the LiveKit participant so the feed appears the moment its camera
// track is published/subscribed (publishing is async after connect).

private struct LiveKitLocalVideoView: View {
    @ObservedObject var participant: LocalParticipant
    var body: some View {
        if let track = participant.firstCameraVideoTrack {
            // .auto mirrors the front camera only — a mirrored back camera
            // reads backwards.
            SwiftUIVideoView(track, layoutMode: .fill, mirrorMode: .auto)
        } else {
            Color.black
        }
    }
}

private struct LiveKitRemoteVideoView: View {
    @ObservedObject var room: Room
    let participantId: String?

    private var participant: LiveKit.RemoteParticipant? {
        let values = room.remoteParticipants.values
        if let id = participantId {
            return values.first { $0.identity?.stringValue == id }
        }
        return values.first
    }

    var body: some View {
        if let participant {
            RemoteParticipantVideo(participant: participant)
        } else {
            Color.black
        }
    }
}

private struct RemoteParticipantVideo: View {
    @ObservedObject var participant: LiveKit.RemoteParticipant
    var body: some View {
        if let track = participant.firstCameraVideoTrack {
            SwiftUIVideoView(track, layoutMode: .fill)
        } else {
            Color.black
        }
    }
}

#endif
