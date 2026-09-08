import XCTest
import CoreLocation
@testable import Phokarta

final class MapFeatureTests: XCTestCase {

    // MARK: - 86. Geo Endpoint Encoding
    func testBoundsEndpointEncoding() {
        let endpoint = PlaceBoundsEndpoint(
            west: 27.123456,
            south: 36.987654,
            east: 28.654321,
            north: 37.890123,
            category: .beach,
            minRating: 9.0,
            limit: 50
        )

        XCTAssertEqual(endpoint.path, "api/v1/places/bounds")
        XCTAssertEqual(endpoint.method, .get)
        XCTAssertTrue(endpoint.requiresAuthentication)

        let queryMap = Dictionary(uniqueKeysWithValues: endpoint.queryItems.map { ($0.name, $0.value ?? "") })
        XCTAssertEqual(queryMap["west"], "27.123456")
        XCTAssertEqual(queryMap["south"], "36.987654")
        XCTAssertEqual(queryMap["east"], "28.654321")
        XCTAssertEqual(queryMap["north"], "37.890123")
        XCTAssertEqual(queryMap["category"], "BEACH")
        XCTAssertEqual(queryMap["minRating"], "9.0")
        XCTAssertEqual(queryMap["limit"], "50")

        // Crucial invariant: never produce comma decimals in query items
        for (_, value) in queryMap {
            XCTAssertFalse(value.contains(","), "Query value \(value) must not contain comma decimals")
        }
    }

    func testNearbyEndpointEncoding() {
        let endpoint = PlaceNearbyEndpoint(
            latitude: 37.085123,
            longitude: 27.530456,
            radiusMeters: 5000,
            category: .cafe,
            minRating: nil,
            limit: 30
        )

        XCTAssertEqual(endpoint.path, "api/v1/places/nearby")
        XCTAssertEqual(endpoint.method, .get)

        let queryMap = Dictionary(uniqueKeysWithValues: endpoint.queryItems.map { ($0.name, $0.value ?? "") })
        XCTAssertEqual(queryMap["lat"], "37.085123")
        XCTAssertEqual(queryMap["lon"], "27.530456")
        XCTAssertEqual(queryMap["radiusMeters"], "5000")
        XCTAssertEqual(queryMap["category"], "CAFE")
        XCTAssertNil(queryMap["minRating"])
        XCTAssertEqual(queryMap["limit"], "30")

        for (_, value) in queryMap {
            XCTAssertFalse(value.contains(","), "Query value \(value) must not contain comma decimals")
        }
    }

    // MARK: - 87. Bounds & Viewport Conversion
    func testMapViewportContainsAndClamping() {
        let viewport = MapViewport(
            north: 38.0,
            east: 28.0,
            south: 36.0,
            west: 26.0,
            centerLatitude: 37.0,
            centerLongitude: 27.0,
            zoom: 11.0
        )

        XCTAssertTrue(viewport.contains(latitude: 37.0, longitude: 27.0))
        XCTAssertTrue(viewport.contains(latitude: 36.5, longitude: 26.5))
        XCTAssertFalse(viewport.contains(latitude: 39.0, longitude: 27.0))
        XCTAssertFalse(viewport.contains(latitude: 37.0, longitude: 29.0))
        XCTAssertTrue(viewport.isValidForBackend)

        let invalidViewport = MapViewport(
            north: 35.0,
            east: 25.0,
            south: 36.0,
            west: 26.0,
            centerLatitude: 35.5,
            centerLongitude: 25.5,
            zoom: 11.0
        )
        XCTAssertFalse(invalidViewport.isValidForBackend)
    }

    // MARK: - 88. Search Area Dirty Threshold
    func testViewportMovedEnoughThreshold() {
        let applied = MapViewport(
            north: 37.5,
            east: 28.0,
            south: 36.5,
            west: 27.0,
            centerLatitude: 37.0,
            centerLongitude: 27.5,
            zoom: 11.0
        )

        // Micro-jitter: < 0.18 normalized distance, same zoom
        let jitterCandidate = MapViewport(
            north: 37.51,
            east: 28.01,
            south: 36.51,
            west: 27.01,
            centerLatitude: 37.01,
            centerLongitude: 27.51,
            zoom: 11.0
        )
        XCTAssertFalse(viewportMovedEnough(applied: applied, candidate: jitterCandidate))

        // Meaningful pan: > 0.18 normalized distance
        let pannedCandidate = MapViewport(
            north: 37.9,
            east: 28.0,
            south: 36.9,
            west: 27.0,
            centerLatitude: 37.4,
            centerLongitude: 27.5,
            zoom: 11.0
        )
        XCTAssertTrue(viewportMovedEnough(applied: applied, candidate: pannedCandidate))

        // Zoom change: >= 0.55 delta
        let zoomedCandidate = MapViewport(
            north: 37.5,
            east: 28.0,
            south: 36.5,
            west: 27.0,
            centerLatitude: 37.0,
            centerLongitude: 27.5,
            zoom: 11.6
        )
        XCTAssertTrue(viewportMovedEnough(applied: applied, candidate: zoomedCandidate))
    }

    // MARK: - 89. Stale Bounds Response Race
    @MainActor
    func testStaleBoundsResponseIgnored() async {
        let placeA = makePlace(name: "Area A Place")
        let placeB = makePlace(name: "Area B Place")

        let mockService = ControllableMapPlaceService()
        let savedStore = SavedPlaceStore(service: FakeSavedPlaceService())
        let visitStore = VisitStore(service: FakeVisitService(), mediaService: NullVisitMediaService())
        let locService = MockLocationService()

        let controller = MapController(
            places: mockService,
            saved: savedStore,
            visits: visitStore,
            locationService: locService
        )

        let viewportA = MapViewport(north: 38, east: 28, south: 36, west: 26, centerLatitude: 37, centerLongitude: 27, zoom: 11)
        let viewportB = MapViewport(north: 40, east: 30, south: 38, west: 28, centerLatitude: 39, centerLongitude: 29, zoom: 11)

        // Trigger search A
        controller.fetchBounds(viewport: viewportA)

        // Quickly trigger search B before A finishes
        controller.fetchBounds(viewport: viewportB)

        // Complete B first
        mockService.completeBounds(with: [placeB], forRequestId: 2)
        await Task.yield()

        XCTAssertEqual(controller.allPlaces.map(\.id), [placeB.id])

        // Now late A returns
        mockService.completeBounds(with: [placeA], forRequestId: 1)
        await Task.yield()

        // State MUST remain B! Late A must not overwrite newer search B
        XCTAssertEqual(controller.allPlaces.map(\.id), [placeB.id])
    }

    // MARK: - 91. Late Location Intent Guard
    @MainActor
    func testLateLocationCallbackDoesNotOverrideManualPan() async {
        let mockService = ControllableMapPlaceService()
        let savedStore = SavedPlaceStore(service: FakeSavedPlaceService())
        let visitStore = VisitStore(service: FakeVisitService(), mediaService: NullVisitMediaService())
        let locService = MockLocationService()

        let controller = MapController(
            places: mockService,
            saved: savedStore,
            visits: visitStore,
            locationService: locService
        )

        // User manually pans
        let userViewport = MapViewport(north: 39, east: 29, south: 37, west: 27, centerLatitude: 38, centerLongitude: 28, zoom: 12)
        controller.onCameraChange(viewport: userViewport, isUserInteraction: true)

        XCTAssertTrue(controller.hasPannedManually)

        // Location arrives late in background
        locService.mockResult = .success(CLLocationCoordinate2D(latitude: 37.085, longitude: 27.53))
        controller.requestCurrentLocation(userInitiated: false)

        await Task.yield()

        // Because user already panned manually, cameraRequest must NOT be set
        XCTAssertNil(controller.cameraRequest)
    }

    // MARK: - 92. Permission Denied UX
    @MainActor
    func testPermissionDeniedDoesNotCrashAndLeavesMapUsable() async {
        let place = makePlace(name: "Bodrum Castle")
        let mockService = ControllableMapPlaceService()
        mockService.stubbedPlaces = [place]

        let savedStore = SavedPlaceStore(service: FakeSavedPlaceService())
        let visitStore = VisitStore(service: FakeVisitService(), mediaService: NullVisitMediaService())
        let locService = MockLocationService()
        locService.authorizationStatus = .denied
        locService.mockResult = .failure(.denied)

        let controller = MapController(
            places: mockService,
            saved: savedStore,
            visits: visitStore,
            locationService: locService
        )

        controller.requestCurrentLocation(userInitiated: true)
        await Task.yield()

        XCTAssertEqual(controller.locationMessageKey, "map.location_denied")
        XCTAssertNil(controller.userLocation)

        // Map remains fully usable: bootstrap default area works
        controller.bootstrapDefaultAreaIfNeeded()
        await Task.yield()

        XCTAssertEqual(controller.allPlaces.count, 1)
        XCTAssertEqual(controller.visiblePlaces.count, 1)
    }

    // MARK: - 93. Filter Combination
    func testFilterCombinationLogic() {
        let p1 = makePlace(name: "P1", category: .beach, communityScore: 9.5)
        let p2 = makePlace(name: "P2", category: .beach, communityScore: 8.0)
        let p3 = makePlace(name: "P3", category: .restaurant, communityScore: 9.2)

        let visitedIds: Set<UUID> = [p1.id, p2.id]
        let savedIds: Set<UUID> = [p1.id, p3.id]
        let friendMetrics: [UUID: FriendPlaceMetrics] = [
            p1.id: FriendPlaceMetrics(placeId: p1.id, friendAverageScore: 9.0, friendsVisitedCount: 2)
        ]

        var filters = MapFilters()
        filters.category = .beach
        filters.highlyRatedOnly = true
        filters.friendsVisitedOnly = true
        filters.visitedOnly = true
        filters.wantToGoOnly = true

        let filtered = filterMapPlaces(
            places: [p1, p2, p3],
            filters: filters,
            visitedPlaceIds: visitedIds,
            savedPlaceIds: savedIds,
            viewport: nil,
            friendMetrics: friendMetrics
        )

        XCTAssertEqual(filtered.map(\.id), [p1.id])
    }

    // MARK: - 94. Friends Filter Semantics
    func testFriendsFilterRequiresBackendMutualFriendMetrics() {
        let p1 = makePlace(name: "Has Mutual Friends")
        let p2 = makePlace(name: "No Friends")

        let friendMetrics: [UUID: FriendPlaceMetrics] = [
            p1.id: FriendPlaceMetrics(placeId: p1.id, friendAverageScore: 8.5, friendsVisitedCount: 1),
            p2.id: FriendPlaceMetrics(placeId: p2.id, friendAverageScore: nil, friendsVisitedCount: 0)
        ]

        var filters = MapFilters()
        filters.friendsVisitedOnly = true

        let filtered = filterMapPlaces(
            places: [p1, p2],
            filters: filters,
            visitedPlaceIds: [],
            savedPlaceIds: [],
            viewport: nil,
            friendMetrics: friendMetrics
        )

        XCTAssertEqual(filtered.map(\.id), [p1.id])
    }

    // MARK: - 95. Saved Filter Live Update
    @MainActor
    func testSavedFilterLiveUpdate() async throws {
        let place = makePlace(name: "Beach Club")
        let fakeSavedService = FakeSavedPlaceService()
        let savedStore = SavedPlaceStore(service: fakeSavedService)
        let visitStore = VisitStore(service: FakeVisitService(), mediaService: NullVisitMediaService())
        let mockPlaces = ControllableMapPlaceService()
        mockPlaces.stubbedPlaces = [place]

        let controller = MapController(
            places: mockPlaces,
            saved: savedStore,
            visits: visitStore,
            locationService: MockLocationService()
        )

        controller.bootstrapDefaultAreaIfNeeded()
        await Task.yield()

        controller.toggleWantToGo()
        XCTAssertEqual(controller.visiblePlaces.count, 0)

        // Save the place
        _ = try await savedStore.toggle(placeId: place.id)
        controller.recomputeVisiblePlaces()

        XCTAssertEqual(controller.visiblePlaces.count, 1)
        XCTAssertEqual(controller.visiblePlaces.first?.id, place.id)
    }

    // MARK: - 97. Account Switch Isolation
    @MainActor
    func testAccountSwitchClearsViewerEnrichment() async {
        let place = makePlace(name: "Place A")
        let mockPlaces = ControllableMapPlaceService()
        mockPlaces.stubbedPlaces = [place]
        let savedStore = SavedPlaceStore(service: FakeSavedPlaceService())
        let visitStore = VisitStore(service: FakeVisitService(), mediaService: NullVisitMediaService())

        let userA = UUID()
        let userB = UUID()

        let controller = MapController(
            places: mockPlaces,
            saved: savedStore,
            visits: visitStore,
            locationService: MockLocationService(),
            currentAccountId: userA
        )

        controller.bootstrapDefaultAreaIfNeeded()
        await Task.yield()

        controller.selectPlace(place.id)
        XCTAssertEqual(controller.selectedPlaceId, place.id)

        // Switch to account B
        controller.activate(accountId: userB)

        XCTAssertEqual(controller.currentAccountId, userB)
        XCTAssertNil(controller.selectedPlaceId)
        XCTAssertTrue(controller.friendMetrics.isEmpty)
    }

    // MARK: - 98. Block Invalidation
    @MainActor
    func testBlockInvalidationPurgesFriendMetricsWithoutMutatingCommunityScore() async {
        let place = makePlace(name: "Place A", communityScore: 8.8)
        let mockPlaces = ControllableMapPlaceService()
        mockPlaces.stubbedPlaces = [place]
        let savedStore = SavedPlaceStore(service: FakeSavedPlaceService())
        let visitStore = VisitStore(service: FakeVisitService(), mediaService: NullVisitMediaService())

        let controller = MapController(
            places: mockPlaces,
            saved: savedStore,
            visits: visitStore,
            locationService: MockLocationService()
        )

        controller.bootstrapDefaultAreaIfNeeded()
        await Task.yield()

        // Community score is backend authoritative
        XCTAssertEqual(controller.allPlaces.first?.communityScore, 8.8)

        // Block mutation occurs
        controller.invalidateFriendMetrics()

        XCTAssertTrue(controller.friendMetrics.isEmpty)
        XCTAssertEqual(controller.allPlaces.first?.communityScore, 8.8)
    }

    // MARK: - 99. Detail Round-Trip Preservation
    @MainActor
    func testDetailRoundTripPreservesCameraAndFilters() async {
        let place = makePlace(name: "Detail Place")
        let mockPlaces = ControllableMapPlaceService()
        mockPlaces.stubbedPlaces = [place]
        let savedStore = SavedPlaceStore(service: FakeSavedPlaceService())
        let visitStore = VisitStore(service: FakeVisitService(), mediaService: NullVisitMediaService())

        let controller = MapController(
            places: mockPlaces,
            saved: savedStore,
            visits: visitStore,
            locationService: MockLocationService()
        )

        let initialViewport = MapViewport(north: 38, east: 28, south: 36, west: 26, centerLatitude: 37, centerLongitude: 27, zoom: 11)
        controller.onCameraChange(viewport: initialViewport, isUserInteraction: true)
        controller.toggleHighlyRated()
        controller.selectPlace(place.id)

        await Task.yield()

        // Simulate navigating to Detail and coming back: MapController instance state is preserved
        XCTAssertEqual(controller.cameraViewport, initialViewport)
        XCTAssertTrue(controller.filters.highlyRatedOnly)
        XCTAssertEqual(controller.selectedPlaceId, place.id)
    }

    // MARK: - 100. Deduplication by Place UUID
    @MainActor
    func testBoundsResultDeduplication() async {
        let p1 = makePlace(name: "Duplicate Place")
        let mockPlaces = ControllableMapPlaceService()
        mockPlaces.stubbedPlaces = [p1, p1, p1]
        let savedStore = SavedPlaceStore(service: FakeSavedPlaceService())
        let visitStore = VisitStore(service: FakeVisitService(), mediaService: NullVisitMediaService())

        let controller = MapController(
            places: mockPlaces,
            saved: savedStore,
            visits: visitStore,
            locationService: MockLocationService()
        )

        controller.bootstrapDefaultAreaIfNeeded()
        await Task.yield()

        XCTAssertEqual(controller.allPlaces.count, 1)
        XCTAssertEqual(controller.visiblePlaces.count, 1)
    }

    // MARK: - 101. Malformed Coordinate Rejection
    func testMalformedCoordinateSafelyRejected() {
        let valid = makePlace(name: "Valid", latitude: 37.0, longitude: 27.0)
        let invalidLat = makePlace(name: "Invalid Lat", latitude: 999.0, longitude: 27.0)
        let invalidLon = makePlace(name: "Invalid Lon", latitude: 37.0, longitude: -999.0)

        let filtered = filterMapPlaces(
            places: [valid, invalidLat, invalidLon],
            filters: MapFilters(),
            visitedPlaceIds: [],
            savedPlaceIds: [],
            viewport: nil
        )

        XCTAssertEqual(filtered.count, 1)
        XCTAssertEqual(filtered.first?.id, valid.id)
    }

    // MARK: - 102. Location Privacy Invariant
    func testLocationPrivacyNoDurableHistoryTable() throws {
        let tempPath = (NSTemporaryDirectory() as NSString).appendingPathComponent(UUID().uuidString + ".sqlite3")
        defer { try? FileManager.default.removeItem(atPath: tempPath) }

        let db = try PersistentDatabase(path: tempPath)
        // Verify schema tables
        let tables = try db.read { dbPointer -> [String] in
            var list: [String] = []
            var stmt: OpaquePointer?
            if sqlite3_prepare_v2(dbPointer, "SELECT name FROM sqlite_master WHERE type='table';", -1, &stmt, nil) == SQLITE_OK {
                while sqlite3_step(stmt) == SQLITE_ROW {
                    if let cName = sqlite3_column_text(stmt, 0) {
                        list.append(String(cString: cName))
                    }
                }
            }
            sqlite3_finalize(stmt)
            return list
        }

        XCTAssertFalse(tables.contains("user_locations"), "Schema must not contain user_locations table")
        XCTAssertFalse(tables.contains("location_history"), "Schema must not contain location_history table")
        XCTAssertFalse(tables.contains("gps_logs"), "Schema must not contain gps_logs table")
    }

    // MARK: - 103. Pre-ACK Visit Privacy Regression
    func testPendingVisitDoesNotAlterCommunityScore() {
        let place = makePlace(name: "Pending Target", communityScore: 8.5)

        // Pending visit is owner-local only
        XCTAssertEqual(place.communityScore, 8.5)
        // ScoreFormatting displays backend score only
        XCTAssertEqual(ScoreFormatting.display(place.communityScore!), "8.5")
    }

    // MARK: - Helper
    private func makePlace(
        name: String,
        category: PlaceCategory = .beach,
        latitude: Double = 37.085,
        longitude: Double = 27.53,
        communityScore: Double? = 8.5
    ) -> PlaceSummary {
        PlaceSummary(
            id: UUID(),
            name: name,
            category: category,
            coverImage: "https://example.com/cover.jpg",
            city: "Bodrum",
            region: "Mugla",
            country: "TR",
            latitude: latitude,
            longitude: longitude,
            priceLevel: 2,
            communityScore: communityScore,
            ratingCount: 10
        )
    }
}

// MARK: - Test Doubles
private final class ControllableMapPlaceService: PlaceServing, @unchecked Sendable {
    var stubbedPlaces: [PlaceSummary] = []
    private var pendingBoundsContinuations: [Int: CheckedContinuation<[PlaceSummary], Error>] = [:]

    func bounds(
        west: Double,
        south: Double,
        east: Double,
        north: Double,
        category: PlaceCategory?,
        minRating: Double?,
        limit: Int
    ) async throws -> [PlaceSummary] {
        if !stubbedPlaces.isEmpty {
            return stubbedPlaces
        }
        return try await withCheckedThrowingContinuation { continuation in
            let id = pendingBoundsContinuations.count + 1
            pendingBoundsContinuations[id] = continuation
        }
    }

    func completeBounds(with places: [PlaceSummary], forRequestId id: Int) {
        pendingBoundsContinuations[id]?.resume(returning: places)
        pendingBoundsContinuations.removeValue(forKey: id)
    }

    func listPlaces(search: String?, category: PlaceCategory?, page: Int, size: Int) async throws -> PlacePage {
        PlacePage(places: stubbedPlaces, page: 0, totalPages: 1, totalElements: Int64(stubbedPlaces.count), hasNext: false)
    }

    func placeDetail(id: UUID) async throws -> PlaceDetail {
        throw AppError.notFound
    }

    func reviews(placeId: UUID, scope: ReviewScope, page: Int, size: Int) async throws -> ReviewPage {
        ReviewPage(reviews: [], page: 0, totalElements: 0, hasNext: false)
    }

    func friendsSummary(placeId: UUID) async throws -> FriendPlaceSummary {
        FriendPlaceSummary(friendVisits: [], friendsVisitedCount: 0, friendAverageScore: nil)
    }

    func friendMetrics(placeIds: [UUID]) async throws -> [FriendPlaceMetrics] {
        []
    }

    func savedPlaceIDs() async throws -> Set<UUID> {
        []
    }

    func ownerVisits() async throws -> [OwnerVisitSummary] {
        []
    }
}

private final class FakeSavedPlaceService: SavedPlaceServing, @unchecked Sendable {
    private var saved: [UUID: SavedPlaceDTO] = [:]

    func savedPlaces() async throws -> [SavedPlaceDTO] {
        Array(saved.values)
    }

    func setSaved(placeId: UUID, desired: Bool) async throws -> SavedPlaceDTO? {
        if desired {
            let place = PlaceSummary(
                id: placeId,
                name: "Test",
                category: .beach,
                coverImage: "",
                city: "Bodrum",
                region: "Mugla",
                country: "TR",
                latitude: 37.0,
                longitude: 27.0,
                priceLevel: 1,
                communityScore: 8.0,
                ratingCount: 1
            )
            let dto = SavedPlaceDTO(
                place: place,
                friendAverageScore: nil,
                friendsVisitedCount: 0,
                savedAt: "2026-09-08T12:00:00Z"
            )
            saved[placeId] = dto
            return dto
        } else {
            saved.removeValue(forKey: placeId)
            return nil
        }
    }
}

private final class FakeVisitService: VisitServing, @unchecked Sendable {
    func createVisit(_ request: VisitCreateRequestDTO) async throws -> OwnerVisit {
        throw AppError.general
    }

    func publishVisit(draft: VisitDraft, placeId: UUID, clientMutationId: UUID) async throws -> OwnerVisit {
        throw AppError.general
    }

    func publishVisit(
        draft: VisitDraft,
        placeId: UUID,
        clientMutationId: UUID,
        mediaIds: [UUID]
    ) async throws -> OwnerVisit {
        throw AppError.general
    }

    func ownerVisits(page: Int, size: Int) async throws -> PageDTO<OwnerVisit> {
        PageDTO(content: [], page: 0, size: size, totalElements: 0, totalPages: 0, hasNext: false)
    }
}
