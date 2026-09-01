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
    let places: any PlaceServing
    let saved: SavedPlaceStore
    let collections: CollectionStore
    let visits: VisitStore

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
        let saved = SavedPlaceStore(service: SavedPlaceService(client: client))
        let collections = CollectionStore(service: CollectionService(client: client))
        let visits = VisitStore(service: VisitService(client: client))
        let session = AuthSessionController(auth: auth) {
            saved.clear()
            collections.clear()
            visits.clear()
        }
        relay.controller = session
        return AppEnvironment(
            config: config,
            auth: auth,
            session: session,
            places: PlaceService(client: client),
            saved: saved,
            collections: collections,
            visits: visits
        )
    }

    @MainActor
    static func testing(
        config: AppConfig,
        store: any SessionStore,
        transport: any HTTPTransport,
        initialState: AuthState = .restoring,
        places: (any PlaceServing)? = nil
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
        let saved = SavedPlaceStore(service: SavedPlaceService(client: client))
        let collections = CollectionStore(service: CollectionService(client: client))
        let visits = VisitStore(service: VisitService(client: client))
        let session = AuthSessionController(auth: auth, initialState: initialState) {
            saved.clear()
            collections.clear()
            visits.clear()
        }
        relay.controller = session
        return AppEnvironment(
            config: config,
            auth: auth,
            session: session,
            places: places ?? PlaceService(client: client),
            saved: saved,
            collections: collections,
            visits: visits
        )
    }
}
