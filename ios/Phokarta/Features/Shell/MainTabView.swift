import SwiftUI
import Observation

struct MainTabView: View {
    let environment: AppEnvironment
    let user: CurrentUser
    @Environment(\.colorScheme) private var colorScheme
    @State private var selection = 0

    var body: some View {
        TabView(selection: $selection) {
            ExploreScreen(
                environment: environment,
                currentUserId: user.id,
                onOpenMap: { selection = 1 }
            )
            .tag(0)
            .tabItem {
                Label {
                    Text("tab.explore")
                } icon: {
                    Image(systemName: "safari")
                }
            }

            MapScreen(
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
            .tag(1)
            .tabItem {
                Label {
                    Text("tab.map")
                } icon: {
                    Image(systemName: "map")
                }
            }

            AddExperienceTab(environment: environment)
            .tabItem {
                Label {
                    Text("tab.add")
                } icon: {
                    Image(systemName: "plus.circle.fill")
                }
            }
            .tag(2)

            SavedTab(
                environment: environment,
                user: user
            )
            .tabItem {
                Label("tab.plan", systemImage: "bookmark")
            }
            .tag(3)

            ProfileTab(
                environment: environment,
                user: user,
                onLogout: {
                    Task { await environment.session.logout() }
                }
            )
            .tag(4)
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
            await environment.policyStore.activateAndLoad(accountId: user.id)
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

enum SavedCollectionsSegment: Int, CaseIterable {
    case wantToGo = 0
    case collections = 1
}

enum WantToGoSegment: Int, CaseIterable { case experiences = 0, places = 1 }

struct SavedTab: View {
    @State private var selectedSegment: SavedCollectionsSegment = .wantToGo
    @State private var wantToGoSegment: WantToGoSegment = .experiences
    let environment: AppEnvironment
    let user: CurrentUser
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $selectedSegment) {
                Text(String(localized: "plan.want_to_go")).tag(SavedCollectionsSegment.wantToGo)
                Text(String(localized: "saved.collections_tab")).tag(SavedCollectionsSegment.collections)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, PhokartaSpacing.md)
            .padding(.top, 8)
            .padding(.bottom, 4)
            .background(PhokartaColor.background(for: colorScheme))

            if selectedSegment == .wantToGo {
                VStack(spacing: 0) {
                    Picker("", selection: $wantToGoSegment) {
                        Text(String(localized: "plan.experiences")).tag(WantToGoSegment.experiences)
                        Text(String(localized: "plan.places")).tag(WantToGoSegment.places)
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal, PhokartaSpacing.md)
                    .padding(.vertical, 8)
                    if wantToGoSegment == .experiences {
                        PlannedExperiencesScreen(environment: environment, currentUserId: user.id)
                    } else {
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
                    }
                }
            } else {
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
            }
        }
    }
}

@MainActor
@Observable
final class PlannedExperiencesController {
    private(set) var rows: [DurablePlannedExperienceRow] = []
    private(set) var loading = false
    private(set) var error: AppError?
    private let service: any ExperienceDiscoveryServing
    private let mutations: any OfflineMutationRepository
    private let syncEngine: MutationSyncEngine
    private let userId: UUID
    init(service: any ExperienceDiscoveryServing, mutations: any OfflineMutationRepository,
         syncEngine: MutationSyncEngine, userId: UUID) {
        self.service = service
        self.mutations = mutations
        self.syncEngine = syncEngine
        self.userId = userId
    }
    func load() async {
        loading = rows.isEmpty
        defer { loading = false }
        let local = (try? await mutations.localPlannedExperiences(userId: userId)) ?? []
        if rows.isEmpty { rows = local }
        do {
            let remote = try await service.plannedExperiences(page: 0).content.map {
                DurablePlannedExperienceRow(
                    id: $0.id, title: $0.experience.title, placeName: $0.experience.place.name,
                    authorName: $0.experience.author.displayName,
                    primaryExperienceCode: $0.experience.primaryExperience.code.rawValue,
                    imageURL: $0.experience.media.min(by: { $0.position < $1.position })?.url,
                    plannedAt: $0.plannedAt, pendingDesiredState: nil
                )
            }
            var seen = Set<UUID>()
            let optimistic = local.filter { $0.pendingDesiredState == true }
            rows = (optimistic + remote).filter { seen.insert($0.id).inserted }
            error = nil
        }
        catch let value as AppError { error = value }
        catch { self.error = .server }
    }
    func remove(_ id: UUID) async {
        do {
            try await mutations.removePlannedExperience(experienceId: id, userId: userId)
            rows.removeAll { $0.id == id }
            _ = await syncEngine.drain()
        }
        catch let value as AppError { error = value }
        catch { self.error = .server }
    }
}

struct PlannedExperiencesScreen: View {
    @State private var controller: PlannedExperiencesController
    @State private var path: [AppRoute] = []
    let environment: AppEnvironment
    let currentUserId: UUID
    @Environment(\.colorScheme) private var colorScheme

    init(environment: AppEnvironment, currentUserId: UUID) {
        self.environment = environment
        self.currentUserId = currentUserId
        _controller = State(initialValue: PlannedExperiencesController(
            service: environment.experiences, mutations: environment.mutationRepository,
            syncEngine: environment.syncEngine, userId: currentUserId
        ))
    }

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if controller.loading && controller.rows.isEmpty { ProgressView() }
                else if controller.rows.isEmpty {
                    FeatureEmptyState(title: String(localized: "plan.experiences.empty"))
                } else {
                    List(controller.rows) { row in
                        Button { path.append(.experienceDetail(row.id)) } label: {
                            HStack(spacing: 12) {
                                AsyncImage(url: row.imageURL.flatMap { URL(string: $0) }) { image in
                                    image.resizable().scaledToFill()
                                } placeholder: { Rectangle().fill(PhokartaColor.mist) }
                                .frame(width: 72, height: 72).clipShape(RoundedRectangle(cornerRadius: 12))
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(row.title).font(.headline).lineLimit(2)
                                    Text(row.placeName).font(.subheadline).foregroundStyle(.secondary)
                                    Text(row.authorName).font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Button { Task { await controller.remove(row.id) } } label: {
                                    Image(systemName: "bookmark.fill")
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(String(localized: "experience.remove_from_plan"))
                            }
                        }.buttonStyle(.plain)
                    }.listStyle(.plain)
                }
            }
            .background(PhokartaColor.background(for: colorScheme))
            .refreshable { await controller.load() }
            .task { if controller.rows.isEmpty { await controller.load() } }
            .navigationDestination(for: AppRoute.self) { route in
                AppRouteDestinationView(route: route, environment: environment, currentUserId: currentUserId) { path.append($0) }
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

    func remove(experienceID: UUID) async {
        mutationError = nil
        do { try await store.remove(experienceID: experienceID, from: collectionID) }
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
    @Environment(\.locale) private var locale

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
                            Text("\(CollectionContentPresentation(items: detail.items).label(locale: locale)) · \(phokartaString(detail.visibility.localizationKey, locale: locale))")
                        }
                        if detail.items.isEmpty {
                            Section { FeatureEmptyState(title: String(localized: "collection.empty")) }
                        } else {
                            Section {
                                ForEach(detail.items.sorted { $0.displayOrder < $1.displayOrder }) { item in
                                    if let place = item.place, item.type == .place {
                                        NavigationLink {
                                            PlaceDetailScreen(
                                                placeId: place.id, places: places, saved: saved,
                                                collections: store, visits: visits,
                                                draftRepository: draftRepository,
                                                mutationRepository: mutationRepository,
                                                mediaStore: mediaStore, syncEngine: syncEngine
                                            )
                                        } label: {
                                            CollectionPlaceRow(place: place)
                                        }
                                        .swipeActions {
                                            Button(role: .destructive) {
                                                Task { await controller.remove(placeID: place.id) }
                                            } label: { Label("collections.remove", systemImage: "trash") }
                                            .accessibilityLabel(String(localized: "collections.remove_place \(place.name)"))
                                        }
                                    } else if let experience = item.experience, item.type == .experience {
                                        CollectionExperienceRow(experience: experience)
                                            .swipeActions {
                                                Button(role: .destructive) {
                                                    Task { await controller.remove(experienceID: experience.id) }
                                                } label: { Label("collections.remove", systemImage: "trash") }
                                                .accessibilityLabel(String(localized: "collection.remove_experience \(experience.title)"))
                                            }
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

struct CollectionExperienceRow: View {
    let experience: ExperienceV2
    @Environment(\.locale) private var locale

    var body: some View {
        HStack(spacing: PhokartaSpacing.md) {
            AsyncImage(url: experience.media.sorted(by: { $0.position < $1.position }).first.flatMap { URL(string: $0.url) }) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                Image(systemName: "sparkles").foregroundStyle(.secondary)
            }
            .frame(width: 64, height: 64)
            .clipShape(RoundedRectangle(cornerRadius: PhokartaRadius.md))
            VStack(alignment: .leading, spacing: 3) {
                Text(experience.title).font(.headline)
                Text(experience.place.name).font(.subheadline).foregroundStyle(.secondary)
                Text(ExperienceLocalizedLabels.primary(experience.primaryExperience.code, locale: locale) ?? "")
                    .font(.caption).foregroundStyle(.tint)
                Label("collection.item_experience", systemImage: "sparkles")
                    .font(.caption2).foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(String(localized: "collection.experience_item \(experience.title) \(experience.place.name)"))
    }
}

enum CollectionContentKind: Equatable {
    case places
    case experiences
    case mixed
}

struct CollectionContentPresentation: Equatable {
    let kind: CollectionContentKind
    let placeCount: Int
    let experienceCount: Int

    init(items: [CollectionItem]) {
        self.init(
            placeCount: items.count { $0.type == .place && $0.place != nil },
            experienceCount: items.count { $0.type == .experience && $0.experience != nil }
        )
    }

    init(placeCount: Int, experienceCount: Int) {
        self.placeCount = placeCount
        self.experienceCount = experienceCount
        kind = if placeCount > 0 && experienceCount > 0 {
            .mixed
        } else if experienceCount > 0 {
            .experiences
        } else {
            .places
        }
    }

    var totalCount: Int { placeCount + experienceCount }

    func label(locale: Locale) -> String {
        switch kind {
        case .places:
            if placeCount == 1 { return phokartaString("collection.summary.place.one", locale: locale) }
            return String(format: phokartaString("collection.summary.place.many %lld", locale: locale), locale: locale, Int64(placeCount))
        case .experiences:
            if experienceCount == 1 { return phokartaString("collection.summary.experience.one", locale: locale) }
            return String(format: phokartaString("collection.summary.experience.many %lld", locale: locale), locale: locale, Int64(experienceCount))
        case .mixed:
            return String(format: phokartaString("collection.summary.mixed %lld", locale: locale), locale: locale, Int64(totalCount))
        }
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
                Label("collection.item_place", systemImage: "mappin")
                    .font(.caption2).foregroundStyle(.tint)
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
                experienceService: environment.experiences,
                mutationRepository: environment.mutationRepository,
                onSelectExperience: { path.append(.experienceDetail($0)) },
                onConvertAcknowledgement: { acknowledgement in
                    let now = Int64(Date().timeIntervalSince1970 * 1000)
                    Task {
                        var draft = (try? await environment.draftRepository.getDraft(
                            placeId: acknowledgement.place.id,
                            userId: user.id
                        )) ?? DurableVisitDraft(
                            userId: user.id, placeId: acknowledgement.place.id,
                            overallScore: 8, publicReview: "", privateMemory: "",
                            visitedAtEpochDay: Int64(Date().timeIntervalSince1970 / 86400),
                            visibility: VisitVisibility.publicAccess.rawValue,
                            dimensionsExpanded: false, createdAtEpochMillis: now,
                            updatedAtEpochMillis: now
                        )
                        draft.payloadVersion = 2
                        draft.primaryExperienceCode = acknowledgement.primaryExperienceCode.rawValue
                        draft.rawExperienceLabel = acknowledgement.rawExperienceLabel
                        draft.originAcknowledgementId = acknowledgement.id
                        draft.updatedAtEpochMillis = now
                        try? await environment.draftRepository.saveDraft(
                            placeId: acknowledgement.place.id, draft: draft, userId: user.id
                        )
                        await MainActor.run { path.append(.placeComposer(acknowledgement.place.id)) }
                    }
                },
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
