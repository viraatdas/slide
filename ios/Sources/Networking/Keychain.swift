import Foundation
import Security

/// Thin Keychain wrapper for secure token storage.
struct Keychain {
    let service: String

    init(service: String = "app.slide.tokens") {
        self.service = service
    }

    func set(_ value: String, for key: String) {
        guard let data = value.data(using: .utf8) else { return }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]
        SecItemDelete(query as CFDictionary)
        var attributes = query
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(attributes as CFDictionary, nil)
    }

    func get(_ key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess,
              let data = item as? Data,
              let str = String(data: data, encoding: .utf8) else { return nil }
        return str
    }

    func remove(_ key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]
        SecItemDelete(query as CFDictionary)
    }
}

/// Stores access + refresh tokens in the Keychain.
final class TokenStore: @unchecked Sendable {
    static let shared = TokenStore()

    private let keychain = Keychain()
    private let accessKey = "accessToken"
    private let refreshKey = "refreshToken"
    private let lock = NSLock()

    var accessToken: String? {
        lock.lock(); defer { lock.unlock() }
        return keychain.get(accessKey)
    }

    var refreshToken: String? {
        lock.lock(); defer { lock.unlock() }
        return keychain.get(refreshKey)
    }

    var isAuthenticated: Bool { accessToken != nil }

    func save(access: String, refresh: String) {
        lock.lock(); defer { lock.unlock() }
        keychain.set(access, for: accessKey)
        keychain.set(refresh, for: refreshKey)
    }

    func updateAccess(_ access: String, refresh: String) {
        lock.lock(); defer { lock.unlock() }
        keychain.set(access, for: accessKey)
        keychain.set(refresh, for: refreshKey)
    }

    func clear() {
        lock.lock(); defer { lock.unlock() }
        keychain.remove(accessKey)
        keychain.remove(refreshKey)
    }
}

/// Stable for this installed app, independent of the signed-in account. The
/// backend uses it to elect exactly one installation when two devices answer
/// the same account's call at once, while allowing safe same-device retries.
enum InstallationIdentity {
    private static let callAcceptKeyName = "slide.callAcceptKey"

    static let callAcceptKey: String = {
        if let existing = UserDefaults.standard.string(forKey: callAcceptKeyName),
           existing.count >= 8, existing.count <= 128,
           existing.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "_" || $0 == "-" }) {
            return existing
        }
        let generated = UUID().uuidString.lowercased()
        UserDefaults.standard.set(generated, forKey: callAcceptKeyName)
        return generated
    }()
}
