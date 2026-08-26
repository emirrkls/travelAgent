import Foundation

/// Single-flight refresh coordinator.
///
/// Invariant: concurrent 401s share exactly one refresh request. The refresh
/// always reads the *current* Keychain refresh token at execution time so a
/// waiter cannot replay a rotated token after the in-flight refresh succeeds.
actor TokenRefreshCoordinator: AuthRetryHandling {
    private let store: any SessionStore
    private let refreshClient: APIClient
    private var inFlight: Task<TokenPair, Error>?
    private let terminalAuthHandler: (@Sendable () async -> Void)?

    init(
        store: any SessionStore,
        config: AppConfig,
        transport: any HTTPTransport,
        terminalAuthHandler: (@Sendable () async -> Void)? = nil
    ) {
        self.store = store
        self.refreshClient = APIClient(config: config, transport: transport, authRetry: nil)
        self.terminalAuthHandler = terminalAuthHandler
    }

    func accessToken() async -> String? {
        await store.load()?.tokens.accessToken
    }

    func refreshAfterUnauthorized(failedAccessToken: String?) async throws -> String {
        if let current = await store.load()?.tokens.accessToken,
           let failed = failedAccessToken,
           !current.isEmpty,
           current != failed {
            return current
        }
        let pair = try await refreshSession()
        return pair.accessToken
    }

    func refreshSession() async throws -> TokenPair {
        if let inFlight {
            return try await inFlight.value
        }
        let store = self.store
        let client = self.refreshClient
        let handler = self.terminalAuthHandler
        let task = Task {
            try await TokenRefreshCoordinator.executeRefresh(
                store: store,
                client: client,
                terminalAuthHandler: handler
            )
        }
        inFlight = task
        do {
            let pair = try await task.value
            inFlight = nil
            return pair
        } catch {
            inFlight = nil
            throw error
        }
    }

    private static func executeRefresh(
        store: any SessionStore,
        client: APIClient,
        terminalAuthHandler: (@Sendable () async -> Void)?
    ) async throws -> TokenPair {
        guard let stored = await store.load() else {
            throw AppError.unauthorized
        }
        do {
            let dto = try await client.sendWithoutRetry(
                RefreshEndpoint(refreshToken: stored.tokens.refreshToken)
            )
            let pair = dto.toTokenPair()
            try await store.updateTokens(pair)
            return pair
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as AppError {
            if error.isTransient {
                throw error
            }
            try? await store.clear()
            if let terminalAuthHandler {
                await terminalAuthHandler()
            }
            throw AppError.unauthorized
        }
    }
}
