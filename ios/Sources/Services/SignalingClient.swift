import Foundation

// MARK: - Signaling events (server -> client)

enum SignalingEvent {
    case incomingCall(callId: String, fromUserId: String?, fromName: String?,
                      type: CallType, videoEnabled: Bool, ringStyle: String,
                      expiresAt: Date?)
    case callAccepted(callId: String, byUserId: String?)
    case callDeclined(callId: String, byUserId: String?)
    case callEnded(callId: String)
    case participantJoined(callId: String, userId: String)
    case participantLeft(callId: String, userId: String)
    case presenceUpdate(userId: String, online: Bool)
    case contactsUpdated(userId: String?, phone: String?)
    /// A lightweight presence ping — one event per received tap.
    case knock(fromUserId: String?, fromName: String?, seq: Int?, dt: Int?)
    case unknown(type: String)
}

protocol SignalingClientDelegate: AnyObject {
    func signaling(_ client: SignalingClient, didReceive event: SignalingEvent)
    func signalingDidConnect(_ client: SignalingClient)
    func signalingDidDisconnect(_ client: SignalingClient)
}

/// App-plane WebSocket: `GET /v1/ws?token=<accessToken>`.
/// Handles incoming_call/call_accepted/etc with reconnect + exponential backoff.
final class SignalingClient: NSObject, @unchecked Sendable {
    weak var delegate: SignalingClientDelegate?

    private let baseURL: URL
    private let tokens: TokenStore
    private var session: URLSession!
    private var task: URLSessionWebSocketTask?

    private var isStarted = false
    private var reconnectAttempt = 0
    private var heartbeatTimer: DispatchSourceTimer?
    private var socketGeneration = 0
    private var retryGeneration = 0
    private struct PendingMessage {
        let text: String
        let expiresAt: Date
    }
    private var pendingKnocks: [PendingMessage] = []
    private let queue = DispatchQueue(label: "app.slide.signaling")

    init(baseURL: URL = Config.apiBaseURL, tokens: TokenStore = .shared) {
        self.baseURL = baseURL
        self.tokens = tokens
        super.init()
        self.session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
    }

    // MARK: Lifecycle

    func connect() {
        queue.async { [weak self] in
            guard let self else { return }
            self.isStarted = true
            guard self.task == nil else { return }
            self.openSocket()
        }
    }

    /// Cancel a stale/suspended socket and connect immediately. Foregrounding
    /// calls this so `connect()` cannot be a no-op merely because an old task
    /// still exists locally.
    func reconnectNow() {
        queue.async { [weak self] in
            guard let self else { return }
            self.isStarted = true
            self.retryGeneration += 1
            self.reconnectAttempt = 0
            self.stopHeartbeat()
            let oldTask = self.task
            self.task = nil
            oldTask?.cancel(with: .goingAway, reason: nil)
            self.openSocket()
        }
    }

    func disconnect() {
        queue.async { [weak self] in
            guard let self else { return }
            self.isStarted = false
            self.retryGeneration += 1
            self.stopHeartbeat()
            let oldTask = self.task
            self.task = nil
            oldTask?.cancel(with: .goingAway, reason: nil)
        }
    }

    // MARK: Internal

    private func wsURL() -> URL? {
        guard let token = tokens.accessToken else { return nil }
        // Derive ws(s) scheme from the http(s) base.
        guard var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else {
            return nil
        }
        components.scheme = (baseURL.scheme == "https") ? "wss" : "ws"
        // baseURL path already ends with /v1 — append /ws.
        let basePath = components.path
        components.path = basePath + "/ws"
        components.queryItems = [URLQueryItem(name: "token", value: token)]
        return components.url
    }

    private func openSocket() {
        guard isStarted, let url = wsURL() else { return }
        let task = session.webSocketTask(with: url)
        socketGeneration += 1
        let generation = socketGeneration
        self.task = task
        task.resume()
        receiveLoop(task: task, generation: generation)
    }

    private func receiveLoop(task: URLSessionWebSocketTask, generation: Int) {
        task.receive { [weak self] result in
            guard let self else { return }
            self.queue.async {
                guard self.task === task, self.socketGeneration == generation else { return }
                switch result {
                case .success(let message):
                    self.handle(message)
                    self.receiveLoop(task: task, generation: generation)
                case .failure:
                    self.handleDisconnect(task: task, generation: generation)
                }
            }
        }
    }

    private func handle(_ message: URLSessionWebSocketTask.Message) {
        let data: Data?
        switch message {
        case .string(let s): data = s.data(using: .utf8)
        case .data(let d): data = d
        @unknown default: data = nil
        }
        guard let data,
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = obj["type"] as? String else { return }

        let event = Self.parse(type: type, obj: obj)
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.delegate?.signaling(self, didReceive: event)
        }
    }

    static func parse(type: String, obj: [String: Any]) -> SignalingEvent {
        switch type {
        case "incoming_call":
            let typeStr = obj["callType"] as? String ?? obj["type2"] as? String
            let call = obj["call"] as? [String: Any]
            let from = obj["from"] as? [String: Any]
            let callType = CallType(rawValue: typeStr ?? "one_to_one") ?? .oneToOne
            let videoEnabled = boolValue(obj["videoEnabled"])
                ?? boolValue(call?["videoEnabled"])
                ?? true
            let ringStyle = (obj["ringStyle"] as? String)
                ?? (call?["ringStyle"] as? String)
                ?? ((boolValue(obj["knock"]) ?? false) ? "knock" : "call")
            return .incomingCall(
                callId: (obj["callId"] as? String) ?? (obj["id"] as? String) ?? (call?["id"] as? String) ?? "",
                fromUserId: obj["fromUserId"] as? String ?? (obj["from"] as? String) ?? (from?["id"] as? String),
                fromName: obj["fromName"] as? String ?? obj["fromDisplayName"] as? String
                    ?? (from?["displayName"] as? String) ?? (from?["phone"] as? String),
                type: callType,
                videoEnabled: videoEnabled,
                ringStyle: ringStyle,
                expiresAt: epochMillisecondsDate(obj["expiresAt"] ?? call?["expiresAt"]))
        case "call_accepted":
            return .callAccepted(callId: callIdOf(obj),
                                 byUserId: obj["byUserId"] as? String
                                    ?? obj["userId"] as? String)
        case "call_declined":
            return .callDeclined(callId: callIdOf(obj),
                                 byUserId: obj["byUserId"] as? String
                                    ?? obj["userId"] as? String)
        case "call_ended":
            return .callEnded(callId: callIdOf(obj))
        case "participant_joined":
            return .participantJoined(callId: callIdOf(obj), userId: obj["userId"] as? String ?? "")
        case "participant_left":
            return .participantLeft(callId: callIdOf(obj), userId: obj["userId"] as? String ?? "")
        case "presence_update":
            return .presenceUpdate(userId: obj["userId"] as? String ?? "",
                                   online: obj["online"] as? Bool ?? false)
        case "contacts_updated":
            return .contactsUpdated(userId: obj["userId"] as? String,
                                    phone: obj["phone"] as? String)
        case "knock":
            return .knock(fromUserId: obj["fromUserId"] as? String,
                          fromName: obj["fromName"] as? String,
                          seq: intValue(obj["seq"]),
                          dt: intValue(obj["dt"]))
        default:
            return .unknown(type: type)
        }
    }

    private static func callIdOf(_ obj: [String: Any]) -> String {
        (obj["callId"] as? String) ?? (obj["id"] as? String) ?? ""
    }

    /// JSON numbers may decode as Int, Double, or NSNumber — coerce to Int and
    /// pass through nulls/missing values as nil.
    private static func intValue(_ value: Any?) -> Int? {
        switch value {
        case let i as Int: return i
        case let d as Double: return Int(d)
        case let n as NSNumber: return n.intValue
        default: return nil
        }
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

    /// Incoming expiry is transported as epoch milliseconds; accept JSON
    /// strings and numbers because APNs/WS serializers differ.
    private static func epochMillisecondsDate(_ value: Any?) -> Date? {
        let milliseconds: Double?
        switch value {
        case let string as String:
            milliseconds = Double(string)
        case let double as Double:
            milliseconds = double
        case let int as Int:
            milliseconds = Double(int)
        case let number as NSNumber:
            milliseconds = number.doubleValue
        default:
            milliseconds = nil
        }
        guard let milliseconds, milliseconds.isFinite, milliseconds > 0 else { return nil }
        return Date(timeIntervalSince1970: milliseconds / 1_000)
    }

    // MARK: Outbound

    func send(_ object: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: object),
              let string = String(data: data, encoding: .utf8) else { return }
        let isKnock = object["type"] as? String == "knock"
        queue.async { [weak self] in
            guard let self else { return }
            guard let task = self.task else {
                if isKnock { self.enqueueKnock(string) }
                if self.isStarted {
                    self.retryGeneration += 1
                    self.openSocket()
                }
                return
            }
            let generation = self.socketGeneration
            task.send(.string(string)) { [weak self, weak task] error in
                guard error != nil, let self, let task else { return }
                self.queue.async {
                    if isKnock { self.enqueueKnock(string) }
                    self.handleDisconnect(task: task, generation: generation)
                }
            }
        }
    }

    func presencePing() { send(["type": "presence_ping"]) }

    /// Relay a single knock tap to `to` (a callee user-id UUID string). Each tap
    /// is its own message; `seq` increments per knock session and `dt` is the
    /// gap in ms since the previous tap (0 for the first tap).
    func sendKnock(to: String, fromName: String, seq: Int, dt: Int) {
        send(["type": "knock", "to": to, "fromName": fromName, "seq": seq, "dt": dt])
    }
    private func heartbeat() { send(["type": "heartbeat"]) }

    private func enqueueKnock(_ text: String) {
        pendingKnocks.removeAll { $0.expiresAt <= Date() }
        pendingKnocks.append(PendingMessage(text: text, expiresAt: Date().addingTimeInterval(5)))
        if pendingKnocks.count > 20 { pendingKnocks.removeFirst(pendingKnocks.count - 20) }
    }

    private func flushPendingKnocks(on task: URLSessionWebSocketTask) {
        let now = Date()
        let messages = pendingKnocks.filter { $0.expiresAt > now }
        pendingKnocks.removeAll()
        for message in messages {
            task.send(.string(message.text)) { _ in }
        }
    }

    private func startHeartbeat() {
        stopHeartbeat()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 25, repeating: 25)
        timer.setEventHandler { [weak self] in self?.heartbeat() }
        heartbeatTimer = timer
        timer.resume()
    }

    private func stopHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = nil
    }

    // MARK: Reconnect with exponential backoff

    private func handleDisconnect(task closedTask: URLSessionWebSocketTask,
                                  generation: Int) {
        guard task === closedTask, socketGeneration == generation else { return }
        task = nil
        stopHeartbeat()
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.delegate?.signalingDidDisconnect(self)
        }
        guard isStarted else { return }
        reconnectAttempt += 1
        let delay = min(pow(2.0, Double(reconnectAttempt)), 30.0)
        retryGeneration += 1
        let retry = retryGeneration
        queue.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self, self.isStarted, self.task == nil,
                  self.retryGeneration == retry else { return }
            self.openSocket()
        }
    }
}

extension SignalingClient: URLSessionWebSocketDelegate {
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didOpenWithProtocol protocol: String?) {
        queue.async { [weak self] in
            guard let self, self.task === webSocketTask else { return }
            self.reconnectAttempt = 0
            self.flushPendingKnocks(on: webSocketTask)
            self.startHeartbeat()
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.delegate?.signalingDidConnect(self)
            }
        }
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
                    reason: Data?) {
        queue.async { [weak self] in
            guard let self else { return }
            self.handleDisconnect(task: webSocketTask, generation: self.socketGeneration)
        }
    }
}
