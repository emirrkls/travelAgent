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
                collections: environment.collections
            )
                .tabItem {
                    Label {
                        Text("tab.explore")
                    } icon: {
                        Image(systemName: "safari")
                    }
                }

            SavedScreen(
                store: environment.saved,
                places: environment.places,
                collections: environment.collections
            )
            .tabItem {
                Label("saved.title", systemImage: "bookmark")
            }

            CollectionsScreen(
                store: environment.collections,
                saved: environment.saved,
                places: environment.places
            )
            .tabItem {
                Label("collections.title", systemImage: "square.stack")
            }

            ProfilePlaceholderView(user: user) {
                Task { await environment.session.logout() }
            }
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
            try? await environment.saved.refresh()
            try? await environment.collections.refreshList()
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
    @Environment(\.colorScheme) private var colorScheme

    init(store: SavedPlaceStore, places: any PlaceServing, collections: CollectionStore) {
        self.store = store
        self.places = places
        self.collections = collections
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
                if case .placeDetail(let id) = route {
                    PlaceDetailScreen(placeId: id, places: places, saved: store, collections: collections)
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
    @Environment(\.colorScheme) private var colorScheme

    init(store: CollectionStore, saved: SavedPlaceStore, places: any PlaceServing) {
        self.store = store
        self.saved = saved
        self.places = places
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
                    places: places
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

    init(collectionID: UUID, store: CollectionStore, saved: SavedPlaceStore, places: any PlaceServing) {
        self.store = store
        self.saved = saved
        self.places = places
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
                                            collections: store
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

struct ProfilePlaceholderView: View {
    let user: CurrentUser
    let onLogout: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: PhokartaSpacing.md) {
                Text(user.displayName)
                    .font(.title.bold())
                    .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                Text(user.username)
                    .font(.body)
                    .foregroundStyle(PhokartaColor.muted(for: colorScheme))
                Text("profile.placeholder")
                    .font(.body)
                    .foregroundStyle(PhokartaColor.muted(for: colorScheme))
                    .fixedSize(horizontal: false, vertical: true)
                Spacer()
                Button(action: onLogout) {
                    Text("auth.logout")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, PhokartaSpacing.md)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.white)
                .background(PhokartaColor.accent(for: colorScheme), in: RoundedRectangle(cornerRadius: PhokartaRadius.lg))
                .accessibilityLabel(String(localized: "auth.logout"))
            }
            .padding(PhokartaSpacing.lg)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .background(PhokartaColor.background(for: colorScheme))
            .navigationTitle(String(localized: "profile.title"))
        }
    }
}
