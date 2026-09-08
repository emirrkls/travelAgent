import Foundation
import CoreLocation
import Observation

@MainActor
@Observable
final class MapController {
    // MARK: - State
    private(set) var allPlaces: [PlaceSummary] = []
    private(set) var visiblePlaces: [PlaceSummary] = []
    private(set) var filters: MapFilters = MapFilters()
    private(set) var selectedPlaceId: UUID? = nil
    private(set) var appliedViewport: MapViewport? = nil
    private(set) var cameraViewport: MapViewport? = nil
    private(set) var showSearchThisArea: Bool = false
    private(set) var userLocation: (latitude: Double, longitude: Double)? = nil
    private(set) var locationMessageKey: String? = nil
    private(set) var isLoading: Bool = false
    private(set) var boundsErrorMessageKey: String? = nil
    private(set) var friendMetrics: [UUID: FriendPlaceMetrics] = [:]
    private(set) var friendMetricsLoading: Bool = false
    private(set) var friendMetricsErrorKey: String? = nil
    private(set) var saveErrorMessageKey: String? = nil
    private(set) var hasPannedManually: Bool = false
    private(set) var cameraRequest: (latitude: Double, longitude: Double, zoom: Double)? = nil

    // MARK: - Dependencies
    let places: any PlaceServing
    let saved: SavedPlaceStore
    let visits: VisitStore
    let locationService: any LocationProviding
    private(set) var currentAccountId: UUID?

    // MARK: - Concurrency / Race protection
    private var boundsRequestId: Int = 0
    private var boundsTask: Task<Void, Never>? = nil
    private var friendMetricsTask: Task<Void, Never>? = nil
    private var locationTask: Task<Void, Never>? = nil
    private var saveTask: Task<Void, Never>? = nil

    func waitForPendingTasks() async {
        await boundsTask?.value
        await friendMetricsTask?.value
        await locationTask?.value
        await saveTask?.value
    }

    init(
        places: any PlaceServing,
        saved: SavedPlaceStore,
        visits: VisitStore,
        locationService: any LocationProviding,
        currentAccountId: UUID? = nil
    ) {
        self.places = places
        self.saved = saved
        self.visits = visits
        self.locationService = locationService
        self.currentAccountId = currentAccountId
    }

    // MARK: - Camera & Viewport
    func onCameraChange(viewport: MapViewport, isUserInteraction: Bool = false) {
        if isUserInteraction {
            hasPannedManually = true
        }
        cameraViewport = viewport

        guard let applied = appliedViewport else {
            appliedViewport = viewport
            showSearchThisArea = false
            fetchBounds(viewport: viewport)
            return
        }

        let moved = viewportMovedEnough(applied: applied, candidate: viewport)
        showSearchThisArea = moved
        recomputeVisiblePlaces()
    }

    func bootstrapDefaultAreaIfNeeded() {
        guard allPlaces.isEmpty, appliedViewport == nil, !isLoading else { return }
        let defaultViewport = MapDefaults.defaultViewport()
        appliedViewport = defaultViewport
        cameraViewport = defaultViewport
        showSearchThisArea = false
        fetchBounds(viewport: defaultViewport)
    }

    func searchThisArea() {
        guard let viewport = cameraViewport else { return }
        appliedViewport = viewport
        showSearchThisArea = false
        fetchBounds(viewport: viewport)
    }

    func consumeCameraRequest() {
        cameraRequest = nil
    }

    // MARK: - Bounds Loading
    func fetchBounds(viewport: MapViewport) {
        appliedViewport = viewport
        boundsRequestId += 1
        let currentRequestId = boundsRequestId
        boundsTask?.cancel()

        isLoading = true
        boundsErrorMessageKey = nil

        let category = filters.category
        let minRating = filters.highlyRatedOnly ? 9.0 : nil

        boundsTask = Task { [weak self] in
            guard let self else { return }
            do {
                let results = try await self.places.bounds(
                    west: viewport.west,
                    south: viewport.south,
                    east: viewport.east,
                    north: viewport.north,
                    category: category,
                    minRating: minRating,
                    limit: 50
                )

                guard !Task.isCancelled, currentRequestId == self.boundsRequestId else { return }

                // Deduplicate by place ID
                var seen = Set<UUID>()
                var unique: [PlaceSummary] = []
                for p in results {
                    if seen.insert(p.id).inserted {
                        unique.append(p)
                    }
                }

                self.allPlaces = unique
                self.isLoading = false
                self.recomputeVisiblePlaces()
                self.loadFriendMetrics(for: unique, requestId: currentRequestId)
            } catch is CancellationError {
                // Ignore cancellation
            } catch {
                guard !Task.isCancelled, currentRequestId == self.boundsRequestId else { return }
                self.isLoading = false
                self.boundsErrorMessageKey = "map.places_in_this_area"
            }
        }
    }

    private func loadFriendMetrics(for placeList: [PlaceSummary], requestId: Int) {
        friendMetricsTask?.cancel()
        guard !placeList.isEmpty else {
            friendMetrics = [:]
            return
        }

        friendMetricsLoading = true
        friendMetricsErrorKey = nil

        let placeIds = placeList.map(\.id)

        friendMetricsTask = Task { [weak self] in
            guard let self else { return }
            do {
                let metrics = try await self.places.friendMetrics(placeIds: placeIds)
                guard !Task.isCancelled, requestId == self.boundsRequestId else { return }
                var map: [UUID: FriendPlaceMetrics] = [:]
                for m in metrics {
                    map[m.placeId] = m
                }
                self.friendMetrics = map
                self.friendMetricsLoading = false
                self.recomputeVisiblePlaces()
            } catch is CancellationError {
                // Ignore
            } catch {
                guard !Task.isCancelled, requestId == self.boundsRequestId else { return }
                self.friendMetricsLoading = false
                self.friendMetricsErrorKey = "map.friend_signals_unavailable"
            }
        }
    }

    // MARK: - Filtering & Visible Places
    func recomputeVisiblePlaces() {
        let visitedIds = visits.visitedPlaceIDs
        let savedIds = saved.savedPlaceIDs

        let filtered = filterMapPlaces(
            places: allPlaces,
            filters: filters,
            visitedPlaceIds: visitedIds,
            savedPlaceIds: savedIds,
            viewport: cameraViewport,
            friendMetrics: friendMetrics
        )
        visiblePlaces = filtered

        if let selected = selectedPlaceId, !filtered.contains(where: { $0.id == selected }) {
            selectedPlaceId = nil
        }
    }

    func selectCategory(_ category: PlaceCategory?) {
        guard filters.category != category else { return }
        filters.category = category
        if let applied = appliedViewport {
            fetchBounds(viewport: applied)
        } else {
            recomputeVisiblePlaces()
        }
    }

    func toggleHighlyRated() {
        filters.highlyRatedOnly.toggle()
        if let applied = appliedViewport {
            fetchBounds(viewport: applied)
        } else {
            recomputeVisiblePlaces()
        }
    }

    func toggleFriendsVisited() {
        filters.friendsVisitedOnly.toggle()
        recomputeVisiblePlaces()
    }

    func toggleVisited() {
        filters.visitedOnly.toggle()
        recomputeVisiblePlaces()
    }

    func toggleWantToGo() {
        filters.wantToGoOnly.toggle()
        recomputeVisiblePlaces()
    }

    func clearFilters() {
        let hadRemoteFilter = filters.category != nil || filters.highlyRatedOnly
        filters = MapFilters()
        if hadRemoteFilter, let applied = appliedViewport {
            fetchBounds(viewport: applied)
        } else {
            recomputeVisiblePlaces()
        }
    }

    // MARK: - Selection
    func selectPlace(_ id: UUID) {
        selectedPlaceId = id
    }

    func clearSelection() {
        selectedPlaceId = nil
    }

    // MARK: - Saved Mutations
    func toggleSaved(placeId: UUID) {
        saved.toggle(placeId)
        recomputeVisiblePlaces()
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            guard let self else { return }
            await self.saved.waitForMutation(of: placeId)
            if self.saved.error(for: placeId) != nil {
                self.saveErrorMessageKey = "error.general"
            } else {
                self.saveErrorMessageKey = nil
            }
            self.recomputeVisiblePlaces()
        }
    }

    func dismissSaveError() {
        saveErrorMessageKey = nil
    }

    // MARK: - Location
    func requestCurrentLocation(userInitiated: Bool = true) {
        locationTask?.cancel()
        locationTask = Task { [weak self] in
            guard let self else { return }
            let result = await self.locationService.requestLocation()
            switch result {
            case .success(let coord):
                self.userLocation = (coord.latitude, coord.longitude)
                self.locationMessageKey = nil
                // If user tapped location or has not manually panned yet:
                if userInitiated || !self.hasPannedManually {
                    self.cameraRequest = (coord.latitude, coord.longitude, 14.5)
                }
            case .failure(let error):
                switch error {
                case .denied:
                    self.locationMessageKey = "map.location_denied"
                case .restricted, .unavailable:
                    self.locationMessageKey = "map.location_unavailable"
                }
            }
        }
    }

    func dismissLocationMessage() {
        locationMessageKey = nil
    }

    // MARK: - Account Switch & Invalidation
    func activate(accountId: UUID) {
        guard currentAccountId != accountId else { return }
        currentAccountId = accountId
        friendMetrics = [:]
        selectedPlaceId = nil
        recomputeVisiblePlaces()
    }

    func purgeAccountState() {
        currentAccountId = nil
        friendMetrics = [:]
        selectedPlaceId = nil
        filters = MapFilters()
        recomputeVisiblePlaces()
    }

    func invalidateFriendMetrics() {
        friendMetrics = [:]
        if !allPlaces.isEmpty {
            loadFriendMetrics(for: allPlaces, requestId: boundsRequestId)
        }
        recomputeVisiblePlaces()
    }
}
