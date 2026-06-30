import Foundation

/// Async/await URLSession client implementing every endpoint in AGENTS.md.
/// Performs a silent refresh on 401 via POST /auth/refresh.
actor APIClient {
    static let shared = APIClient()

    private let baseURL: URL
    private let session: URLSession
    private let tokens: TokenStore
    private let decoder: JSONDecoder
    private let encoder: JSONEncoder

    /// Coalesces concurrent refresh attempts.
    private var refreshTask: Task<Void, Error>?

    /// Called when refresh ultimately fails so the app can log out.
    var onAuthFailure: (@Sendable () -> Void)?

    init(baseURL: URL = Config.apiBaseURL, tokens: TokenStore = .shared) {
        self.baseURL = baseURL
        self.tokens = tokens

        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 20
        config.waitsForConnectivity = false
        self.session = URLSession(configuration: config)

        let dec = JSONDecoder()
        dec.dateDecodingStrategy = .iso8601WithFractional
        self.decoder = dec

        let enc = JSONEncoder()
        enc.dateEncodingStrategy = .iso8601
        self.encoder = enc
    }

    func setAuthFailureHandler(_ handler: @escaping @Sendable () -> Void) {
        self.onAuthFailure = handler
    }

    // MARK: - Auth (no bearer)

    func requestOtp(phone: String) async throws -> RequestOtpResponse {
        // 202 may carry a devCode body, or be empty.
        let data = try await send(
            path: "/auth/request-otp", method: "POST",
            body: ["phone": phone], authenticated: false, allowEmpty: true
        )
        if data.isEmpty { return RequestOtpResponse(devCode: nil) }
        return (try? decoder.decode(RequestOtpResponse.self, from: data)) ?? RequestOtpResponse(devCode: nil)
    }

    func verifyOtp(phone: String, code: String) async throws -> VerifyOtpResponse {
        let data = try await send(
            path: "/auth/verify-otp", method: "POST",
            body: ["phone": phone, "code": code], authenticated: false
        )
        let resp = try decode(VerifyOtpResponse.self, from: data)
        tokens.save(access: resp.accessToken, refresh: resp.refreshToken)
        return resp
    }

    /// Exchange a verified Firebase ID token for Slide session tokens.
    func firebaseAuth(idToken: String) async throws -> VerifyOtpResponse {
        let data = try await send(
            path: "/auth/firebase", method: "POST",
            body: ["idToken": idToken], authenticated: false
        )
        let resp = try decode(VerifyOtpResponse.self, from: data)
        tokens.save(access: resp.accessToken, refresh: resp.refreshToken)
        return resp
    }

    func logout() async {
        guard let refresh = tokens.refreshToken else { tokens.clear(); return }
        _ = try? await send(
            path: "/auth/logout", method: "POST",
            body: ["refreshToken": refresh], authenticated: false, allowEmpty: true
        )
        tokens.clear()
    }

    // MARK: - User & onboarding

    func me() async throws -> User {
        let data = try await send(path: "/me", method: "GET")
        return try decode(User.self, from: data)
    }

    func updateMe(displayName: String? = nil, avatarUrl: String? = nil) async throws -> User {
        var body: [String: String] = [:]
        if let displayName { body["displayName"] = displayName }
        if let avatarUrl { body["avatarUrl"] = avatarUrl }
        let data = try await send(path: "/me", method: "PATCH", body: body)
        return try decode(User.self, from: data)
    }

    func uploadAvatar(_ imageData: Data, fileName: String = "avatar.jpg",
                      mime: String = "image/jpeg") async throws -> String {
        let boundary = "Boundary-\(UUID().uuidString)"
        var body = Data()
        body.append("--\(boundary)\r\n")
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"\(fileName)\"\r\n")
        body.append("Content-Type: \(mime)\r\n\r\n")
        body.append(imageData)
        body.append("\r\n--\(boundary)--\r\n")

        let data = try await sendRaw(
            path: "/me/avatar", method: "POST", body: body,
            contentType: "multipart/form-data; boundary=\(boundary)"
        )
        struct Resp: Codable { let avatarUrl: String }
        return try decode(Resp.self, from: data).avatarUrl
    }

    func registerDevice(pushToken: String, platform: String = "ios") async throws -> Device {
        let data = try await send(path: "/devices", method: "POST", body: [
            "pushToken": pushToken,
            "platform": platform,
            "appVersion": Config.appVersion
        ])
        return try decode(Device.self, from: data)
    }

    /// Register the standard APNs token so the backend can send alert pushes
    /// (knock taps while backgrounded, missed knocks). Distinct from the VoIP
    /// token, which only rings calls.
    func registerStandardPushToken(_ token: String) async throws {
        _ = try await send(path: "/push/register", method: "POST", body: [
            "pushToken": token,
            "kind": "apns",
            "platform": "ios",
            "appVersion": Config.appVersion
        ])
    }

    /// Register the PushKit VoIP token so the backend can wake the app with a
    /// VoIP push for incoming calls/knocks. Requires a valid Bearer access
    /// token (call after sign-in once the token is available).
    func registerPushToken(_ token: String) async throws {
        _ = try await send(path: "/push/register", method: "POST", body: [
            "pushToken": token,
            "kind": "apns_voip",
            "platform": "ios",
            "appVersion": Config.appVersion
        ])
    }

    /// Remove a standard or VoIP token from the current account. This matters
    /// on logout and account switching: APNs tokens identify the app install,
    /// not the signed-in user, so leaving one attached to an old account can
    /// ring the wrong person on this phone.
    func unregisterPushToken(_ token: String) async throws {
        _ = try await send(path: "/push/register", method: "DELETE", body: [
            "pushToken": token
        ], allowEmpty: true)
    }

    // MARK: - Contacts

    func syncContacts(phones: [String], names: [String] = []) async throws -> [ContactSyncResult] {
        var body: [String: Any] = ["phones": phones]
        if !names.isEmpty { body["names"] = names }
        let data = try await send(path: "/contacts/sync", method: "POST",
                                  jsonObject: body,
                                  authenticated: true, allowEmpty: false)
        return try decode([ContactSyncResult].self, from: data)
    }

    func contacts() async throws -> [Contact] {
        let data = try await send(path: "/contacts", method: "GET")
        return try decode([Contact].self, from: data)
    }

    // MARK: - Calls control plane

    func createCall(type: CallType, participantUserIds: [String],
                    videoEnabled: Bool = true,
                    ringStyle: String = "call") async throws -> CallSession {
        let body: [String: Any] = [
            "type": type.rawValue,
            "participantUserIds": participantUserIds,
            "videoEnabled": videoEnabled,
            "ringStyle": ringStyle
        ]
        let data = try await send(path: "/calls", method: "POST", jsonObject: body,
                                  authenticated: true, allowEmpty: false)
        return try decode(CallSession.self, from: data)
    }

    func acceptCall(id: String) async throws -> CallSession {
        let data = try await perform(
            path: "/calls/\(id)/accept", method: "POST", bodyData: nil,
            authenticated: true, allowEmpty: false,
            additionalHeaders: ["X-Call-Accept-Key": InstallationIdentity.callAcceptKey]
        )
        return try decode(CallSession.self, from: data)
    }

    func declineCall(id: String) async throws {
        _ = try await send(path: "/calls/\(id)/decline", method: "POST", allowEmpty: true)
    }

    func leaveCall(id: String) async throws {
        _ = try await perform(
            path: "/calls/\(id)/leave", method: "POST", bodyData: nil,
            authenticated: true, allowEmpty: true,
            additionalHeaders: ["X-Call-Accept-Key": InstallationIdentity.callAcceptKey]
        )
    }

    /// Lifecycle cleanup must survive the transient outage that often caused
    /// the call to fail in the first place. `/leave` is idempotent, so retry a
    /// few times and still allow later owners (CallKit/view teardown) to retry.
    func leaveCallBestEffort(id: String) async {
        for attempt in 0..<3 {
            do {
                try await leaveCall(id: id)
                return
            } catch let error as APIError {
                guard attempt < 2, error.shouldRetryCallAccept else { return }
                let delay = UInt64(500_000_000 * (attempt + 1))
                try? await Task.sleep(nanoseconds: delay)
            } catch {
                return
            }
        }
    }

    func calls(cursor: String? = nil) async throws -> CallListResponse {
        var path = "/calls"
        if let cursor { path += "?cursor=\(cursor)" }
        let data = try await send(path: path, method: "GET")
        return try decode(CallListResponse.self, from: data)
    }

    // MARK: - Token refresh

    func refreshTokens() async throws {
        guard let refresh = tokens.refreshToken else {
            throw APIError.notAuthenticated
        }
        let req = try makeRequest(path: "/auth/refresh", method: "POST",
                                  body: ["refreshToken": refresh], authenticated: false)
        let (data, response) = try await transport(req)
        guard let http = response as? HTTPURLResponse else { throw APIError.http(status: -1) }
        guard (200..<300).contains(http.statusCode) else {
            throw APIError.unauthorized
        }
        let resp = try decode(RefreshResponse.self, from: data)
        tokens.updateAccess(resp.accessToken, refresh: resp.refreshToken)
    }

    // MARK: - Request plumbing

    private func send(path: String, method: String,
                      body: [String: String]? = nil,
                      authenticated: Bool = true,
                      allowEmpty: Bool = false) async throws -> Data {
        let bodyData = try body.map { try JSONSerialization.data(withJSONObject: $0) }
        return try await perform(path: path, method: method, bodyData: bodyData,
                                 authenticated: authenticated, allowEmpty: allowEmpty)
    }

    private func send(path: String, method: String,
                      jsonObject: [String: Any],
                      authenticated: Bool = true,
                      allowEmpty: Bool = false) async throws -> Data {
        let bodyData = try JSONSerialization.data(withJSONObject: jsonObject)
        return try await perform(path: path, method: method, bodyData: bodyData,
                                 authenticated: authenticated, allowEmpty: allowEmpty)
    }

    private func sendRaw(path: String, method: String, body: Data,
                         contentType: String) async throws -> Data {
        return try await perform(path: path, method: method, bodyData: body,
                                 authenticated: true, allowEmpty: false,
                                 contentType: contentType)
    }

    private func perform(path: String, method: String, bodyData: Data?,
                         authenticated: Bool, allowEmpty: Bool,
                         contentType: String = "application/json",
                         isRetry: Bool = false,
                         additionalHeaders: [String: String] = [:]) async throws -> Data {
        var req = try makeRawRequest(path: path, method: method, bodyData: bodyData,
                                     authenticated: authenticated, contentType: contentType,
                                     additionalHeaders: additionalHeaders)
        let (data, response) = try await transport(req)
        guard let http = response as? HTTPURLResponse else { throw APIError.http(status: -1) }

        if http.statusCode == 401 && authenticated && !isRetry {
            // Silent refresh, then retry once.
            try await performRefresh()
            req = try makeRawRequest(path: path, method: method, bodyData: bodyData,
                                     authenticated: authenticated, contentType: contentType,
                                     additionalHeaders: additionalHeaders)
            return try await perform(path: path, method: method, bodyData: bodyData,
                                     authenticated: authenticated, allowEmpty: allowEmpty,
                                     contentType: contentType, isRetry: true,
                                     additionalHeaders: additionalHeaders)
        }

        guard (200..<300).contains(http.statusCode) else {
            if http.statusCode == 401 { throw APIError.unauthorized }
            if let env = try? decoder.decode(APIErrorEnvelope.self, from: data) {
                throw APIError.server(code: env.error.code,
                                      message: env.error.message,
                                      retryAfter: env.error.retryAfter,
                                      status: http.statusCode)
            }
            throw APIError.http(status: http.statusCode)
        }

        if data.isEmpty && !allowEmpty {
            // 204 etc. with body expected but absent.
        }
        return data
    }

    /// Coalesced refresh so concurrent 401s only trigger one network call.
    /// Stays on the actor throughout so `tokens`/`onAuthFailure` access is safe.
    private func performRefresh() async throws {
        if let task = refreshTask {
            try await task.value
            return
        }
        // The work runs as an actor-isolated async method; the Task only exists
        // so concurrent callers can await the same in-flight refresh.
        let task = Task { try await self.runRefresh() }
        refreshTask = task
        defer { refreshTask = nil }
        try await task.value
    }

    private func runRefresh() async throws {
        do {
            try await refreshTokens()
        } catch {
            tokens.clear()
            onAuthFailure?()
            throw error
        }
    }

    private func transport(_ req: URLRequest) async throws -> (Data, URLResponse) {
        do {
            return try await session.data(for: req)
        } catch {
            throw APIError.transport(error)
        }
    }

    private func makeRequest(path: String, method: String,
                             body: [String: String]?, authenticated: Bool) throws -> URLRequest {
        let bodyData = try body.map { try JSONSerialization.data(withJSONObject: $0) }
        return try makeRawRequest(path: path, method: method, bodyData: bodyData,
                                  authenticated: authenticated, contentType: "application/json",
                                  additionalHeaders: [:])
    }

    private func makeRawRequest(path: String, method: String, bodyData: Data?,
                                authenticated: Bool, contentType: String,
                                additionalHeaders: [String: String] = [:]) throws -> URLRequest {
        guard let url = URL(string: baseURL.absoluteString + path) else {
            throw APIError.invalidURL
        }
        var req = URLRequest(url: url)
        req.httpMethod = method
        if let bodyData {
            req.httpBody = bodyData
            req.setValue(contentType, forHTTPHeaderField: "Content-Type")
        }
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        for (header, value) in additionalHeaders {
            req.setValue(value, forHTTPHeaderField: header)
        }
        if authenticated {
            guard let token = tokens.accessToken else { throw APIError.notAuthenticated }
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        return req
    }

    private func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            throw APIError.decoding(error)
        }
    }
}

// MARK: - Helpers

private extension Data {
    mutating func append(_ string: String) {
        if let d = string.data(using: .utf8) { append(d) }
    }
}

extension JSONDecoder.DateDecodingStrategy {
    /// ISO8601 with optional fractional seconds (backend uses RFC3339).
    static var iso8601WithFractional: JSONDecoder.DateDecodingStrategy {
        .custom { decoder in
            let container = try decoder.singleValueContainer()
            let string = try container.decode(String.self)
            let withFrac = ISO8601DateFormatter()
            withFrac.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            if let d = withFrac.date(from: string) { return d }
            let plain = ISO8601DateFormatter()
            plain.formatOptions = [.withInternetDateTime]
            if let d = plain.date(from: string) { return d }
            throw DecodingError.dataCorruptedError(
                in: container, debugDescription: "Bad date: \(string)")
        }
    }
}
