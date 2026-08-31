import XCTest
@testable import Phokarta

@MainActor
final class PlaceDetailControllerTests: XCTestCase {
    func testLoadSuccessUsesBackendCommunityAggregate() async throws {
        let service = FakePlaceService()
        let lowReviews = [
            TestPlaces.review(id: UUID(), rating: 1.0, text: "a"),
            TestPlaces.review(id: UUID(), rating: 2.0, text: "b"),
        ]
        service.detailHandler = { _ in
            TestPlaces.detail(communityScore: 8.7, ratingCount: 40, reviews: lowReviews)
        }
        service.reviewsHandler = { _, scope, _, _ in
            ReviewPage(reviews: lowReviews, page: 0, totalElements: 2, hasNext: false)
        }
        service.friendsHandler = { _ in
            FriendPlaceSummary(averageScore: 7.2, friendsVisitedCount: 2, friends: [])
        }
        service.savedIDs = [TestPlaces.placeID]
        service.visits = [
            OwnerVisitSummary(id: UUID(), placeId: TestPlaces.placeID, visitedAt: "2026-08-21", overallRating: 9.4),
        ]
        let controller = PlaceDetailController(placeId: TestPlaces.placeID, places: service)
        controller.startIfNeeded()
        await settleDetail()
        for _ in 0..<40 where controller.content?.personal == nil {
            await Task.yield()
        }
        let content = try XCTUnwrap(controller.content)
        XCTAssertEqual(controller.phase, .content)
        XCTAssertEqual(content.communityScore, 8.7)
        XCTAssertNotEqual(content.communityScore, (1.0 + 2.0) / 2.0)
        XCTAssertEqual(content.friends?.averageScore, 7.2)
        XCTAssertEqual(content.personal?.latestScore, 9.4)
        XCTAssertTrue(content.isSaved)
        XCTAssertEqual(content.communityReviews.count, 2)
    }

    func testCommunityScoreIsNotRecomputedFromVisibleReviews() {
        let content = PlaceDetailContent(
            place: TestPlaces.detail(communityScore: 8.7, ratingCount: 40, reviews: [
                TestPlaces.review(rating: 1.0),
                TestPlaces.review(id: UUID(), rating: 2.0),
            ]),
            friends: nil,
            communityReviews: [
                TestPlaces.review(rating: 1.0),
                TestPlaces.review(id: UUID(), rating: 2.0),
            ],
            friendReviews: [],
            isSaved: false,
            personal: nil
        )
        XCTAssertEqual(content.communityScore, 8.7)
        let visibleAverage = content.communityReviews.map(\.overallRating).reduce(0, +) / Double(content.communityReviews.count)
        XCTAssertNotEqual(content.communityScore, visibleAverage)
    }

    func testNotFoundIsUnavailable() async {
        let service = FakePlaceService()
        service.detailHandler = { _ in throw AppError.notFound }
        let controller = PlaceDetailController(placeId: TestPlaces.placeID, places: service)
        controller.startIfNeeded()
        await settleDetail()
        XCTAssertEqual(controller.phase, .unavailable)
        XCTAssertNil(controller.content)
    }

    func testForbiddenBlockAwareIsGenericUnavailable() async {
        let service = FakePlaceService()
        service.detailHandler = { _ in throw AppError.forbidden }
        let controller = PlaceDetailController(placeId: TestPlaces.placeID, places: service)
        controller.startIfNeeded()
        await settleDetail()
        XCTAssertEqual(controller.phase, .unavailable)
    }

    func testNetworkError() async {
        let service = FakePlaceService()
        service.detailHandler = { _ in throw AppError.networkUnavailable }
        let controller = PlaceDetailController(placeId: TestPlaces.placeID, places: service)
        controller.startIfNeeded()
        await settleDetail()
        XCTAssertEqual(controller.phase, .error(.network))
    }

    func testNullableCommunityScore() async {
        let service = FakePlaceService()
        service.detailHandler = { _ in TestPlaces.detail(communityScore: nil, ratingCount: 0) }
        let controller = PlaceDetailController(placeId: TestPlaces.placeID, places: service)
        controller.startIfNeeded()
        await settleDetail()
        XCTAssertNil(controller.content?.communityScore)
    }

    func testRefreshReloadsDetail() async {
        let service = FakePlaceService()
        let score = TestValue(8.0)
        service.detailHandler = { _ in TestPlaces.detail(communityScore: await score.read()) }
        let controller = PlaceDetailController(placeId: TestPlaces.placeID, places: service)
        controller.startIfNeeded()
        await settleDetail()
        await score.set(9.1)
        await controller.refresh()
        XCTAssertEqual(controller.content?.communityScore, 9.1)
        XCTAssertEqual(controller.phase, .content)
    }

    func testStartIfNeededDoesNotDoubleLoad() async {
        let service = FakePlaceService()
        let loads = TestCounter()
        service.detailHandler = { _ in
            await loads.increment()
            return TestPlaces.detail()
        }
        let controller = PlaceDetailController(placeId: TestPlaces.placeID, places: service)
        controller.startIfNeeded()
        controller.startIfNeeded()
        await settleDetail()
        let loadCount = await loads.read()
        XCTAssertEqual(loadCount, 1)
    }
}

@MainActor
private func settleDetail() async {
    for _ in 0..<12 {
        await Task.yield()
    }
}
