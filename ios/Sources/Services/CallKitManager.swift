import Foundation
import CallKit
import AVFoundation
#if canImport(LiveKit)
import LiveKit
#endif

/// Bridges Slide calls to the system call UI via CallKit (CXProvider) so calls
/// ring natively and integrate with the OS call experience.
protocol CallKitManagerDelegate: AnyObject {
    /// Call `completion(true)` only after control-plane acceptance succeeds.
    /// CallKit keeps the answer action pending until then instead of showing a
    /// connected system call that the app failed to join.
    func callKitDidAnswer(callId: UUID, completion: @escaping (Bool) -> Void)
    func callKitDidEnd(callId: UUID)
    func callKitDidSetMuted(callId: UUID, muted: Bool)
    func callKitDidReset()
}

final class CallKitManager: NSObject, @unchecked Sendable {
    static let shared = CallKitManager()

    /// CallKit can invoke an action while the app is still cold-launching from
    /// PushKit. Keep actions until AppState installs its delegate; dropping an
    /// early answer here leaves the system UI connected to a call the app never
    /// joins.
    weak var delegate: CallKitManagerDelegate? {
        didSet { deliverPendingActionsIfPossible() }
    }

    private let provider: CXProvider
    private let callController = CXCallController()

    /// The most recently reported call. The product only supports one active
    /// call, but `reportedCallIds` is a set so duplicate WS + VoIP delivery can
    /// be made idempotent without racing CallKit's async report completion.
    private(set) var activeCallId: UUID?
    private var reportedCallIds = Set<UUID>()
    private var reportingCallIds = Set<UUID>()
    private var connectedCallIds = Set<UUID>()
    private var deferredEndReasons: [UUID: CXCallEndedReason] = [:]

    private enum PendingAction {
        case answer(CXAnswerCallAction)
        case end(UUID)
        case muted(UUID, Bool)
        case reset
    }

    private var pendingActions: [PendingAction] = []

    /// Short connected/ended chimes (see Resources/RINGTONE.md). Held strongly so
    /// playback isn't cut off by deallocation.
    private var chimePlayer: AVAudioPlayer?

    private func playChime(_ name: String) {
        guard let url = Bundle.main.url(forResource: name, withExtension: "caf") else { return }
        do {
            let player = try AVAudioPlayer(contentsOf: url)
            player.volume = 0.7
            player.prepareToPlay()
            player.play()
            chimePlayer = player
        } catch {
            // Non-fatal: a missing/unreadable chime just means no sound.
        }
    }

    override init() {
#if canImport(LiveKit)
        // LiveKit must not start/configure its audio engine before CallKit owns
        // the AVAudioSession. Otherwise cold/lock-screen answers can connect
        // with no microphone or an incorrect route.
        AudioManager.shared.audioSession.isAutomaticConfigurationEnabled = false
        try? AudioManager.shared.setEngineAvailability(.none)
#endif
        let config = CXProviderConfiguration()
        config.supportsVideo = true
        config.maximumCallGroups = 1
        config.maximumCallsPerCallGroup = 1
        config.supportedHandleTypes = [.phoneNumber, .generic]
        // Slide has its own recents with the server call id and participant
        // context needed to retry. System recents cannot reconstruct that call
        // (and anonymous knocks would show as "Knock Knock"), so omit them.
        config.includesCallsInRecents = false
        // Use a bundled custom ringtone only when one is present; otherwise CallKit
        // falls back to the default system ringtone. (See Resources/RINGTONE.md.)
        if Bundle.main.url(forResource: "ringtone", withExtension: "caf") != nil {
            config.ringtoneSound = "ringtone.caf"
        }
        provider = CXProvider(configuration: config)
        super.init()
        provider.setDelegate(self, queue: nil)
    }

    // MARK: - Incoming

    func reportIncomingCall(uuid: UUID, handle: String, displayName: String,
                            hasVideo: Bool, completion: ((Error?) -> Void)? = nil) {
        // The backend intentionally delivers through both the realtime socket
        // and PushKit. Whichever arrives second should update the same native
        // call, never ask CXProvider to create it again.
        if reportedCallIds.contains(uuid) {
            updateCall(uuid: uuid, handle: handle, displayName: displayName,
                       hasVideo: hasVideo)
            completion?(nil)
            return
        }

        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: handle)
        update.localizedCallerName = displayName
        update.hasVideo = hasVideo
        update.supportsHolding = false
        update.supportsGrouping = false
        update.supportsUngrouping = false
        reportedCallIds.insert(uuid)
        reportingCallIds.insert(uuid)
        provider.reportNewIncomingCall(with: uuid, update: update) { [weak self] error in
            DispatchQueue.main.async {
                guard let self else {
                    completion?(error)
                    return
                }
                self.reportingCallIds.remove(uuid)
                if error == nil {
                    self.activeCallId = uuid
                    if let reason = self.deferredEndReasons.removeValue(forKey: uuid) {
                        self.finishReportedCall(uuid: uuid, reason: reason)
                    }
                } else {
                    self.reportedCallIds.remove(uuid)
                    self.deferredEndReasons.removeValue(forKey: uuid)
                }
                completion?(error)
            }
        }
    }

    func updateCall(uuid: UUID, handle: String, displayName: String, hasVideo: Bool) {
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: handle)
        update.localizedCallerName = displayName
        update.hasVideo = hasVideo
        update.supportsHolding = false
        update.supportsGrouping = false
        update.supportsUngrouping = false
        provider.reportCall(with: uuid, updated: update)
    }

    // MARK: - Outgoing

    func startOutgoingCall(uuid: UUID, handle: String, displayName: String, hasVideo: Bool,
                           completion: ((Error?) -> Void)? = nil) {
        guard !reportedCallIds.contains(uuid) else {
            completion?(nil)
            return
        }
        reportedCallIds.insert(uuid)
        let cxHandle = CXHandle(type: .generic, value: handle)
        let action = CXStartCallAction(call: uuid, handle: cxHandle)
        action.isVideo = hasVideo
        action.contactIdentifier = displayName
        callController.request(CXTransaction(action: action)) { [weak self] error in
            DispatchQueue.main.async {
                if error == nil {
                    self?.activeCallId = uuid
                } else {
                    self?.reportedCallIds.remove(uuid)
                }
                completion?(error)
            }
        }

        let update = CXCallUpdate()
        update.localizedCallerName = displayName
        update.hasVideo = hasVideo
        provider.reportCall(with: uuid, updated: update)
    }

    func answerCall(uuid: UUID, completion: ((Error?) -> Void)? = nil) {
        let action = CXAnswerCallAction(call: uuid)
        callController.request(CXTransaction(action: action)) { error in
            DispatchQueue.main.async { completion?(error) }
        }
    }

    func setMuted(uuid: UUID, muted: Bool) {
        let action = CXSetMutedCallAction(call: uuid, muted: muted)
        callController.request(CXTransaction(action: action)) { _ in }
    }

    func reportOutgoingConnected(uuid: UUID) {
        guard connectedCallIds.insert(uuid).inserted else { return }
        provider.reportOutgoingCall(with: uuid, connectedAt: Date())
        playChime("pickup")
    }

    // MARK: - End

    func endCall(uuid: UUID) {
        let action = CXEndCallAction(call: uuid)
        callController.request(CXTransaction(action: action)) { _ in }
    }

    func reportCallEnded(uuid: UUID, reason: CXCallEndedReason = .remoteEnded) {
        // `reportNewIncomingCall` is asynchronous. A stale VoIP push can be
        // reconciled as ended before that completion returns, so defer the end
        // instead of reporting it against a call CallKit does not know yet.
        if reportingCallIds.contains(uuid) {
            deferredEndReasons[uuid] = reason
            return
        }
        finishReportedCall(uuid: uuid, reason: reason)
    }

    private func finishReportedCall(uuid: UUID, reason: CXCallEndedReason) {
        guard reportedCallIds.remove(uuid) != nil else { return }
        connectedCallIds.remove(uuid)
        provider.reportCall(with: uuid, endedAt: Date(), reason: reason)
        playChime("hangup")
        if activeCallId == uuid { activeCallId = nil }
    }

    private func dispatchOrQueue(_ action: PendingAction) {
        guard let delegate else {
            pendingActions.append(action)
            return
        }
        switch action {
        case .answer(let answerAction):
            delegate.callKitDidAnswer(callId: answerAction.callUUID) { [weak self] success in
                if success {
                    self?.connectedCallIds.insert(answerAction.callUUID)
                    self?.playChime("pickup")
                    answerAction.fulfill()
                } else {
                    answerAction.fail()
                }
            }
        case .end(let uuid): delegate.callKitDidEnd(callId: uuid)
        case .muted(let uuid, let muted):
            delegate.callKitDidSetMuted(callId: uuid, muted: muted)
        case .reset: delegate.callKitDidReset()
        }
    }

    private func deliverPendingActionsIfPossible() {
        guard delegate != nil, !pendingActions.isEmpty else { return }
        let actions = pendingActions
        pendingActions.removeAll()
        actions.forEach(dispatchOrQueue)
    }
}

// MARK: - CXProviderDelegate

extension CallKitManager: CXProviderDelegate {
    func providerDidReset(_ provider: CXProvider) {
        activeCallId = nil
        reportedCallIds.removeAll()
        reportingCallIds.removeAll()
        connectedCallIds.removeAll()
        deferredEndReasons.removeAll()
        dispatchOrQueue(.reset)
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        dispatchOrQueue(.answer(action))
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        dispatchOrQueue(.end(action.callUUID))
        reportedCallIds.remove(action.callUUID)
        reportingCallIds.remove(action.callUUID)
        connectedCallIds.remove(action.callUUID)
        deferredEndReasons.removeValue(forKey: action.callUUID)
        if activeCallId == action.callUUID { activeCallId = nil }
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        dispatchOrQueue(.muted(action.callUUID, action.isMuted))
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        action.fulfill()
    }

    func provider(_ provider: CXProvider, timedOutPerforming action: CXAction) {
        action.fail()
        if let answer = action as? CXAnswerCallAction {
            dispatchOrQueue(.end(answer.callUUID))
            finishReportedCall(uuid: answer.callUUID, reason: .failed)
        }
    }

    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        do {
            try audioSession.setCategory(.playAndRecord, mode: .voiceChat,
                                         options: [.allowBluetooth, .allowBluetoothA2DP])
#if canImport(LiveKit)
            try AudioManager.shared.setEngineAvailability(.default)
#endif
        } catch {
#if DEBUG
            print("Call audio activation failed: \(error.localizedDescription)")
#endif
        }
    }

    func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
#if canImport(LiveKit)
        try? AudioManager.shared.setEngineAvailability(.none)
#endif
    }
}
