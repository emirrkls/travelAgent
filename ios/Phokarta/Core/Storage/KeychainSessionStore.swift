import Foundation
import Security

/// Keychain session store.
///
/// Accessibility: `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`
/// - Available after the first device unlock in a boot cycle (needed if a later
///   milestone adds background refresh).
/// - `ThisDeviceOnly` prevents iCloud Keychain sync of access/refresh tokens.
///
/// Stores only: access token, refresh token, token metadata, and minimal user identity.
/// Never stores passwords.
actor KeychainSessionStore: SessionStore {
    static let service = "com.emirrkls.phokarta.session"
    static let account = "current"

    func load() async -> PersistedSession? {
        var query: [String: Any] = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else {
            return nil
        }
        return try? APIJSON.decoder.decode(PersistedSession.self, from: data)
    }

    func save(_ session: PersistedSession) async throws {
        let data = try APIJSON.encoder.encode(session)
        try persist(data)
    }

    func updateTokens(_ tokens: TokenPair) async throws {
        guard var session = await load() else { return }
        session.tokens = tokens
        try await save(session)
    }

    func clear() async throws {
        let status = SecItemDelete(baseQuery() as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            AppLog.session.error("keychain delete failed status=\(status, privacy: .public)")
            throw AppError.unknown(status: Int(status), code: "KEYCHAIN")
        }
    }

    private func persist(_ data: Data) throws {
        let deleteStatus = SecItemDelete(baseQuery() as CFDictionary)
        guard deleteStatus == errSecSuccess || deleteStatus == errSecItemNotFound else {
            AppLog.session.error("keychain replace-delete failed status=\(deleteStatus, privacy: .public)")
            throw AppError.unknown(status: Int(deleteStatus), code: "KEYCHAIN")
        }

        var add = baseQuery()
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let addStatus = SecItemAdd(add as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            AppLog.session.error("keychain add failed status=\(addStatus, privacy: .public)")
            throw AppError.unknown(status: Int(addStatus), code: "KEYCHAIN")
        }
    }

    private func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: Self.account,
            kSecAttrSynchronizable as String: kCFBooleanFalse as Any,
        ]
    }
}
