import Foundation

enum APIError: LocalizedError {
    case invalidURL
    case transport(Error)
    case decoding(Error)
    case unauthorized
    case server(code: String, message: String, retryAfter: Int?, status: Int)
    case http(status: Int)
    case notAuthenticated

    var errorDescription: String? {
        switch self {
        case .invalidURL: return "Invalid URL."
        case .transport(let e): return e.localizedDescription
        case .decoding: return "Couldn't read the server response."
        case .unauthorized: return "Your session expired. Please sign in again."
        case .server(_, let message, _, _): return message
        case .http(let status): return "Request failed (\(status))."
        case .notAuthenticated: return "Not signed in."
        }
    }

    var isAnsweredOnAnotherInstallation: Bool {
        guard case .server(_, let message, _, let status) = self, status == 409 else {
            return false
        }
        return message.localizedCaseInsensitiveContains("answered on another installation")
    }

    /// Failures where an accept may have committed but its response was lost.
    /// Retrying with the stable installation key is safe and authoritative.
    var shouldRetryCallAccept: Bool {
        switch self {
        case .transport, .decoding:
            return true
        case .http(let status):
            return status < 0 || status >= 500
        case .server(_, _, _, let status):
            return status >= 500
        default:
            return false
        }
    }
}
