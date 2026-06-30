import Foundation
import PushKit
import CallKit

/// Bridges Apple PushKit (VoIP pushes) to CallKit so an incoming call/knock
/// rings natively even when the app is backgrounded, locked, or killed.
///
/// Flow:
///   APNs VoIP push (topic `app.exla.slide.voip`)
///     → `pushRegistry(_:didReceiveIncomingPushWith:)`
///     → CallKitManager.reportIncomingCall (REQUIRED on every VoIP push, or
///       iOS terminates the app for not reporting a call)
///     → on CallKit answer, AppState joins the call via the normal accept path.
///
/// iOS launches the app into the background to deliver a VoIP push, so this
/// works from a cold start.
final class PushService: NSObject, @unchecked Sendable {
    static let shared = PushService()

    private let registry = PKPushRegistry(queue: .main)

    /// The most recent VoIP token (hex), retained so we can (re)register once
    /// the user is authenticated even if the token arrived before sign-in.
    private(set) var voipToken: String?

    /// Standard APNs token (hex) from didRegisterForRemoteNotifications —
    /// registered with the backend for alert pushes once signed in.
    var standardTokenHex: String?

    private struct IncomingPayload {
        let callId: String
        let fromUserId: String?
        let fromName: String?
        let callType: CallType
        let videoEnabled: Bool
        let ringStyle: String
        let expiresAt: Date?
    }

    private struct TerminalPayload {
        let type: String
        let callId: String
    }

    /// PushKit can wake the process before SwiftUI constructs AppState. Buffer
    /// payloads until the handler exists so a cold-launch call is not reduced to
    /// a CallKit screen with no app-side call to accept.
    private var pendingIncoming: [IncomingPayload] = []
    private var pendingTerminal: [TerminalPayload] = []
    private var pendingReportFailures: [String] = []

    /// Invoked when a VoIP push arrives. AppState wires this up to surface the
    /// call and join it on answer. Parameters mirror the push payload. Setting
    /// the handler replays anything received during cold launch.
    var onIncomingCall: ((_ callId: String, _ fromUserId: String?,
                          _ fromName: String?, _ callType: CallType,
                          _ videoEnabled: Bool, _ ringStyle: String,
                          _ expiresAt: Date?) -> Void)? {
        didSet { drainPendingEventsIfPossible() }
    }

    /// A rejected CXProvider report means there is no native surface the user
    /// can answer. AppState must remove its mirrored call and resolve the server
    /// invitation instead of leaving a silent/unanswerable screen.
    var onIncomingCallReportFailed: ((_ callId: String) -> Void)? {
        didSet { drainPendingEventsIfPossible() }
    }

    /// Standard background APNs carries terminal state because sending another
    /// VoIP push just to cancel CallKit violates PushKit's call-only contract.
    /// Buffer it across a cold launch exactly like the incoming VoIP payload.
    var onCallTerminal: ((_ type: String, _ callId: String) -> Void)? {
        didSet { drainPendingEventsIfPossible() }
    }

    private override init() { super.init() }

    /// Register for VoIP pushes. Safe to call once at launch.
    func start() {
        registry.delegate = self
        registry.desiredPushTypes = [.voIP]
    }

    private func deliver(_ payload: IncomingPayload) {
        guard let onIncomingCall else {
            // Replace a duplicate payload while preserving distinct calls; a
            // WS + retry push should never create multiple pending screens.
            pendingIncoming.removeAll { $0.callId == payload.callId }
            pendingIncoming.append(payload)
            return
        }
        onIncomingCall(payload.callId, payload.fromUserId, payload.fromName,
                       payload.callType, payload.videoEnabled, payload.ringStyle,
                       payload.expiresAt)
    }

    private func deliverReportFailure(callId: String) {
        guard let onIncomingCallReportFailed else {
            if !pendingReportFailures.contains(callId) {
                pendingReportFailures.append(callId)
            }
            return
        }
        onIncomingCallReportFailed(callId)
    }

    func receiveTerminalPush(type: String, callId: String) {
        guard Thread.isMainThread else {
            DispatchQueue.main.async { [weak self] in
                self?.receiveTerminalPush(type: type, callId: callId)
            }
            return
        }
        let payload = TerminalPayload(type: type, callId: callId)
        guard let onCallTerminal else {
            pendingTerminal.removeAll { $0.callId == callId }
            pendingTerminal.append(payload)
            return
        }
        onCallTerminal(type, callId)
    }

    private func drainPendingEventsIfPossible() {
        if let onIncomingCall, !pendingIncoming.isEmpty {
            let payloads = pendingIncoming
            pendingIncoming.removeAll()
            for payload in payloads {
                onIncomingCall(payload.callId, payload.fromUserId, payload.fromName,
                               payload.callType, payload.videoEnabled, payload.ringStyle,
                               payload.expiresAt)
            }
        }
        if let onIncomingCallReportFailed, !pendingReportFailures.isEmpty {
            let failures = pendingReportFailures
            pendingReportFailures.removeAll()
            failures.forEach(onIncomingCallReportFailed)
        }
        if let onCallTerminal, !pendingTerminal.isEmpty {
            let payloads = pendingTerminal
            pendingTerminal.removeAll()
            for payload in payloads {
                onCallTerminal(payload.type, payload.callId)
            }
        }
    }
}

// MARK: - PKPushRegistryDelegate

extension PushService: PKPushRegistryDelegate {
    func pushRegistry(_ registry: PKPushRegistry,
                      didUpdate pushCredentials: PKPushCredentials,
                      for type: PKPushType) {
        guard type == .voIP else { return }
        let hex = pushCredentials.token.map { String(format: "%02x", $0) }.joined()
        let previous = voipToken
        voipToken = hex
        // Register with the backend if we're already signed in; otherwise the
        // post-sign-in hook in AppState will pick up `voipToken`.
        Task {
            if let previous, previous != hex, TokenStore.shared.isAuthenticated {
                try? await APIClient.shared.unregisterPushToken(previous)
            }
            _ = try? await APIClient.shared.registerPushToken(hex)
        }
    }

    func pushRegistry(_ registry: PKPushRegistry,
                      didInvalidatePushTokenFor type: PKPushType) {
        guard type == .voIP else { return }
        let invalidatedToken = voipToken
        voipToken = nil
        if let invalidatedToken, TokenStore.shared.isAuthenticated {
            Task { try? await APIClient.shared.unregisterPushToken(invalidatedToken) }
        }
    }

    func pushRegistry(_ registry: PKPushRegistry,
                      didReceiveIncomingPushWith payload: PKPushPayload,
                      for type: PKPushType,
                      completion: @escaping () -> Void) {
        guard type == .voIP else { completion(); return }

        let dict = payload.dictionaryPayload
        let callId = (dict["callId"] as? String) ?? (dict["id"] as? String) ?? ""
        let eventType = (dict["type"] as? String) ?? "incoming_call"
        let isTapOnly = eventType == "knock" || ((dict["knock"] as? Bool) == true && callId.isEmpty)
        guard eventType == "incoming_call", !isTapOnly, !callId.isEmpty else {
            // The backend contract sends only real incoming calls over VoIP.
            // If that contract is ever violated, iOS still requires every VoIP
            // delivery to be reported to CallKit. Report-and-end a failed
            // placeholder instead of completing silently (which can cause the
            // OS to terminate or throttle the app).
            let fallbackUUID = callId.isEmpty ? UUID() : Self.uuid(for: callId)
            CallKitManager.shared.reportIncomingCall(
                uuid: fallbackUUID, handle: "Slide", displayName: "Slide",
                hasVideo: false
            ) { _ in
                CallKitManager.shared.reportCallEnded(uuid: fallbackUUID, reason: .failed)
                completion()
            }
            return
        }
        let fromUserId = dict["fromUserId"] as? String
        let fromName = Self.sanitizedName(dict["fromName"] as? String) ?? "Slide"
        let callTypeRaw = (dict["callType"] as? String) ?? CallType.oneToOne.rawValue
        let callType = CallType(rawValue: callTypeRaw) ?? .oneToOne
        let hasVideo = Self.boolValue(dict["videoEnabled"]) ?? true
        let ringStyle = (dict["ringStyle"] as? String)
            ?? ((Self.boolValue(dict["knock"]) ?? false) ? "knock" : "call")
        let expiresAt = Self.epochMillisecondsDate(dict["expiresAt"])
        // Knocks ring anonymously — the whole point is "knock knock, who's
        // there?": you find out by answering. Normal calls show the name.
        let isKnock = ringStyle == "knock"
        let displayName = isKnock ? "Knock knock…" : fromName
        let handle = isKnock ? "Knock Knock" : fromName

        // CRITICAL: report an incoming call to CallKit synchronously on every
        // VoIP push, before returning, or iOS will kill the app. We mint a
        // stable UUID derived from the callId so the in-app accept path can
        // match this CallKit call to the server-side call.
        let uuid = Self.uuid(for: callId)
        let incoming = IncomingPayload(callId: callId, fromUserId: fromUserId,
                                       fromName: fromName, callType: callType,
                                       videoEnabled: hasVideo, ringStyle: ringStyle,
                                       expiresAt: expiresAt)

        let isExpired = expiresAt.map { $0 <= Date() } ?? false

        CallKitManager.shared.reportIncomingCall(
            uuid: uuid, handle: handle, displayName: displayName,
            hasVideo: hasVideo) { [weak self] error in
                if error != nil, !isExpired {
                    self?.deliverReportFailure(callId: callId)
                } else if isExpired, error == nil {
                    // Every VoIP push is still reported, but an invitation that
                    // expired in transit must never surface as answerable.
                    CallKitManager.shared.reportCallEnded(uuid: uuid, reason: .unanswered)
                }
                completion()
            }

        // Hand off even when expired so AppState can tombstone the call without
        // creating UI; this prevents a duplicate WS event from resurrecting it.
        deliver(incoming)
    }

    /// Derive a stable UUID from the server call id so the CallKit call UUID is
    /// the same one AppState uses for this call. Falls back to a random UUID.
    static func uuid(for callId: String) -> UUID {
        if let u = UUID(uuidString: callId) { return u }
        // Deterministic UUID from an arbitrary string via a hash of its bytes.
        var bytes = Array(callId.utf8)
        var digest = [UInt8](repeating: 0, count: 16)
        for (i, b) in bytes.enumerated() { digest[i % 16] ^= b &+ UInt8(i & 0xff) }
        // Set version (4) and variant bits so it's a well-formed UUID.
        digest[6] = (digest[6] & 0x0f) | 0x40
        digest[8] = (digest[8] & 0x3f) | 0x80
        bytes.removeAll()
        return UUID(uuid: (digest[0], digest[1], digest[2], digest[3],
                           digest[4], digest[5], digest[6], digest[7],
                           digest[8], digest[9], digest[10], digest[11],
                           digest[12], digest[13], digest[14], digest[15]))
    }

    private static func sanitizedName(_ value: String?) -> String? {
        guard let name = value?.trimmingCharacters(in: .whitespacesAndNewlines),
              !name.isEmpty,
              name.localizedCaseInsensitiveCompare("unknown") != .orderedSame,
              name.localizedCaseInsensitiveCompare("someone") != .orderedSame else {
            return nil
        }
        return name
    }

    private static func boolValue(_ value: Any?) -> Bool? {
        switch value {
        case let b as Bool:
            return b
        case let s as String:
            if s.caseInsensitiveCompare("true") == .orderedSame { return true }
            if s.caseInsensitiveCompare("false") == .orderedSame { return false }
            return nil
        case let n as NSNumber:
            return n.boolValue
        default:
            return nil
        }
    }

    private static func epochMillisecondsDate(_ value: Any?) -> Date? {
        let milliseconds: Double?
        switch value {
        case let string as String: milliseconds = Double(string)
        case let double as Double: milliseconds = double
        case let int as Int: milliseconds = Double(int)
        case let number as NSNumber: milliseconds = number.doubleValue
        default: milliseconds = nil
        }
        guard let milliseconds, milliseconds.isFinite, milliseconds > 0 else { return nil }
        return Date(timeIntervalSince1970: milliseconds / 1_000)
    }
}
