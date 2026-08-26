import XCTest
@testable import Phokarta

@MainActor
final class ExploreControllerTests: XCTestCase {
    func testInitialLoadContent() async {
        let service = FakePlaceService()
        service.listHandler = { _, _, _, _ in
            TestPlaces.page(places: [TestPlaces.summary()])
        }
        let controller = ExploreController(places: service, debounceNanoseconds: 0)
        controller.startIfNeeded()
        await settle()
        XCTAssertEqual(controller.phase, .content)
        XCTAssertEqual(controller.places.map(\.id), [TestPlaces.placeID])
        XCTAssertEqual(controller.places.first?.summary.communityScore, 8.7)
    }

    func testEmptyCatalog() async {
        let service = FakePlaceService()
        service.listHandler = { _, _, _, _ in TestPlaces.page(places: []) }
        let controller = ExploreController(places: service, debounceNanoseconds: 0)
        controller.startIfNeeded()
        await settle()
        XCTAssertEqual(controller.phase, .empty(.catalog))
    }

    func testEmptySearch() async {
        let service = FakePlaceService()
        service.listHandler = { _, _, _, _ in TestPlaces.page(places: []) }
        let controller = ExploreController(places: service, debounceNanoseconds: 0)
        controller.setQuery("xyz")
        await settle()
        XCTAssertEqual(controller.phase, .empty(.search))
    }

    func testEmptyCategory() async {
        let service = FakePlaceService()
        service.listHandler = { _, _, _, _ in TestPlaces.page(places: []) }
        let controller = ExploreController(places: service, debounceNanoseconds: 0)
        controller.selectCategory(.cafe)
        await settle()
        XCTAssertEqual(controller.phase, .empty(.category))
        XCTAssertEqual(service.listCalls.last?.1, .cafe)
    }

    func testErrorAndRetry() async {
        let service = FakePlaceService()
        var shouldFail = true
        service.listHandler = { _, _, _, _ in
            if shouldFail { throw AppError.networkUnavailable }
            return TestPlaces.page(places: [TestPlaces.summary()])
        }
        let controller = ExploreController(places: service, debounceNanoseconds: 0)
        controller.startIfNeeded()
        await settle()
        XCTAssertEqual(controller.phase, .error(.network))
        shouldFail = false
        controller.retry()
        await settle()
        XCTAssertEqual(controller.phase, .content)
    }

    func testRateLimitDoesNotAutoRetry() async {
        let service = FakePlaceService()
        service.listHandler = { _, _, _, _ in throw AppError.rateLimited }
        let controller = ExploreController(places: service, debounceNanoseconds: 0)
        controller.startIfNeeded()
        await settle()
        XCTAssertEqual(controller.phase, .error(.rateLimited))
        XCTAssertEqual(service.listCalls.count, 1)
    }

    func testCategoryAndQueryAreSentTogether() async throws {
        let service = FakePlaceService()
        service.listHandler = { _, _, _, _ in TestPlaces.page(places: [TestPlaces.summary()]) }
        let controller = ExploreController(places: service, debounceNanoseconds: 0)
        controller.selectCategory(.cafe)
        await settle()
        controller.setQuery("bodrum")
        await settle()
        let last = try XCTUnwrap(service.listCalls.last)
        XCTAssertEqual(last.0, "bodrum")
        XCTAssertEqual(last.1, .cafe)
        XCTAssertEqual(controller.selectedCategory, .cafe)
        XCTAssertEqual(controller.query, "bodrum")
    }

    func testPullRefreshKeepsFilters() async {
        let service = FakePlaceService()
        service.listHandler = { _, _, _, _ in TestPlaces.page(places: [TestPlaces.summary()]) }
        let controller = ExploreController(places: service, debounceNanoseconds: 0)
        controller.selectCategory(.hotel)
        await settle()
        controller.setQuery("cove")
        await settle()
        let callsBefore = service.listCalls.count
        await controller.refresh()
        XCTAssertEqual(controller.selectedCategory, .hotel)
        XCTAssertEqual(controller.query, "cove")
        XCTAssertGreaterThan(service.listCalls.count, callsBefore)
        XCTAssertEqual(service.listCalls.last?.0, "cove")
        XCTAssertEqual(service.listCalls.last?.1, .hotel)
    }

    func testPaginationLoadsNextPageWithoutDuplicates() async throws {
        let service = FakePlaceService()
        service.listHandler = { _, _, page, _ in
            if page == 0 {
                return TestPlaces.page(places: [TestPlaces.summary()], page: 0, hasNext: true)
            }
            return TestPlaces.page(
                places: [TestPlaces.summary(), TestPlaces.summary(id: TestPlaces.cafeID, name: "Cafe")],
                page: 1,
                hasNext: false
            )
        }
        let controller = ExploreController(places: service, debounceNanoseconds: 0)
        controller.startIfNeeded()
        await settle()
        let first = try XCTUnwrap(controller.places.first)
        controller.loadNextPageIfNeeded(current: first)
        controller.loadNextPageIfNeeded(current: first)
        await settle()
        XCTAssertEqual(controller.places.map(\.id), [TestPlaces.placeID, TestPlaces.cafeID])
        XCTAssertFalse(controller.hasNext)
        XCTAssertEqual(service.listCalls.filter { $0.2 == 1 }.count, 1)
    }

    func testQueryChangeDropsInFlightPagination() async {
        let gate = PaginationGate()
        let service = FakePlaceService()
        service.listHandler = { search, _, page, _ in
            if page == 0 {
                return TestPlaces.page(
                    places: [TestPlaces.summary(name: search ?? "first")],
                    page: 0,
                    hasNext: true
                )
            }
            await gate.wait()
            return TestPlaces.page(
                places: [TestPlaces.summary(id: TestPlaces.cafeID, name: "stale-page")],
                page: 1,
                hasNext: false
            )
        }
        let controller = ExploreController(places: service, debounceNanoseconds: 0)
        controller.startIfNeeded()
        await settle()
        controller.loadNextPageIfNeeded(current: controller.places[0])
        await settle()
        controller.setQuery("cafe")
        await gate.resume()
        await settle()
        XCTAssertEqual(controller.places.map(\.summary.name), ["cafe"])
        XCTAssertFalse(controller.places.contains(where: { $0.summary.name == "stale-page" }))
    }

    func testSearchRaceKeepsNewerResults() async {
        let gated = GatedPlaceService()
        let controller = ExploreController(places: gated, debounceNanoseconds: 0)
        controller.setQuery("ca")
        await waitUntil { await gated.recordedSearches.contains("ca") }
        controller.setQuery("cafe")
        await waitUntil { await gated.recordedSearches.contains("cafe") }

        await gated.complete(
            search: "cafe",
            page: TestPlaces.page(places: [TestPlaces.summary(id: TestPlaces.cafeID, name: "Cafe")])
        )
        await settle()
        await gated.complete(
            search: "ca",
            page: TestPlaces.page(places: [TestPlaces.summary(name: "Ca Place")])
        )
        await settle()

        XCTAssertEqual(controller.places.map(\.summary.name), ["Cafe"])
        XCTAssertEqual(controller.query, "cafe")
    }

    func testUnauthorizedDoesNotShowRetryableErrorLoop() async {
        let service = FakePlaceService()
        service.listHandler = { _, _, _, _ in throw AppError.unauthorized }
        let controller = ExploreController(places: service, debounceNanoseconds: 0)
        controller.startIfNeeded()
        await settle()
        XCTAssertEqual(controller.phase, .error(.unauthorized))
        if case .error(let kind) = controller.phase {
            XCTAssertFalse(kind.showsRetry)
        }
    }

    func testEnrichmentUsesBackendFriendMetricsNotReviewAverage() async throws {
        let service = FakePlaceService()
        service.listHandler = { _, _, _, _ in TestPlaces.page(places: [TestPlaces.summary(communityScore: 8.7)]) }
        service.metricsHandler = { ids in
            ids.map { FriendPlaceMetrics(placeId: $0, friendAverageScore: 7.5, friendsVisitedCount: 3) }
        }
        service.savedIDs = [TestPlaces.placeID]
        service.visits = [
            OwnerVisitSummary(id: UUID(), placeId: TestPlaces.placeID, visitedAt: "2026-08-21", overallRating: 9.0),
        ]
        let controller = ExploreController(places: service, debounceNanoseconds: 0)
        controller.startIfNeeded()
        await settle()
        var item = controller.places.first
        for _ in 0..<40 where item?.isSaved != true {
            await Task.yield()
            item = controller.places.first
        }
        let loaded = try XCTUnwrap(item)
        XCTAssertEqual(loaded.summary.communityScore, 8.7)
        XCTAssertEqual(loaded.friendAverageScore, 7.5)
        XCTAssertTrue(loaded.isSaved)
        XCTAssertTrue(loaded.isVisited)
        XCTAssertEqual(loaded.personalScore, 9.0)
    }
}

@MainActor
private func settle() async {
    for _ in 0..<20 {
        await Task.yield()
    }
}

private func waitUntil(
    timeoutNanoseconds: UInt64 = 1_000_000_000,
    condition: @escaping @Sendable () async -> Bool
) async {
    let start = DispatchTime.now().uptimeNanoseconds
    while DispatchTime.now().uptimeNanoseconds - start < timeoutNanoseconds {
        if await condition() { return }
        await Task.yield()
    }
    XCTFail("condition not met")
}

actor PaginationGate {
    private var continuation: CheckedContinuation<Void, Never>?

    func wait() async {
        await withCheckedContinuation { continuation in
            self.continuation = continuation
        }
    }

    func resume() {
        continuation?.resume()
        continuation = nil
    }
}
