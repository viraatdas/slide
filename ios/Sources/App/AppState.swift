import SwiftUI
import Combine
import UserNotifications

/// Top-level app phases.
enum AppPhase: Equatable {
    case loading
    case onboarding
    case needsName        // authenticated but new user without a name
    case home
}

/// Owns auth/session state and the cross-cutting services. Injected as an
/// `@EnvironmentObject`.
@MainActor
final class AppState: ObservableObject {
    @Published var phase: AppPhase = .loading
    @Published var currentUser: User?
    @Published private(set) var contacts: [Contact] = []

    let api = APIClient.shared
    let tokens = TokenStore.shared
    let signaling = SignalingClient()

    /// Drives the incoming-call screen / in-call modal.
    @Published var activeCall: ActiveCall?

    /// Drives the incoming-knock banner overlay (lightweight, not CallKit).
    @Published var incomingKnock: IncomingKnock?

    /// Short-lived tombstones stop a delayed duplicate WS/VoIP delivery from
    /// resurrecting a call we already ended.
    private var recentlyFinishedCallIds: [String: Date] = [:]
    private var answerRequestedCallIds = Set<UUID>()
    private var acceptingCallIds = Set<UUID>()
    private var answerCompletions: [UUID: [(Bool) -> Void]] = [:]
    private var incomingRingDeadlineTasks: [UUID: Task<Void, Never>] = [:]
    private var permissionRequestTask: Task<Void, Never>?

    // MARK: Knock send-side state
    /// Monotonic sequence for the current outbound knock session.
    private var outgoingKnockSeq = 0
    /// Timestamp of the previous outbound tap, to compute `dt`.
    private var lastOutgoingKnockAt: Date?
    /// The user-id we're currently knocking, so a new target resets the session.
    private var outgoingKnockTarget: String?

    // MARK: Knock receive-side state
    /// Auto-clears the incoming-knock banner ~2.5s after the last received tap.
    private var incomingKnockClearTask: Task<Void, Never>?

    init() {
        signaling.delegate = self

        // Install the PushKit handoff before the CallKit delegate. Both systems
        // can fire before SwiftUI's `.task` bootstrap runs on a cold launch;
        // replaying the payload first guarantees a queued answer has an
        // ActiveCall to act on when CallKitManager drains it.
        PushService.shared.onIncomingCall = { [weak self] callId, fromUserId, fromName,
                                              callType, videoEnabled, ringStyle, expiresAt in
            // PKPushRegistry is explicitly created on the main queue, and a
            // pending payload is drained here from AppState's main-actor init.
            MainActor.assumeIsolated {
                self?.receivePushedCall(callId: callId, fromUserId: fromUserId,
                                        fromName: fromName, type: callType,
                                        videoEnabled: videoEnabled,
                                        ringStyle: ringStyle,
                                        expiresAt: expiresAt)
            }
        }
        PushService.shared.onIncomingCallReportFailed = { [weak self] callId in
            MainActor.assumeIsolated {
                self?.handleIncomingCallReportFailure(callId: callId)
            }
        }
        PushService.shared.onCallTerminal = { [weak self] type, callId in
            MainActor.assumeIsolated {
                self?.receivePushedTerminal(type: type, callId: callId)
            }
        }
        CallKitManager.shared.delegate = self

        Task { [weak self] in
            guard let self else { return }
            await api.setAuthFailureHandler { [weak self] in
                Task { @MainActor in self?.logoutLocally() }
            }
        }
    }

    func bootstrap() async {
        // Debug/screenshot hooks: jump straight to a screen in the simulator.
        let args = ProcessInfo.processInfo.arguments
        if args.contains("-group") {
            // Group-call grid for screenshots.
            currentUser = MockData.me
            phase = .home
            let names = ["Amelia Stone", "Daniel Wu", "Grace Lin", "Marcus Reed", "Priya Nair"]
            let demo = ActiveCall(direction: .outgoing,
                                  remoteName: names[0],
                                  remotePhone: "",
                                  remoteUserId: "u_group",
                                  isVideo: true,
                                  status: .connecting,
                                  isGroup: true,
                                  memberNames: names)
            demo.session = MockData.callSession(for: MockData.userForContact(MockData.contacts[0]),
                                                video: true)
            activeCall = demo
            return
        }
        if args.contains("-home") || args.contains("-incall") {
            currentUser = MockData.me
            phase = .home
            if args.contains("-incall") {
                let demo = ActiveCall(direction: .outgoing,
                                      remoteName: "Amelia Stone",
                                      remotePhone: "+14155550111",
                                      remoteUserId: "u_amelia",
                                      isVideo: !args.contains("-audio"),
                                      status: .connecting,
                                      isKnock: args.contains("-knock"))
                // Knock demo: leave the session unset so nobody "answers" and
                // the knock stage stays up for screenshots.
                if !demo.isKnock {
                    demo.session = MockData.callSession(for: MockData.userForContact(MockData.contacts[0]),
                                                        video: !args.contains("-audio"))
                }
                activeCall = demo
            }
            return
        }
        if args.contains("-incoming") {
            currentUser = MockData.me
            phase = .home
            let demo = ActiveCall(direction: .incoming,
                                  remoteName: "Daniel Wu",
                                  remotePhone: "+14155550114",
                                  remoteUserId: "u_daniel",
                                  isVideo: true,
                                  status: .ringing,
                                  isKnock: args.contains("-knock"))
            demo.callId = "demo_incoming"
            activeCall = demo
            // Simulate the knocker's rhythm so the door rattles in screenshots.
            if demo.isKnock {
                Task { @MainActor in
                    while self.activeCall === demo {
                        try? await Task.sleep(nanoseconds: 1_400_000_000)
                        demo.knockPulse += 1
                    }
                }
            }
            return
        }

        guard tokens.isAuthenticated else {
            phase = .onboarding
            return
        }

        // Try to load the profile; if it fails on auth, fall back to onboarding.
        do {
            let user = try await api.me()
            currentUser = user
            phase = (user.displayName?.isEmpty ?? true) ? .needsName : .home
            signaling.connect()
            Task { await registerDeviceIfPossible() }
            Task { await refreshContactCache() }
            Task { await reconcileActiveRingingCall() }
            Task { await recoverRecentIncomingCall() }
            schedulePostAuthenticationPermissionsIfNeeded()
        } catch APIError.unauthorized, APIError.notAuthenticated {
            logoutLocally()
        } catch {
            // Network down but we have tokens — proceed to home optimistically.
            if Config.useMockData {
                currentUser = MockData.me
                phase = .home
            } else {
                phase = .home
            }
            signaling.connect()
            Task { await registerDeviceIfPossible() }
            Task { await refreshContactCache() }
            Task { await recoverRecentIncomingCall() }
            schedulePostAuthenticationPermissionsIfNeeded()
        }
    }

    func didAuthenticate(user: User, isNewUser: Bool) {
        currentUser = user
        phase = (isNewUser || (user.displayName?.isEmpty ?? true)) ? .needsName : .home
        signaling.connect()
        Task { await registerDeviceIfPossible() }
        Task { await refreshContactCache() }
        Task { await reconcileActiveRingingCall() }
        Task { await recoverRecentIncomingCall() }
        schedulePostAuthenticationPermissionsIfNeeded()
    }

    func didCompleteName(user: User) {
        currentUser = user
        phase = .home
        Task { await refreshContactCache() }
        schedulePostAuthenticationPermissionsIfNeeded()
    }

    func logout() {
        let standardToken = PushService.shared.standardTokenHex
        let voipToken = PushService.shared.voipToken
        let callResolution: (id: String, decline: Bool)? = {
            guard let call = activeCall,
                  let id = call.callIdForBackendResolution() else { return nil }
            if call.direction == .incoming, call.session == nil {
                // `/leave` acts on the account participant and could terminate a
                // sibling installation that won a racing accept. A still-ringing
                // invitation can be declined safely; an ambiguous in-flight
                // keyed accept reconciles itself when its response returns.
                guard !acceptingCallIds.contains(call.uuid) else { return nil }
                return (id, true)
            }
            return (id, false)
        }()
        Task {
            await withTaskGroup(of: Void.self) { group in
                if let callResolution {
                    group.addTask {
                        if callResolution.decline {
                            _ = try? await self.api.declineCall(id: callResolution.id)
                        } else {
                            await self.api.leaveCallBestEffort(id: callResolution.id)
                        }
                    }
                }
                if let standardToken {
                    group.addTask { _ = try? await self.api.unregisterPushToken(standardToken) }
                }
                if let voipToken {
                    group.addTask { _ = try? await self.api.unregisterPushToken(voipToken) }
                }
            }
            await api.logout()
            await MainActor.run { logoutLocally() }
        }
    }

    func logoutLocally() {
        signaling.disconnect()
        permissionRequestTask?.cancel()
        permissionRequestTask = nil
        cancelAllIncomingRingDeadlines()
        tokens.clear()
        currentUser = nil
        contacts = []
        if let call = activeCall {
            CallKitManager.shared.reportCallEnded(uuid: call.uuid, reason: .failed)
            rememberFinished(call.callId)
        }
        acceptingCallIds.removeAll()
        finishAnswerCompletions(success: false)
        activeCall = nil
        phase = .onboarding
    }

    func refreshContactCache() async {
        guard tokens.isAuthenticated else { return }
        do {
            let list = try await api.contacts()
            contacts = list
            refreshActiveCallDisplayName()
        } catch {
            // Contacts are a display-name enhancement. Never block calls on it.
        }
    }

    func replaceContactCache(_ list: [Contact]) {
        contacts = list
        refreshActiveCallDisplayName()
    }

    func appBecameActive() async {
        guard tokens.isAuthenticated else { return }
        signaling.reconnectNow()
        Task { await registerDeviceIfPossible() }
        await refreshContactCache()
        await reconcileActiveRingingCall()
        if activeCall == nil { await recoverRecentIncomingCall() }
        schedulePostAuthenticationPermissionsIfNeeded()
    }

    func appEnteredBackground() {
        // An idle iOS process is about to be suspended. Closing the socket makes
        // server-side offline detection truthful immediately instead of leaving
        // a stale connection that swallows realtime-only delivery. Keep it for
        // an active call so hang-up signaling can continue under audio/PiP.
        switch activeCall?.status {
        case .ringing, .connecting, .active:
            break
        case .none, .dialing, .failed, .ended:
            signaling.disconnect()
        }
    }

    /// Ask for core permissions after onboarding, one prompt at a time. The mic
    /// is requested proactively so a locked/cold CallKit answer never needs to
    /// present permission UI in the background.
    private func schedulePostAuthenticationPermissionsIfNeeded() {
        guard permissionRequestTask == nil,
              tokens.isAuthenticated,
              phase == .home,
              activeCall == nil,
              UIApplication.shared.applicationState == .active else { return }

        permissionRequestTask = Task { @MainActor [weak self] in
            guard let self else { return }
            defer { self.permissionRequestTask = nil }
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            guard !Task.isCancelled, self.activeCall == nil,
                  UIApplication.shared.applicationState == .active else { return }

            let notificationKey = "askedNotificationPermission"
            if !UserDefaults.standard.bool(forKey: notificationKey) {
                UserDefaults.standard.set(true, forKey: notificationKey)
                _ = try? await UNUserNotificationCenter.current()
                    .requestAuthorization(options: [.alert, .sound, .badge])
                guard !Task.isCancelled else { return }
                // Avoid stacking the microphone sheet directly on the
                // notification sheet's dismissal animation.
                try? await Task.sleep(nanoseconds: 700_000_000)
            }

            UIApplication.shared.registerForRemoteNotifications()
            guard !Task.isCancelled, self.activeCall == nil,
                  UIApplication.shared.applicationState == .active else { return }
            _ = await MediaPermissions.requestMicrophoneAccess()
        }
    }

    private func registerDeviceIfPossible() async {
        // Standard APNs token → backend alert pushes (knocks while
        // backgrounded, missed knocks). Re-sent here in case it arrived
        // before sign-in.
        if let hex = PushService.shared.standardTokenHex {
            _ = try? await api.registerStandardPushToken(hex)
        }
        // PushKit tokens land moments after launch — wait briefly instead of
        // racing ahead (the old placeholder write masked real failures).
        for _ in 0..<16 where PushService.shared.voipToken == nil {
            try? await Task.sleep(nanoseconds: 500_000_000)
        }
        if let voip = PushService.shared.voipToken {
            _ = try? await api.registerPushToken(voip)
            return
        }
        #if targetEnvironment(simulator)
        // Simulators never get push tokens; a placeholder keeps the device row.
        _ = try? await api.registerDevice(pushToken: "simulator-no-apns-token")
        #endif
    }

    // MARK: - Calls

    func startCall(to user: User, video: Bool) {
        guard activeCall == nil else { return }
        Haptics.impact()   // committing to a call
        let call = ActiveCall(direction: .outgoing,
                              remoteName: user.displayName ?? user.phone,
                              remotePhone: user.phone,
                              remoteUserId: user.id,
                              isVideo: video,
                              status: .dialing)
        activeCall = call
        Task {
            guard await prepareMediaPermissions(for: call) else { return }
            guard await startOutgoingSystemCall(
                uuid: call.uuid, handle: call.remoteName,
                displayName: call.remoteName, hasVideo: call.isVideo) else {
                call.status = .failed
                call.endMessage = "Another call is already using the phone."
                return
            }
            guard activeCall?.id == call.id else {
                CallKitManager.shared.endCall(uuid: call.uuid)
                return
            }
            await placeCall(to: user, video: call.isVideo, ringStyle: "call", local: call)
        }
    }

    func startKnockCall(to user: User, video: Bool = false) {
        guard activeCall == nil else { return }
        KnockHaptics.shared.knock()
        let call = ActiveCall(direction: .outgoing,
                              remoteName: user.displayName ?? user.phone,
                              remotePhone: user.phone,
                              remoteUserId: user.id,
                              isVideo: video,
                              status: .dialing,
                              isKnock: true)
        activeCall = call
        Task {
            guard await prepareMediaPermissions(for: call) else { return }
            guard await startOutgoingSystemCall(
                uuid: call.uuid, handle: "Knock Knock",
                displayName: "Knocking", hasVideo: call.isVideo) else {
                call.status = .failed
                call.endMessage = "Another call is already using the phone."
                return
            }
            guard activeCall?.id == call.id else {
                CallKitManager.shared.endCall(uuid: call.uuid)
                return
            }
            await placeCall(to: user, video: call.isVideo, ringStyle: "knock", local: call)
        }
    }

    /// Start a group call with several people selected up front. The backend
    /// rings everyone and the SFU fans out each participant's media.
    func startGroupCall(to users: [User], video: Bool) {
        guard activeCall == nil else { return }
        guard !users.isEmpty else { return }
        guard users.count > 1 else { startCall(to: users[0], video: video); return }
        Haptics.impact()   // committing to a group call
        let names = users.map { $0.displayName ?? $0.phone }
        let call = ActiveCall(direction: .outgoing,
                              remoteName: names.first ?? "Group",
                              remotePhone: "",
                              remoteUserId: users.first?.id,
                              isVideo: video,
                              status: .dialing,
                              isGroup: true,
                              memberNames: names)
        activeCall = call
        Task {
            guard await prepareMediaPermissions(for: call) else { return }
            guard await startOutgoingSystemCall(
                uuid: call.uuid, handle: "Group call",
                displayName: names.joined(separator: ", "), hasVideo: call.isVideo) else {
                call.status = .failed
                call.endMessage = "Another call is already using the phone."
                return
            }
            guard activeCall?.id == call.id else {
                CallKitManager.shared.endCall(uuid: call.uuid)
                return
            }
            await placeGroupCall(to: users, video: call.isVideo, local: call)
        }
    }

    private func startOutgoingSystemCall(uuid: UUID, handle: String,
                                         displayName: String, hasVideo: Bool) async -> Bool {
        await withCheckedContinuation { continuation in
            CallKitManager.shared.startOutgoingCall(
                uuid: uuid, handle: handle, displayName: displayName, hasVideo: hasVideo
            ) { error in
                continuation.resume(returning: error == nil)
            }
        }
    }

    private func prepareMediaPermissions(for call: ActiveCall) async -> Bool {
        guard await MediaPermissions.requestMicrophoneAccess() else {
            guard activeCall?.id == call.id else { return false }
            call.status = .failed
            call.endMessage = "Microphone access is required for calls."
            return false
        }
        if call.isVideo {
            // Camera denial does not block audio; the media service skips local
            // video publication and the user can enable access in Settings.
            if !(await MediaPermissions.requestCameraAccess()) {
                call.isVideo = false
            }
        }
        return activeCall?.id == call.id
    }

    private func placeGroupCall(to users: [User], video: Bool, local: ActiveCall) async {
        do {
            let session = try await api.createCall(type: .group,
                                                   participantUserIds: users.map { $0.id },
                                                   videoEnabled: video,
                                                   ringStyle: "call")
            guard activeCall?.id == local.id else {
                // The user hung up while POST /calls was in flight. Cancel the
                // newly-created server call before it becomes a ghost ring.
                await api.leaveCallBestEffort(id: session.call.id)
                return
            }
            local.session = session
            local.callId = session.call.id
            local.status = .connecting
        } catch {
            guard activeCall?.id == local.id else { return }
            if Config.useMockData {
                local.session = MockData.callSession(for: users[0], video: video)
                local.status = .connecting
            } else {
                local.status = .failed
                CallKitManager.shared.reportCallEnded(uuid: local.uuid, reason: .failed)
            }
        }
    }

    private func placeCall(to user: User, video: Bool, ringStyle: String, local: ActiveCall) async {
        do {
            let session = try await api.createCall(type: .oneToOne,
                                                   participantUserIds: [user.id],
                                                   videoEnabled: video,
                                                   ringStyle: ringStyle)
            guard activeCall?.id == local.id else {
                await api.leaveCallBestEffort(id: session.call.id)
                return
            }
            local.session = session
            local.callId = session.call.id
            local.status = .connecting
        } catch {
            guard activeCall?.id == local.id else { return }
            if Config.useMockData {
                // Mock: synthesize a session so the in-call UI works offline.
                local.session = MockData.callSession(for: user, video: video)
                local.status = .connecting
            } else {
                local.status = .failed
                CallKitManager.shared.reportCallEnded(uuid: local.uuid, reason: .failed)
            }
        }
    }

    /// Surface a call that arrived via a VoIP push. CallKit has already been
    /// told about this call (in PushService) using `PushService.uuid(for:)`, so
    /// we build the ActiveCall with the SAME uuid. That makes the CallKit answer
    /// callback (`callKitDidAnswer`) match this call and run `acceptIncoming`,
    /// which joins via the normal accept path — identical to an in-app
    /// `incoming_call`. If the WebSocket later delivers the same `incoming_call`,
    /// it's deduped by callId so we don't double-ring.
    func receivePushedCall(callId: String, fromUserId: String?,
                           fromName: String?, type: CallType,
                           videoEnabled: Bool, ringStyle: String,
                           expiresAt: Date?) {
        // A cold/background PushKit launch must also bring signaling up now,
        // rather than waiting for profile bootstrap. That lets a caller hang-up
        // dismiss CallKit while this process is still backgrounded.
        if UIApplication.shared.applicationState == .active {
            signaling.connect()
        } else {
            signaling.reconnectNow()
        }
        receiveIncomingCall(callId: callId, fromUserId: fromUserId,
                            fromName: fromName, type: type,
                            videoEnabled: videoEnabled, ringStyle: ringStyle,
                            expiresAt: expiresAt,
                            wasReportedByPushKit: true)
    }

    private func receiveIncomingCall(callId: String, fromUserId: String?,
                                     fromName: String?, type: CallType,
                                     videoEnabled: Bool, ringStyle: String,
                                     expiresAt: Date?,
                                     wasReportedByPushKit: Bool) {
        guard !callId.isEmpty else { return }
        let uuid = PushService.uuid(for: callId)
        let resolvedExpiry = expiresAt ?? Date().addingTimeInterval(45)
        let fromUserId = actionableUserId(fromUserId)

        guard resolvedExpiry > Date() else {
            if wasReportedByPushKit {
                CallKitManager.shared.reportCallEnded(uuid: uuid, reason: .unanswered)
            }
            if let call = activeCall, call.callId == callId, call.status == .ringing {
                cancelIncomingRingDeadline(for: call)
                finishAnswer(call.uuid, success: false)
                CallKitManager.shared.reportCallEnded(uuid: call.uuid, reason: .unanswered)
                activeCall = nil
            }
            rememberFinished(callId)
            return
        }

        guard tokens.isAuthenticated else {
            // A token can remain briefly registered after logout. PushKit has
            // already reported the native call; end it instead of presenting an
            // unanswerable call over onboarding with no bearer credentials.
            if wasReportedByPushKit {
                CallKitManager.shared.reportCallEnded(uuid: uuid, reason: .failed)
            }
            rememberFinished(callId)
            return
        }

        pruneFinishedCallIds()
        if recentlyFinishedCallIds[callId] != nil {
            // PushKit has already satisfied its report requirement. End the
            // delayed duplicate immediately instead of resurrecting the UI.
            if wasReportedByPushKit {
                CallKitManager.shared.reportCallEnded(uuid: uuid, reason: .remoteEnded)
            }
            return
        }

        if let existing = activeCall {
            if existing.callId == callId {
                if existing.isKnock { clearIncomingKnock() }
                if existing.status == .ringing, expiresAt != nil {
                    armIncomingRingDeadline(for: existing, expiresAt: resolvedExpiry)
                }
                // A duplicate PushKit delivery may have refreshed CallKit with
                // the raw payload name after the WS path resolved a local
                // contact. Re-apply the app's authoritative presentation.
                let hideKnocker = existing.isKnock && existing.session == nil
                CallKitManager.shared.updateCall(
                    uuid: existing.uuid,
                    handle: callKitHandle(existing.remoteName, isKnock: hideKnocker),
                    displayName: callKitDisplayName(existing.remoteName,
                                                    isKnock: hideKnocker),
                    hasVideo: existing.isVideo)
                return
            }

            // Slide supports one live call. Never overwrite the current media
            // session with a second invitation; explicitly reject the newcomer
            // so its caller does not ring forever.
            if wasReportedByPushKit {
                CallKitManager.shared.reportCallEnded(uuid: uuid, reason: .failed)
            }
            rememberFinished(callId)
            Task { try? await api.declineCall(id: callId) }
            return
        }

        let isKnock = ringStyle == "knock"
        let name = isKnock
            ? "Someone"
            : displayNameForIncomingCall(fromUserId: fromUserId, fromName: fromName)
        if isKnock { clearIncomingKnock() }
        let call = ActiveCall(direction: .incoming,
                              remoteName: name,
                              remotePhone: "",
                              remoteUserId: fromUserId,
                              isVideo: videoEnabled,
                              status: .ringing,
                              isKnock: isKnock,
                              isGroup: type == .group,
                              uuid: uuid)
        call.callId = callId
        activeCall = call
        armIncomingRingDeadline(for: call, expiresAt: resolvedExpiry)
        if wasReportedByPushKit {
            CallKitManager.shared.updateCall(
                uuid: uuid, handle: callKitHandle(name, isKnock: isKnock),
                displayName: callKitDisplayName(name, isKnock: isKnock),
                hasVideo: call.isVideo)
        } else {
            Haptics.warning()
            CallKitManager.shared.reportIncomingCall(
                uuid: uuid, handle: callKitHandle(name, isKnock: isKnock),
                displayName: callKitDisplayName(name, isKnock: isKnock),
                hasVideo: call.isVideo) { [weak self] error in
                    guard error != nil else { return }
                    Task { @MainActor in
                        self?.handleIncomingCallReportFailure(callId: callId)
                    }
                }
        }
        Task { await reconcileActiveRingingCall() }
    }

    /// Show a brief end-state message on the call screen, then dismiss — the
    /// screen shouldn't just vanish when the other side declines or hangs up.
    private func windDown(_ call: ActiveCall, message: String, after seconds: Double) {
        guard call.status != .ended else { return }
        cancelIncomingRingDeadline(for: call)
        rememberFinished(call.callId)
        finishAnswer(call.uuid, success: false)
        call.endMessage = message
        call.status = .ended
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            guard let self, self.activeCall?.id == call.id else { return }
            self.activeCall = nil
        }
    }

    /// Re-place a failed 1:1 call with the same person and settings.
    func retryCall(_ failed: ActiveCall) {
        guard let userId = failed.remoteUserId, !userId.isEmpty, !failed.isGroup else { return }
        guard activeCall?.id == failed.id else { return }
        cancelIncomingRingDeadline(for: failed)
        let user = User(id: userId,
                        phone: failed.remotePhone,
                        displayName: failed.remoteName,
                        avatarUrl: nil,
                        createdAt: nil, lastSeenAt: nil)
        rememberFinished(failed.callId)
        CallKitManager.shared.reportCallEnded(uuid: failed.uuid, reason: .failed)
        resolveCallOnBackend(failed)
        activeCall = nil
        if failed.isKnock {
            startKnockCall(to: user, video: failed.isVideo)
        } else {
            startCall(to: user, video: failed.isVideo)
        }
    }

    func endActiveCall(fromCallKit: Bool = false) {
        guard let call = activeCall else { return }
        Haptics.strong()   // decisive: hang up
        cancelIncomingRingDeadline(for: call)
        rememberFinished(call.callId)
        finishAnswer(call.uuid, success: false)
        if !fromCallKit {
            CallKitManager.shared.endCall(uuid: call.uuid)
        }
        resolveCallOnBackend(call)
        activeCall = nil
    }

    func acceptIncoming() {
        guard let call = activeCall,
              call.direction == .incoming,
              call.status == .ringing,
              call.callId != nil else { return }
        Haptics.strong()   // decisive: answer
        cancelIncomingRingDeadline(for: call)
        answerRequestedCallIds.insert(call.uuid)
        // Move the app UI immediately, but let CXProvider own the actual accept
        // operation. Its action stays pending until `/accept` returns.
        call.status = .connecting
        CallKitManager.shared.answerCall(uuid: call.uuid) { [weak self, weak call] error in
            guard let error else { return }
            Task { @MainActor in
                guard let self, let call, self.activeCall?.id == call.id,
                      call.session == nil else { return }
                call.status = .failed
                self.rememberFinished(call.callId)
                CallKitManager.shared.reportCallEnded(uuid: call.uuid, reason: .failed)
                self.resolveCallOnBackend(call)
                self.finishAnswer(call.uuid, success: false)
#if DEBUG
                print("CallKit answer request failed: \(error.localizedDescription)")
#endif
            }
        }
    }

    private func performCallKitAnswer(call: ActiveCall, completion: @escaping (Bool) -> Void) {
        guard activeCall?.id == call.id,
              call.direction == .incoming,
              let id = call.callId,
              call.status != .ended else {
            completion(false)
            return
        }

        if call.session != nil {
            completion(true)
            return
        }
        answerRequestedCallIds.insert(call.uuid)
        answerCompletions[call.uuid, default: []].append(completion)
        guard acceptingCallIds.insert(call.uuid).inserted else { return }

        cancelIncomingRingDeadline(for: call)
        call.status = .connecting

        Task {
            do {
                guard await MediaPermissions.requestMicrophoneAccess() else {
                    throw CallAcceptanceError.microphoneDenied
                }
                guard activeCall?.id == call.id, call.status != .ended else {
                    finishAnswer(call.uuid, success: false)
                    return
                }
                if call.isVideo {
                    let cameraGranted = await MediaPermissions.requestCameraAccess()
                    guard activeCall?.id == call.id, call.status != .ended else {
                        finishAnswer(call.uuid, success: false)
                        return
                    }
                    if !cameraGranted {
                        // Foreground answers ask; background/cold answers cannot
                        // present UI and safely degrade to audio.
                        call.isVideo = false
                        CallKitManager.shared.updateCall(
                            uuid: call.uuid,
                            handle: callKitHandle(call.remoteName, isKnock: call.isKnock),
                            displayName: callKitDisplayName(
                                call.remoteName, isKnock: call.isKnock),
                            hasVideo: false)
                    }
                }
                let session = try await acceptCallWithRetry(id: id)
                guard activeCall?.id == call.id, call.status != .ended else {
                    if let callId = call.callIdForBackendResolution() {
                        await api.leaveCallBestEffort(id: callId)
                    }
                    finishAnswer(call.uuid, success: false)
                    return
                }
                revealAcceptedPeerIfNeeded(on: call, from: session)
                call.session = session
                finishAnswer(call.uuid, success: true)
            } catch {
                if let apiError = error as? APIError,
                   apiError.isAnsweredOnAnotherInstallation {
                    cancelIncomingRingDeadline(for: call)
                    rememberFinished(call.callId)
                    finishAnswer(call.uuid, success: false)
                    CallKitManager.shared.reportCallEnded(
                        uuid: call.uuid, reason: .answeredElsewhere)
                    if activeCall?.id == call.id {
                        activeCall = nil
                    }
                    return
                }
                if !Config.useMockData {
                    if let apiError = error as? APIError, apiError.shouldRetryCallAccept {
                        reconcileAmbiguousAccept(callId: id)
                    } else {
                        resolveCallOnBackend(call)
                    }
                }
                guard activeCall?.id == call.id else {
                    finishAnswer(call.uuid, success: false)
                    return
                }
                if Config.useMockData {
                    call.session = MockData.incomingSession(callId: id, video: call.isVideo)
                    finishAnswer(call.uuid, success: true)
                } else {
                    call.status = .failed
                    rememberFinished(call.callId)
                    CallKitManager.shared.reportCallEnded(uuid: call.uuid, reason: .failed)
                    finishAnswer(call.uuid, success: false)
                }
            }
        }
    }

    func declineIncoming(fromCallKit: Bool = false) {
        guard let call = activeCall else { return }
        Haptics.gentle()   // dismiss
        cancelIncomingRingDeadline(for: call)
        rememberFinished(call.callId)
        finishAnswer(call.uuid, success: false)
        if !fromCallKit {
            CallKitManager.shared.endCall(uuid: call.uuid)
        }
        if let id = call.callId {
            Task { try? await api.declineCall(id: id) }
        }
        activeCall = nil
    }

    // MARK: - Knocks

    /// This user's outbound display name on a knock: prefer the display name,
    /// fall back to phone, then a generic label.
    private var myKnockName: String {
        if let name = currentUser?.displayName, !name.isEmpty { return name }
        if let phone = currentUser?.phone, !phone.isEmpty { return phone }
        return "Someone"
    }

    /// Send a single knock tap to `userId`. Tracks `seq` + the last-tap time so
    /// `dt` (ms since the previous tap) is filled in. Plays the caller's own
    /// sound + haptic so they feel the rhythm they're tapping. Call once per tap.
    func sendKnockTap(to userId: String, playLocalFeedback: Bool = true) {
        // Reset the session whenever the target changes.
        if outgoingKnockTarget != userId {
            outgoingKnockTarget = userId
            outgoingKnockSeq = 0
            lastOutgoingKnockAt = nil
        }
        let now = Date()
        let dt: Int
        if let last = lastOutgoingKnockAt {
            dt = max(0, Int(now.timeIntervalSince(last) * 1000))
        } else {
            dt = 0
        }
        lastOutgoingKnockAt = now
        let seq = outgoingKnockSeq
        outgoingKnockSeq += 1

        // Local feedback so the caller feels their own taps.
        if playLocalFeedback { KnockHaptics.shared.knock() }

        signaling.sendKnock(to: userId, fromName: myKnockName, seq: seq, dt: dt)
    }

    /// Reset the outbound knock session (e.g. when the knock pad is dismissed).
    func resetKnockSession() {
        outgoingKnockTarget = nil
        outgoingKnockSeq = 0
        lastOutgoingKnockAt = nil
    }

    /// Tap back at whoever is currently tapping us, then clear the banner.
    func knockBack() {
        guard let knock = incomingKnock, let userId = knock.fromUserId else { return }
        sendKnockTap(to: userId)
    }

    /// Escalate the incoming knock into a real call.
    func callFromKnock(video: Bool = false) {
        guard let knock = incomingKnock,
              let userId = knock.fromUserId,
              !userId.isEmpty else { return }
        let user = User(id: userId,
                        phone: "",
                        displayName: displayNameForIncomingCall(
                            fromUserId: userId, fromName: knock.displayName),
                        avatarUrl: nil,
                        createdAt: nil, lastSeenAt: nil)
        clearIncomingKnock()
        startCall(to: user, video: video)
    }

    /// Handle one received knock tap: play sound + haptic, surface/refresh the
    /// banner, bump the pulse counter, and (re)arm the auto-clear timer.
    func receiveKnock(fromUserId: String?, fromName: String?, seq: Int?, dt: Int?) {
        let fromUserId = actionableUserId(fromUserId)
        KnockHaptics.shared.knock()

        // If the tap comes from the person whose call is on screen right now,
        // drive that call's UI (avatar thump on the ringing screen) instead of
        // stacking a banner underneath the full-screen cover.
        if let call = activeCall,
           (call.remoteUserId == fromUserId && fromUserId != nil
                || call.isKnock && fromUserId == nil) {
            call.knockPulse += 1
            // Remember the cadence (cap so a marathon knocker stays replayable).
            if call.knockRhythm.count < 12 {
                call.knockRhythm.append(Double(dt ?? 0) / 1000.0)
            }
            return
        }
        // A raw anonymous tap must never cover an active call's controls. If it
        // cannot be safely attributed to the current knock call, keep only the
        // sound/haptic feedback already played above.
        if activeCall != nil { return }

        if let existing = incomingKnock, existing.fromUserId == fromUserId {
            existing.pulse += 1
            existing.lastName = fromName ?? existing.lastName
        } else {
            let knock = IncomingKnock(fromUserId: fromUserId, fromName: fromName)
            incomingKnock = knock
        }
        armIncomingKnockClear()
    }

    private func armIncomingKnockClear() {
        incomingKnockClearTask?.cancel()
        incomingKnockClearTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            guard !Task.isCancelled else { return }
            self?.incomingKnock = nil
        }
    }

    func clearIncomingKnock() {
        incomingKnockClearTask?.cancel()
        incomingKnockClearTask = nil
        incomingKnock = nil
    }
}

/// Transient state backing the incoming-knock banner. `pulse` increments on
/// every received tap so the banner can re-animate per tap.
@MainActor
final class IncomingKnock: ObservableObject, Identifiable {
    let id = UUID()
    let fromUserId: String?
    private let initialName: String?
    /// Most recently seen name (knock messages may carry it each tap).
    @Published var lastName: String?
    /// Increments per received tap; the banner observes this to re-pulse.
    @Published var pulse: Int = 0

    init(fromUserId: String?, fromName: String?) {
        self.fromUserId = fromUserId
        self.initialName = fromName
        self.lastName = fromName
    }

    var displayName: String {
        if let name = lastName, !name.isEmpty { return name }
        if let name = initialName, !name.isEmpty { return name }
        return "Someone"
    }
}

// MARK: - Signaling delegate

extension AppState: SignalingClientDelegate {
    nonisolated func signaling(_ client: SignalingClient, didReceive event: SignalingEvent) {
        Task { @MainActor in
            switch event {
            case let .incomingCall(callId, fromUserId, fromName, type, videoEnabled,
                                   ringStyle, expiresAt):
                self.receiveIncomingCall(callId: callId, fromUserId: fromUserId,
                                         fromName: fromName, type: type,
                                         videoEnabled: videoEnabled,
                                         ringStyle: ringStyle,
                                         expiresAt: expiresAt,
                                         wasReportedByPushKit: false)
            case let .callEnded(callId):
                if let call = self.activeCall, call.callId == callId {
                    guard call.status != .ended else { break }
                    CallKitManager.shared.reportCallEnded(uuid: call.uuid)
                    self.windDown(call, message: "Call ended", after: 1.2)
                }
            case let .callDeclined(callId, _):
                if let call = self.activeCall, call.callId == callId {
                    // One member declining a group invitation does not end the
                    // call for everyone else; a later call_ended event is the
                    // authoritative group terminal signal.
                    guard !call.isGroup, call.status != .ended else { break }
                    Haptics.warning()   // they declined — make the dismissal felt
                    CallKitManager.shared.reportCallEnded(uuid: call.uuid, reason: .remoteEnded)
                    self.windDown(call, message: "They can't talk right now", after: 1.8)
                }
            case let .callAccepted(callId, byUserId):
                if let call = self.activeCall, call.callId == callId {
                    if call.direction == .outgoing {
                        call.status = .connecting
                        CallKitManager.shared.reportOutgoingConnected(uuid: call.uuid)
                    } else if self.acceptingCallIds.contains(call.uuid)
                                || self.answerRequestedCallIds.contains(call.uuid)
                                || call.session != nil
                    {
                        // This install initiated the answer; let its in-flight
                        // `/accept` continue when the backend fans the event back.
                        call.status = .connecting
                    } else {
                        let acceptedOnThisAccount = byUserId == self.currentUser?.id
                            || (self.currentUser == nil && !call.isGroup
                                && byUserId != nil && byUserId != call.remoteUserId)
                        guard acceptedOnThisAccount else { break }
                        self.cancelIncomingRingDeadline(for: call)
                        self.rememberFinished(call.callId)
                        self.finishAnswer(call.uuid, success: false)
                        CallKitManager.shared.reportCallEnded(
                            uuid: call.uuid, reason: .answeredElsewhere)
                        // Do not `/leave`: the same account's other install owns
                        // the joined participant and must remain in the call.
                        self.activeCall = nil
                    }
                }
            case let .knock(fromUserId, fromName, seq, dt):
                self.receiveKnock(fromUserId: fromUserId, fromName: fromName, seq: seq, dt: dt)
            case .contactsUpdated(_, _):
                await self.refreshContactCache()
            default:
                break
            }
        }
    }

    nonisolated func signalingDidConnect(_ client: SignalingClient) {}
    nonisolated func signalingDidDisconnect(_ client: SignalingClient) {}
}

private extension AppState {
    func receivePushedTerminal(type: String, callId: String) {
        guard !callId.isEmpty else { return }
        let uuid = PushService.uuid(for: callId)

        if type == "call_accepted" {
            if let call = activeCall, call.callId == callId {
                if call.direction == .outgoing {
                    call.status = .connecting
                    CallKitManager.shared.reportOutgoingConnected(uuid: call.uuid)
                    return
                }
                // The winning installation sees its own fanout while `/accept`
                // is in flight or after it has a session. Its keyed API response
                // is authoritative; idle sibling installations dismiss here.
                if acceptingCallIds.contains(call.uuid) || call.session != nil {
                    return
                }
                cancelIncomingRingDeadline(for: call)
                rememberFinished(callId)
                finishAnswer(call.uuid, success: false)
                CallKitManager.shared.reportCallEnded(
                    uuid: call.uuid, reason: .answeredElsewhere)
                activeCall = nil
                return
            }
            rememberFinished(callId)
            CallKitManager.shared.reportCallEnded(uuid: uuid, reason: .answeredElsewhere)
            return
        }

        guard type == "call_ended" || type == "call_declined" else { return }
        rememberFinished(callId)
        if let call = activeCall, call.callId == callId {
            cancelIncomingRingDeadline(for: call)
            finishAnswer(call.uuid, success: false)
            CallKitManager.shared.reportCallEnded(uuid: call.uuid, reason: .remoteEnded)
            // Background terminal delivery must tear media down immediately;
            // a delayed UI wind-down task may not run while iOS is suspended.
            activeCall = nil
        } else {
            CallKitManager.shared.reportCallEnded(uuid: uuid, reason: .remoteEnded)
        }
    }

    func handleIncomingCallReportFailure(callId: String) {
        guard let call = activeCall, call.callId == callId else { return }
        cancelIncomingRingDeadline(for: call)
        rememberFinished(call.callId)
        finishAnswer(call.uuid, success: false)
        resolveCallOnBackend(call)
        activeCall = nil
    }

    func armIncomingRingDeadline(for call: ActiveCall, expiresAt: Date) {
        cancelIncomingRingDeadline(for: call)
        let delay = min(max(0, expiresAt.timeIntervalSinceNow), 120)
        incomingRingDeadlineTasks[call.uuid] = Task { @MainActor [weak self, weak call] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard !Task.isCancelled,
                  let self, let call,
                  self.activeCall?.id == call.id,
                  call.direction == .incoming,
                  call.status == .ringing else { return }

            self.incomingRingDeadlineTasks.removeValue(forKey: call.uuid)
            self.rememberFinished(call.callId)
            self.finishAnswer(call.uuid, success: false)
            CallKitManager.shared.reportCallEnded(uuid: call.uuid, reason: .unanswered)
            self.resolveCallOnBackend(call)
            self.activeCall = nil
        }
    }

    func cancelIncomingRingDeadline(for call: ActiveCall) {
        incomingRingDeadlineTasks.removeValue(forKey: call.uuid)?.cancel()
    }

    func cancelAllIncomingRingDeadlines() {
        incomingRingDeadlineTasks.values.forEach { $0.cancel() }
        incomingRingDeadlineTasks.removeAll()
    }

    func resolveCallOnBackend(_ call: ActiveCall) {
        guard let callId = call.callIdForBackendResolution() else { return }
        if call.direction == .incoming, call.session == nil {
            // This installation never received a winning keyed session. Decline
            // is a no-op once a sibling has joined, whereas `/leave` would tear
            // down that sibling's participant.
            Task { _ = try? await api.declineCall(id: callId) }
        } else {
            Task { await api.leaveCallBestEffort(id: callId) }
        }
    }

    func acceptCallWithRetry(id: String) async throws -> CallSession {
        do {
            return try await api.acceptCall(id: id)
        } catch let error as APIError where error.shouldRetryCallAccept {
            // The server may have committed just before the response was lost.
            // A same-installation retry returns the winning session safely.
            try? await Task.sleep(nanoseconds: 350_000_000)
            return try await api.acceptCall(id: id)
        }
    }

    func reconcileAmbiguousAccept(callId: String) {
        Task {
            for delay in [700_000_000, 1_400_000_000, 2_800_000_000] as [UInt64] {
                try? await Task.sleep(nanoseconds: delay)
                do {
                    _ = try await api.acceptCall(id: callId)
                    await api.leaveCallBestEffort(id: callId)
                    return
                } catch let error as APIError {
                    if error.isAnsweredOnAnotherInstallation || !error.shouldRetryCallAccept {
                        return
                    }
                } catch {
                    continue
                }
            }
        }
    }

    func revealAcceptedPeerIfNeeded(on call: ActiveCall, from session: CallSession) {
        guard call.isKnock else { return }
        let creatorId = session.call.createdBy
        let creator = session.call.participants.first { $0.userId == creatorId }
        let revealedName = displayNameForIncomingCall(
            fromUserId: creatorId,
            fromName: creator?.displayName ?? creator?.phone)
        call.remoteUserId = creatorId
        call.remoteName = revealedName
        if !call.isGroup {
            call.memberNames = [revealedName]
        }
        CallKitManager.shared.updateCall(
            uuid: call.uuid, handle: revealedName,
            displayName: revealedName, hasVideo: call.isVideo)
    }

    func rememberFinished(_ callId: String?) {
        guard let callId, !callId.isEmpty else { return }
        recentlyFinishedCallIds[callId] = Date()
        pruneFinishedCallIds()
    }

    func pruneFinishedCallIds() {
        recentlyFinishedCallIds = recentlyFinishedCallIds.filter {
            Date().timeIntervalSince($0.value) < 300
        }
    }

    func finishAnswer(_ uuid: UUID, success: Bool) {
        answerRequestedCallIds.remove(uuid)
        acceptingCallIds.remove(uuid)
        let callbacks = answerCompletions.removeValue(forKey: uuid) ?? []
        callbacks.forEach { $0(success) }
    }

    func finishAnswerCompletions(success: Bool) {
        let callbacks = answerCompletions.values.flatMap { $0 }
        answerRequestedCallIds.removeAll()
        acceptingCallIds.removeAll()
        answerCompletions.removeAll()
        callbacks.forEach { $0(success) }
    }

    func displayNameForIncomingCall(fromUserId: String?, fromName: String?) -> String {
        if let fromUserId,
           let contact = contacts.first(where: { $0.contactUserId == fromUserId }) {
            let name = contact.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
            if !name.isEmpty { return name }
        }
        if let name = sanitizedRemoteName(fromName) {
            return name
        }
        return "Slide"
    }

    func actionableUserId(_ value: String?) -> String? {
        guard let value, !value.isEmpty,
              value != "00000000-0000-0000-0000-000000000000",
              value.localizedCaseInsensitiveCompare("unknown") != .orderedSame else {
            return nil
        }
        return value
    }

    func sanitizedRemoteName(_ fromName: String?) -> String? {
        if let name = fromName?.trimmingCharacters(in: .whitespacesAndNewlines),
           !name.isEmpty,
           name.localizedCaseInsensitiveCompare("unknown") != .orderedSame,
           name.localizedCaseInsensitiveCompare("someone") != .orderedSame {
            return name
        }
        return nil
    }

    func reconcileActiveRingingCall() async {
        guard let call = activeCall,
              let callId = call.callId else { return }
        do {
            let response = try await api.calls()
            guard let serverCall = response.calls.first(where: { $0.id == callId }) else {
                clearRingingCallIfStillActive(call)
                return
            }
            if call.status != .ringing {
                switch serverCall.status {
                case .ended, .missed, .declined:
                    CallKitManager.shared.reportCallEnded(uuid: call.uuid, reason: .remoteEnded)
                    windDown(call, message: "Call ended", after: 1.0)
                case .ringing, .active:
                    break
                }
                return
            }
            if let currentUserId = currentUser?.id,
               let participant = serverCall.participants.first(where: { $0.userId == currentUserId }),
               participant.state != .ringing {
                // Another device on this account already answered/declined, or
                // this invitation was otherwise resolved.
                clearRingingCallIfStillActive(call)
                return
            }
            switch serverCall.status {
            case .ringing:
                return
            case .active:
                if serverCall.type == .oneToOne {
                    clearRingingCallIfStillActive(call)
                }
            case .ended, .missed, .declined:
                clearRingingCallIfStillActive(call)
            }
        } catch {
            // Avoid hiding a real incoming call just because the network blipped.
        }
    }

    /// If WS delivery and APNs both failed, opening/foregrounding the app gets
    /// one last chance to recover a fresh server-side invitation. Old ringing
    /// rows are deliberately ignored so call history never starts ringing.
    func recoverRecentIncomingCall() async {
        guard activeCall == nil, let userId = currentUser?.id else { return }
        do {
            let response = try await api.calls()
            guard let serverCall = response.calls.first(where: { call in
                guard call.createdBy != userId,
                      let createdAt = call.createdAt,
                      Date().timeIntervalSince(createdAt) < 90,
                      let me = call.participants.first(where: { $0.userId == userId }),
                      me.state == .ringing else { return false }
                return call.status == .ringing || (call.status == .active && call.type == .group)
            }) else { return }

            let caller = serverCall.participants.first { $0.userId == serverCall.createdBy }
            receiveIncomingCall(
                callId: serverCall.id,
                fromUserId: serverCall.createdBy,
                fromName: caller?.displayName ?? caller?.phone,
                type: serverCall.type,
                videoEnabled: serverCall.videoEnabled ?? true,
                ringStyle: serverCall.ringStyle ?? "call",
                expiresAt: serverCall.createdAt?.addingTimeInterval(45),
                wasReportedByPushKit: false)
        } catch {
            // Recovery is best-effort; never replace a network blip with a fake
            // incoming call or dismiss real state.
        }
    }

    func clearRingingCallIfStillActive(_ call: ActiveCall) {
        guard activeCall?.id == call.id else { return }
        cancelIncomingRingDeadline(for: call)
        rememberFinished(call.callId)
        finishAnswer(call.uuid, success: false)
        CallKitManager.shared.reportCallEnded(uuid: call.uuid, reason: .remoteEnded)
        activeCall = nil
    }

    func refreshActiveCallDisplayName() {
        guard let call = activeCall,
              call.direction == .incoming,
              let remoteUserId = call.remoteUserId else { return }
        let name = displayNameForIncomingCall(fromUserId: remoteUserId, fromName: call.remoteName)
        guard name != call.remoteName else { return }
        call.remoteName = name
        if !call.isGroup {
            call.memberNames = [name]
        }
        // While a knock is still ringing, CallKit stays anonymous; the name is
        // revealed there on answer (acceptIncoming).
        if call.isKnock && call.status == .ringing { return }
        CallKitManager.shared.updateCall(uuid: call.uuid, handle: name,
                                         displayName: name,
                                         hasVideo: call.isVideo)
    }

    /// Knocks are anonymous until answered — "knock knock, who's there?".
    func callKitDisplayName(_ name: String, isKnock: Bool) -> String {
        isKnock ? "Knock knock…" : name
    }

    func callKitHandle(_ name: String, isKnock: Bool) -> String {
        isKnock ? "Knock Knock" : name
    }
}

// MARK: - CallKit delegate

extension AppState: CallKitManagerDelegate {
    nonisolated func callKitDidAnswer(callId: UUID, completion: @escaping (Bool) -> Void) {
        Task { @MainActor in
            guard let call = self.activeCall, call.uuid == callId else {
                completion(false)
                return
            }
            self.performCallKitAnswer(call: call, completion: completion)
        }
    }

    nonisolated func callKitDidEnd(callId: UUID) {
        Task { @MainActor in
            guard let call = self.activeCall, call.uuid == callId else { return }
            if call.direction == .incoming && call.status == .ringing {
                self.declineIncoming(fromCallKit: true)
            } else {
                self.endActiveCall(fromCallKit: true)
            }
        }
    }

    nonisolated func callKitDidSetMuted(callId: UUID, muted: Bool) {
        Task { @MainActor in
            guard let call = self.activeCall, call.uuid == callId else { return }
            call.systemMuted = muted
        }
    }

    nonisolated func callKitDidReset() {
        Task { @MainActor in
            guard self.activeCall != nil else { return }
            self.endActiveCall(fromCallKit: true)
        }
    }
}
