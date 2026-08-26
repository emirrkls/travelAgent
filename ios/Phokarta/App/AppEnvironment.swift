import Foundation

/// Wires the refresh actor to MainActor session state.
/// `@unchecked Sendable` because the controller reference is assigned once
/// during app boot on the main actor before concurrent refresh can run.
final class TerminalAuthRelay: @unchecked Sendable {
    @MainActor var controller: AuthSessionController?

    func notify() async {
        await MainActor.run {
            controller?.handleTerminalAuthLoss()
        }
    }
}

struct AppEnvironment {
    let config: AppConfig
    let auth: AuthRepository
    let session: AuthSessionController

    @MainActor
    static func live() throws -> AppEnvironment {
        let config = try AppConfig.fromBundle()
        let store = KeychainSessionStore()
        let transport = URLSessionTransport.default
        let relay = TerminalAuthRelay()
        let refresh = TokenRefreshCoordinator(
            store: store,
            config: config,
            transport: transport,
            terminalAuthHandler: { await relay.notify() }
        )
        let client = APIClient(config: config, transport: transport, authRetry: refresh)
        let auth = AuthRepository(client: client, store: store, refresh: refresh, config: config)
        let session = AuthSessionController(auth: auth)
        relay.controller = session
        return AppEnvironment(config: config, auth: auth, session: session)
    }

    @MainActor
    static func testing(
        config: AppConfig,
        store: any SessionStore,
        transport: any HTTPTransport,
        initialState: AuthState = .restoring
    ) -> AppEnvironment {
        let relay = TerminalAuthRelay()
        let refresh = TokenRefreshCoordinator(
            store: store,
            config: config,
            transport: transport,
            terminalAuthHandler: { await relay.notify() }
        )
        let client = APIClient(config: config, transport: transport, authRetry: refresh)
        let auth = AuthRepository(client: client, store: store, refresh: refresh, config: config)
        let session = AuthSessionController(auth: auth, initialState: initialState)
        relay.controller = session
        return AppEnvironment(config: config, auth: auth, session: session)
    }
}
