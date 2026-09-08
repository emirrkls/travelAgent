import SwiftUI
import Observation

struct MainTabView: View {
    let environment: AppEnvironment
    let user: CurrentUser
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        TabView {
            ExploreScreen(
                places: environment.places,
                saved: environment.saved,
                collections: environment.collections,
                visits: environment.visits,
                draftRepository: environment.draftRepository,
                mutationRepository: environment.mutationRepository,
                mediaStore: environment.mediaStore,
                syncEngine: environment.syncEngine,
                environment: environment,
                currentUserId: user.id
            )
            .tabItem {
                Label {
                    Text("tab.explore")
                } icon: {
                    Image(systemName: "safari")
                }
            }

            ActivityTab(
                environment: environment,
                currentUserId: user.id
            )
            .tabItem {
                Label {
                    Text("tab.activity")
                } icon: {
                    Image(systemName: "bell")
                }
            }

            SavedScreen(
                store: environment.saved,
                places: environment.places,
                collections: environment.collections,
                visits: environment.visits,
                draftRepository: environment.draftRepository,
                mutationRepository: environment.mutationRepository,
                mediaStore: environment.mediaStore,
                syncEngine: environment.syncEngine,
                environment: environment,
                currentUserId: user.id
            )
            .tabItem {
                Label("saved.title", systemImage: "bookmark")
            }

            CollectionsScreen(
                store: environment.collections,
                saved: environment.saved,
                places: environment.places,
                visits: environment.visits,
                draftRepository: environment.draftRepository,
                mutationRepository: environment.mutationRepository,
                mediaStore: environment.mediaStore,
                syncEngine: environment.syncEngine
            )
            .tabItem {
                Label("collections.title", systemImage: "square.stack")
            }

            ProfileTab(
                environment: environment,
                user: user,
                onLogout: {
                    Task { await environment.session.logout() }
                }
            )
            .tabItem {
                Label {
                    Text("tab.profile")
                } icon: {
                    Image(systemName: "person.crop.circle")
                }
            }
        }
        .tint(PhokartaColor.accent(for: colorScheme))
        .task(id: user.id) {
            environment.saved.activate(accountID: user.id)
            environment.collections.activate(accountID: user.id)
            environment.visits.activate(accountID: user.id)
            environment.socialState.activate(accountID: user.id)
            _ = await environment.syncEngine.drain()
            try? await environment.saved.refresh()
            try? await environment.collections.refreshList()
            try? await environment.visits.refresh()
            _ = try? await environment.socialState.refreshOwnerProfile()
        }
        .task {
            for await isOnline in environment.networkMonitor.observePathUpdates() {
                if isOnline {
                    _ = await environment.syncEngine.drain()
                }
            }
        }
    }
}

enum SavedPhase: Equatable, Sendable {
    case idle, loading, content, empty
    case error(AppError)
}

@MainActor
@Observable
final class SavedController {
    private(set) var phase: SavedPhase = .idle
    private let store: SavedPlaceStore
    private var didStart = false

    init(store: SavedPlaceStore) { self.store = store }

    func startIfNeeded() {
        guard !didStart else { return }
        didStart = true
        Task { await load() }
    }

    func load() async {
        if store.savedRows.isEmpty { phase = .loading }
        do {
            try await store.refresh()
            phase = store.savedRows.isEmpty ? .empty : .content
        } catch is CancellationError {
            return
        } catch let error as AppError {
            phase = store.savedRows.isEmpty ? .error(error) : .content
        } catch {
            phase = store.savedRows.isEmpty ? .error(.server) : .content
        }
    }
}

struct SavedScreen: View {
    @State private var controller: SavedController
    @State private var path: [AppRoute] = []
    let store: SavedPlaceStore
    let places: any PlaceServing
    let collections: CollectionStore
    let visits: VisitStore
    let draftRepository: (any VisitDraftRepository)?
    let mutationRepository: (any OfflineMutationRepository)?
    let mediaStore: (any DurableMediaStoring)?
    let syncEngine: MutationSyncEngine?
    let environment: AppEnvironment?
    let currentUserId: UUID?
    @Environment(\.colorScheme) private var colorScheme

    init(
        store: SavedPlaceStore,
        places: any PlaceServing,
        collections: CollectionStore,
        visits: VisitStore,
        draftRepository: (any VisitDraftRepository)? = nil,
        mutationRepository: (any OfflineMutationRepository)? = nil,
        mediaStore: (any DurableMediaStoring)? = nil,
        syncEngine: MutationSyncEngine? = nil,
        environment: AppEnvironment? = nil,
        currentUserId: UUID? = nil
    ) {
        self.store = store
        self.places = places
        self.collections = collections
        self.visits = visits
        self.draftRepository = draftRepository
        self.mutationRepository = mutationRepository
        self.mediaStore = mediaStore
        self.syncEngine = syncEngine
        self.environment = environment
        self.currentUserId = currentUserId
        _controller = State(initialValue: SavedController(store: store))
    }

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if !store.savedRows.isEmpty {
                    List(store.savedRows) { row in
                        Button { path.append(.placeDetail(row.place.id)) } label: {
                            ExplorePlaceCard(item: ExplorePlaceItem(
                                summary: row.place,
                                friendAverageScore: row.friendAverageScore,
                                friendsVisitedCount: row.friendsVisitedCount,
                                isSaved: store.isSaved(row.place.id)
                            ))
                        }
                        .buttonStyle(.plain)
                        .listRowBackground(PhokartaColor.surface(for: colorScheme))
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                } else {
                    switch controller.phase {
                    case .idle, .loading:
                        ProgressView().accessibilityLabel(String(localized: "saved.loading"))
                    case .error(let error):
                        FeatureEmptyState(
                            title: error.localizedMessage,
                            retryTitle: String(localized: "action.try_again"),
                            retry: { Task { await controller.load() } }
                        )
                    case .content, .empty:
                        FeatureEmptyState(title: String(localized: "saved.empty"))
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(PhokartaColor.background(for: colorScheme))
            .navigationTitle(String(localized: "saved.title"))
            .refreshable { await controller.load() }
            .navigationDestination(for: AppRoute.self) { route in
                if let environment, let currentUserId {
                    AppRouteDestinationView(
                        route: route,
                        environment: environment,
                        currentUserId: currentUserId,
                        onNavigate: { path.append($0) }
                    )
                } else if case .placeDetail(let id) = route {
                    PlaceDetailScreen(
                        placeId: id,
                        places: places,
                        saved: store,
                        collections: collections,
                        visits: visits,
                        draftRepository: draftRepository,
                        mutationRepository: mutationRepository,
                        mediaStore: mediaStore,
                        syncEngine: syncEngine
                    )
                }
            }
        }
        .task { controller.startIfNeeded() }
    }
}

enum CollectionsPhase: Equatable, Sendable {
    case idle, loading, content, empty
    case error(AppError)
}

@MainActor
@Observable
final class CollectionsController {
    private(set) var phase: CollectionsPhase = .idle
    private(set) var isCreating = false
    private(set) var createError: AppError?
    private let store: CollectionStore
    private var didStart = false

    init(store: CollectionStore) { self.store = store }

    func startIfNeeded() {
        guard !didStart else { return }
        didStart = true
        Task { await load() }
    }

    func load() async {
        if store.summaries.isEmpty { phase = .loading }
        do {
            try await store.refreshList()
            phase = store.summaries.isEmpty ? .empty : .content
        } catch let error as AppError {
            phase = store.summaries.isEmpty ? .error(error) : .content
        } catch is CancellationError {
            return
        } catch {
            phase = store.summaries.isEmpty ? .error(.server) : .content
        }
    }

    func create(_ request: CreateCollectionRequestDTO) async -> CollectionDetail? {
        guard !isCreating else { return nil }
        isCreating = true
        createError = nil
        defer { isCreating = false }
        do {
            let detail = try await store.create(request)
            phase = .content
            return detail
        } catch let error as AppError {
            createError = error
            return nil
        } catch {
            createError = .server
            return nil
        }
    }
}

struct CollectionsScreen: View {
    @State private var controller: CollectionsController
    @State private var path: [UUID] = []
    @State private var showingCreate = false
    let store: CollectionStore
    let saved: SavedPlaceStore
    let places: any PlaceServing
    let visits: VisitStore
    let draftRepository: (any VisitDraftRepository)?
    let mutationRepository: (any OfflineMutationRepository)?
    let mediaStore: (any DurableMediaStoring)?
    let syncEngine: MutationSyncEngine?
    @Environment(\.colorScheme) private var colorScheme

    init(
        store: CollectionStore,
        saved: SavedPlaceStore,
        places: any PlaceServing,
        visits: VisitStore,
        draftRepository: (any VisitDraftRepository)? = nil,
        mutationRepository: (any OfflineMutationRepository)? = nil,
        mediaStore: (any DurableMediaStoring)? = nil,
        syncEngine: MutationSyncEngine? = nil
    ) {
        self.store = store
        self.saved = saved
        self.places = places
        self.visits = visits
        self.draftRepository = draftRepository
        self.mutationRepository = mutationRepository
        self.mediaStore = mediaStore
        self.syncEngine = syncEngine
        _controller = State(initialValue: CollectionsController(store: store))
    }

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if !store.summaries.isEmpty {
                    List(store.summaries) { collection in
                        Button { path.append(collection.id) } label: {
                            CollectionSummaryRow(collection: collection)
                        }
                        .buttonStyle(.plain)
                    }
                    .listStyle(.plain)
                } else {
                    switch controller.phase {
                    case .idle, .loading:
                        ProgressView().accessibilityLabel(String(localized: "collections.loading"))
                    case .error(let error):
                        FeatureEmptyState(
                            title: error.localizedMessage,
                            retryTitle: String(localized: "action.try_again"),
                            retry: { Task { await controller.load() } }
                        )
                    case .content, .empty:
                        FeatureEmptyState(title: String(localized: "collections.empty"))
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .navigationTitle(String(localized: "collections.title"))
            .toolbar {
                Button { showingCreate = true } label: {
                    Label("collections.new", systemImage: "plus")
                }
                .accessibilityLabel(String(localized: "collections.new"))
            }
            .refreshable { await controller.load() }
            .sheet(isPresented: $showingCreate) {
                CreateCollectionSheet(
                    isSubmitting: controller.isCreating,
                    error: controller.createError,
                    coverImage: nil
                ) { request in
                    if let created = await controller.create(request) {
                        showingCreate = false
                        path.append(created.id)
                    }
                }
            }
            .navigationDestination(for: UUID.self) { id in
                CollectionDetailScreen(
                    collectionID: id,
                    store: store,
                    saved: saved,
                    places: places,
                    visits: visits,
                    draftRepository: draftRepository,
                    mutationRepository: mutationRepository,
                    mediaStore: mediaStore,
                    syncEngine: syncEngine
                )
            }
        }
        .task { controller.startIfNeeded() }
    }
}

struct CollectionSummaryRow: View {
    let collection: CollectionSummary
    var body: some View {
        HStack(spacing: PhokartaSpacing.md) {
            PlaceImageView(path: collection.coverImage)
                .frame(width: 72, height: 72)
                .clipShape(RoundedRectangle(cornerRadius: PhokartaRadius.md))
            VStack(alignment: .leading, spacing: 4) {
                Text(collection.title).font(.headline).fixedSize(horizontal: false, vertical: true)
                Text("collections.place_count \(String(collection.placeCount))")
                    .font(.subheadline).foregroundStyle(.secondary)
                Text(String(localized: String.LocalizationValue(collection.visibility.localizationKey)))
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

@MainActor
@Observable
final class CollectionDetailController {
    let collectionID: UUID
    private(set) var phase: CollectionsPhase = .idle
    private(set) var mutationError: AppError?
    private let store: CollectionStore
    private var didStart = false

    init(collectionID: UUID, store: CollectionStore) {
        self.collectionID = collectionID
        self.store = store
    }

    func startIfNeeded() {
        guard !didStart else { return }
        didStart = true
        Task { await load() }
    }

    func load() async {
        if store.details[collectionID] == nil { phase = .loading }
        do {
            try await store.refreshDetail(id: collectionID)
            phase = .content
        } catch let error as AppError {
            phase = store.details[collectionID] == nil ? .error(error) : .content
        } catch is CancellationError {
            return
        } catch {
            phase = store.details[collectionID] == nil ? .error(.server) : .content
        }
    }

    func remove(placeID: UUID) async {
        mutationError = nil
        do { try await store.remove(placeID: placeID, from: collectionID) }
        catch let error as AppError { mutationError = error }
        catch is CancellationError { return }
        catch { mutationError = .server }
    }
}

struct CollectionDetailScreen: View {
    @State private var controller: CollectionDetailController
    let store: CollectionStore
    let saved: SavedPlaceStore
    let places: any PlaceServing
    let visits: VisitStore
    let draftRepository: (any VisitDraftRepository)?
    let mutationRepository: (any OfflineMutationRepository)?
    let mediaStore: (any DurableMediaStoring)?
    let syncEngine: MutationSyncEngine?

    init(
        collectionID: UUID,
        store: CollectionStore,
        saved: SavedPlaceStore,
        places: any PlaceServing,
        visits: VisitStore,
        draftRepository: (any VisitDraftRepository)? = nil,
        mutationRepository: (any OfflineMutationRepository)? = nil,
        mediaStore: (any DurableMediaStoring)? = nil,
        syncEngine: MutationSyncEngine? = nil
    ) {
        self.store = store
        self.saved = saved
        self.places = places
        self.visits = visits
        self.draftRepository = draftRepository
        self.mutationRepository = mutationRepository
        self.mediaStore = mediaStore
        self.syncEngine = syncEngine
        _controller = State(initialValue: CollectionDetailController(collectionID: collectionID, store: store))
    }

    var body: some View {
        Group {
            switch controller.phase {
            case .idle, .loading:
                ProgressView().accessibilityLabel(String(localized: "collections.loading"))
            case .error(let error):
                FeatureEmptyState(
                    title: error == .forbidden || error == .notFound
                        ? String(localized: "collection.unavailable") : error.localizedMessage,
                    retryTitle: String(localized: "action.try_again"),
                    retry: { Task { await controller.load() } }
                )
            case .empty:
                EmptyView()
            case .content:
                if let detail = store.details[controller.collectionID] {
                    List {
                        Section {
                            if !detail.description.isEmpty {
                                Text(detail.description).fixedSize(horizontal: false, vertical: true)
                            }
                            Text(String(localized: String.LocalizationValue(detail.visibility.localizationKey)))
                        }
                        if detail.places.isEmpty {
                            Section { FeatureEmptyState(title: String(localized: "collection.empty")) }
                        } else {
                            Section {
                                ForEach(detail.places.sorted { $0.displayOrder < $1.displayOrder }) { row in
                                    NavigationLink {
                                        PlaceDetailScreen(
                                            placeId: row.place.id,
                                            places: places,
                                            saved: saved,
                                            collections: store,
                                            visits: visits,
                                            draftRepository: draftRepository,
                                            mutationRepository: mutationRepository,
                                            mediaStore: mediaStore,
                                            syncEngine: syncEngine
                                        )
                                    } label: {
                                        CollectionPlaceRow(place: row.place)
                                    }
                                    .swipeActions {
                                        Button(role: .destructive) {
                                            Task { await controller.remove(placeID: row.place.id) }
                                        } label: {
                                            Label("collections.remove", systemImage: "trash")
                                        }
                                        .accessibilityLabel(String(localized: "collections.remove_place \(row.place.name)"))
                                    }
                                }
                            }
                        }
                        if let error = controller.mutationError {
                            Section {
                                Text(error.localizedMutationMessage(fallbackKey: "collections.unable_remove"))
                                    .foregroundStyle(.red)
                            }
                        }
                    }
                    .navigationTitle(detail.title)
                    .refreshable { await controller.load() }
                }
            }
        }
        .task { controller.startIfNeeded() }
    }
}

struct CollectionPlaceRow: View {
    let place: PlaceSummary
    var body: some View {
        HStack(spacing: PhokartaSpacing.md) {
            PlaceImageView(path: place.coverImage)
                .frame(width: 64, height: 64)
                .clipShape(RoundedRectangle(cornerRadius: PhokartaRadius.md))
            VStack(alignment: .leading) {
                Text(place.name).font(.headline).fixedSize(horizontal: false, vertical: true)
                Text(place.city).font(.subheadline).foregroundStyle(.secondary)
                Text(place.communityScore.map(ScoreFormatting.display) ?? String(localized: "score.not_rated"))
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}

struct CreateCollectionSheet: View {
    let isSubmitting: Bool
    let error: AppError?
    let coverImage: String?
    let onSubmit: (CreateCollectionRequestDTO) async -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var description = ""
    @State private var visibility: CollectionVisibility = .privateAccess
    @State private var cover = ""

    private var effectiveCover: String {
        let preset = (coverImage ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return (preset.isEmpty ? cover : preset).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var isValid: Bool {
        let name = title.trimmingCharacters(in: .whitespacesAndNewlines)
        return !name.isEmpty && name.count <= 120 && description.count <= 1000 &&
            !effectiveCover.isEmpty && effectiveCover.count <= 500
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField("collections.name", text: $title)
                    .textInputAutocapitalization(.sentences)
                TextField("collections.description_optional", text: $description, axis: .vertical)
                    .lineLimit(2...5)
                if (coverImage ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    TextField("collections.cover_url", text: $cover)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                }
                Picker("collections.visibility", selection: $visibility) {
                    ForEach(CollectionVisibility.allCases, id: \.self) { value in
                        Text(String(localized: String.LocalizationValue(value.localizationKey))).tag(value)
                    }
                }
                if let error {
                    Text(error.localizedMutationMessage(fallbackKey: "collections.unable_create"))
                        .foregroundStyle(.red)
                }
            }
            .navigationTitle(String(localized: "collections.new"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("action.cancel") { dismiss() }.disabled(isSubmitting)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        Task {
                            await onSubmit(CreateCollectionRequestDTO(
                                title: title.trimmingCharacters(in: .whitespacesAndNewlines),
                                description: description.trimmingCharacters(in: .whitespacesAndNewlines),
                                visibility: visibility,
                                coverImage: effectiveCover
                            ))
                        }
                    } label: {
                        if isSubmitting { ProgressView() } else { Text("collections.create") }
                    }
                    .disabled(!isValid || isSubmitting)
                }
            }
            .interactiveDismissDisabled(isSubmitting)
        }
    }
}

struct ActivityTab: View {
    let environment: AppEnvironment
    let currentUserId: UUID
    @State private var path: [AppRoute] = []

    var body: some View {
        NavigationStack(path: $path) {
            ActivityFeedScreen(
                controller: ActivityFeedController(
                    activityService: environment.activity,
                    socialService: environment.social,
                    store: environment.socialState
                ),
                currentUserId: currentUserId,
                reportService: environment.reportService,
                onOpenPlace: { path.append(.placeDetail($0)) },
                onOpenAuthor: { path.append(.userProfile($0)) }
            )
            .navigationDestination(for: AppRoute.self) { route in
                AppRouteDestinationView(
                    route: route,
                    environment: environment,
                    currentUserId: currentUserId,
                    onNavigate: { path.append($0) }
                )
            }
        }
    }
}

struct ProfileTab: View {
    let environment: AppEnvironment
    let user: CurrentUser
    let onLogout: () -> Void
    @State private var path: [AppRoute] = []

    var body: some View {
        NavigationStack(path: $path) {
            UserProfileScreen(
                userId: user.id,
                isOwnProfile: true,
                service: environment.social,
                store: environment.socialState,
                blockService: environment.blockService,
                reportService: environment.reportService,
                onSelectPlace: { path.append(.placeDetail($0)) },
                onSelectUser: { path.append(.userProfile($0)) },
                onFollowers: { path.append(.socialList(.followers)) },
                onFollowing: { path.append(.socialList(.following)) },
                onFriends: { path.append(.socialList(.friends)) },
                onUserSearch: { path.append(.userSearch) },
                onSettings: { path.append(.settings) },
                onLogout: onLogout
            )
            .navigationDestination(for: AppRoute.self) { route in
                AppRouteDestinationView(
                    route: route,
                    environment: environment,
                    currentUserId: user.id,
                    onNavigate: { path.append($0) },
                    onLogout: onLogout
                )
            }
        }
    }
}
