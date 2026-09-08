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

final class AuthSessionOwnerProvider: SessionOwnerProvider, @unchecked Sendable {
    @MainActor weak var controller: AuthSessionController?

    init(controller: AuthSessionController? = nil) {
        self.controller = controller
    }

    func currentUserId() async -> UUID? {
        await MainActor.run {
            if case .signedIn(let user) = controller?.state {
                return user.id
            }
            return nil
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
    let database: PersistentDatabase
    let draftRepository: any VisitDraftRepository
    let mutationRepository: any OfflineMutationRepository
    let mediaStore: any DurableMediaStoring
    let mediaLock: MediaFileMutationLock
    let mediaReconciler: MediaFileReconciler
    let syncEngine: MutationSyncEngine
    let purger: any LocalAccountPurger
    let networkMonitor: any NetworkMonitoring
    let social: any SocialServing
    let activity: any ActivityServing
    let socialState: SocialStateStore
    let blockService: any BlockServing
    let reportService: any ReportServing
    let policyService: any PolicyServing
    let accountDeletionService: any AccountDeletionServing
    let policyStore: PolicyStatusStore

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
        let visitService = VisitService(client: client)
        let visitMediaService = VisitMediaService(client: client)
        let visits = VisitStore(service: visitService, mediaService: visitMediaService)

        // Initialize SQLite persistence in Application Support
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        let phokartaDir = appSupport.appendingPathComponent("Phokarta", isDirectory: true)
        try? FileManager.default.createDirectory(at: phokartaDir, withIntermediateDirectories: true)
        let dbPath = phokartaDir.appendingPathComponent("phokarta.sqlite3").path
        let database = try PersistentDatabase(path: dbPath)

        let draftRepository = SQLiteVisitDraftRepository(database: database)
        let mutationRepository = SQLiteOfflineMutationRepository(database: database)
        let mediaStore = DurableMediaStore()
        let mediaLock = MediaFileMutationLock()
        let mediaReconciler = MediaFileReconciler(
            mediaStore: mediaStore,
            draftRepository: draftRepository,
            mutationRepository: mutationRepository,
            lock: mediaLock
        )
        let sessionOwner = AuthSessionOwnerProvider()
        let syncEngine = MutationSyncEngine(
            mutationRepository: mutationRepository,
            draftRepository: draftRepository,
            mediaStore: mediaStore,
            mediaService: visitMediaService,
            visitService: visitService,
            visitStore: visits,
            sessionProvider: sessionOwner
        )
        let purger = SQLiteLocalAccountPurger(database: database, mediaStore: mediaStore)
        let networkMonitor = SystemNetworkMonitor()

        let socialService = SocialService(client: client)
        let activityService = ActivityService(client: client)
        let socialState = SocialStateStore(service: socialService)

        let blockSvc = BlockService(client: client)
        let reportSvc = ReportService(client: client)
        let policySvc = PolicyService(client: client)
        let accountDelSvc = AccountDeletionService(client: client)
        let policyStore = PolicyStatusStore(service: policySvc)

        let session = AuthSessionController(auth: auth) {
            saved.clear()
            collections.clear()
            visits.clear()
            socialState.clear()
            policyStore.clear()
        }
        relay.controller = session
        sessionOwner.controller = session

        return AppEnvironment(
            config: config,
            auth: auth,
            session: session,
            places: PlaceService(client: client),
            saved: saved,
            collections: collections,
            visits: visits,
            database: database,
            draftRepository: draftRepository,
            mutationRepository: mutationRepository,
            mediaStore: mediaStore,
            mediaLock: mediaLock,
            mediaReconciler: mediaReconciler,
            syncEngine: syncEngine,
            purger: purger,
            networkMonitor: networkMonitor,
            social: socialService,
            activity: activityService,
            socialState: socialState,
            blockService: blockSvc,
            reportService: reportSvc,
            policyService: policySvc,
            accountDeletionService: accountDelSvc,
            policyStore: policyStore
        )
    }

    @MainActor
    static func testing(
        config: AppConfig,
        store: any SessionStore,
        transport: any HTTPTransport,
        initialState: AuthState = .restoring,
        places: (any PlaceServing)? = nil,
        customDatabase: PersistentDatabase? = nil,
        customMediaStore: (any DurableMediaStoring)? = nil,
        customNetworkMonitor: (any NetworkMonitoring)? = nil,
        customSocial: (any SocialServing)? = nil,
        customActivity: (any ActivityServing)? = nil,
        customSocialState: SocialStateStore? = nil
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
        let visitService = VisitService(client: client)
        let visitMediaService = VisitMediaService(client: client)
        let visits = VisitStore(service: visitService, mediaService: visitMediaService)
        let socialService = customSocial ?? SocialService(client: client)
        let activityService = customActivity ?? ActivityService(client: client)
        let socialState = customSocialState ?? SocialStateStore(service: socialService)

        let database = customDatabase ?? (try! PersistentDatabase())
        let draftRepository = SQLiteVisitDraftRepository(database: database)
        let mutationRepository = SQLiteOfflineMutationRepository(database: database)
        let mediaStore = customMediaStore ?? DurableMediaStore(customRootDirectory: URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(UUID().uuidString))
        let mediaLock = MediaFileMutationLock()
        let mediaReconciler = MediaFileReconciler(
            mediaStore: mediaStore,
            draftRepository: draftRepository,
            mutationRepository: mutationRepository,
            lock: mediaLock
        )
        let sessionOwner = AuthSessionOwnerProvider()
        let syncEngine = MutationSyncEngine(
            mutationRepository: mutationRepository,
            draftRepository: draftRepository,
            mediaStore: mediaStore,
            mediaService: visitMediaService,
            visitService: visitService,
            visitStore: visits,
            sessionProvider: sessionOwner
        )
        let purger = SQLiteLocalAccountPurger(database: database, mediaStore: mediaStore)
        let networkMonitor = customNetworkMonitor ?? TestNetworkMonitor()

        let blockSvc = BlockService(client: client)
        let reportSvc = ReportService(client: client)
        let policySvc = PolicyService(client: client)
        let accountDelSvc = AccountDeletionService(client: client)
        let policyStore = PolicyStatusStore(service: policySvc)

        let session = AuthSessionController(auth: auth, initialState: initialState) {
            saved.clear()
            collections.clear()
            visits.clear()
            socialState.clear()
            policyStore.clear()
        }
        relay.controller = session
        sessionOwner.controller = session

        return AppEnvironment(
            config: config,
            auth: auth,
            session: session,
            places: places ?? PlaceService(client: client),
            saved: saved,
            collections: collections,
            visits: visits,
            database: database,
            draftRepository: draftRepository,
            mutationRepository: mutationRepository,
            mediaStore: mediaStore,
            mediaLock: mediaLock,
            mediaReconciler: mediaReconciler,
            syncEngine: syncEngine,
            purger: purger,
            networkMonitor: networkMonitor,
            social: socialService,
            activity: activityService,
            socialState: socialState,
            blockService: blockSvc,
            reportService: reportSvc,
            policyService: policySvc,
            accountDeletionService: accountDelSvc,
            policyStore: policyStore
        )
    }
}
