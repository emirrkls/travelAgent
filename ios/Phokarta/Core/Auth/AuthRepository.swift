import Foundation

final class AuthRepository: Sendable {
    private let client: APIClient
    private let store: any SessionStore
    private let refresh: TokenRefreshCoordinator
    private let config: AppConfig

    init(
        client: APIClient,
        store: any SessionStore,
        refresh: TokenRefreshCoordinator,
        config: AppConfig
    ) {
        self.client = client
        self.store = store
        self.refresh = refresh
        self.config = config
    }

    func register(
        email: String,
        username: String,
        displayName: String,
        password: String
    ) async throws -> CurrentUser {
        try Task.checkCancellation()
        let session = try await client.send(
            RegisterEndpoint(
                body: RegisterRequestDTO(
                    email: email.trimmingCharacters(in: .whitespacesAndNewlines),
                    username: username.trimmingCharacters(in: .whitespacesAndNewlines),
                    displayName: displayName.trimmingCharacters(in: .whitespacesAndNewlines),
                    password: password
                )
            )
        )
        let persisted = session.toPersistedSession()
        try await store.save(persisted)
        return persisted.user
    }

    func login(identifier: String, password: String) async throws -> CurrentUser {
        try Task.checkCancellation()
        let session = try await client.send(
            LoginEndpoint(
                body: LoginRequestDTO(
                    identifier: identifier.trimmingCharacters(in: .whitespacesAndNewlines),
                    password: password
                )
            )
        )
        let persisted = session.toPersistedSession()
        try await store.save(persisted)
        return persisted.user
    }

    func restoreSession() async throws -> AuthState {
        try Task.checkCancellation()
        guard let stored = await store.load() else {
            return .signedOut
        }
        do {
            _ = try await refresh.refreshSession()
            let profile = try await client.send(MeEndpoint())
            let user = profile.toCurrentUser()
            if var session = await store.load() {
                session.user = user
                try await store.save(session)
            }
            return .signedIn(user)
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as AppError {
            if error.isTransient {
                AppLog.auth.info("restore preserved session after transient error")
                return .signedIn(stored.user)
            }
            if error.isTerminalAuth {
                try? await store.clear()
                return .signedOut
            }
            AppLog.auth.info("restore preserved session after non-terminal error")
            return .signedIn(stored.user)
        }
    }

    /// Local logout always succeeds. Remote revocation is best-effort.
    /// If the network call fails, the refresh session may remain valid on the
    /// server until expiry/reuse; this client does not claim remote revocation.
    func logout() async {
        let stored = await store.load()
        if let token = stored?.tokens.refreshToken, !config.isPlaceholderHost {
            do {
                _ = try await client.sendWithoutRetry(LogoutEndpoint(refreshToken: token))
            } catch is CancellationError {
                AppLog.auth.info("remote logout cancelled; clearing local session")
            } catch {
                AppLog.auth.info("remote logout failed; clearing local session")
            }
        }
        try? await store.clear()
    }

    func currentUser() async -> CurrentUser? {
        await store.load()?.user
    }
}
