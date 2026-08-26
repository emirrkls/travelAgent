import XCTest
@testable import Phokarta

final class PlaceDecodingTests: XCTestCase {
    func testPlaceSummaryPagePreservesNullableCommunityScore() throws {
        let page = try APIJSON.decoder.decode(PageDTO<PlaceSummary>.self, from: Data(TestPlaces.summaryPageJSON.utf8))
        let place = try XCTUnwrap(page.content.first)
        XCTAssertEqual(place.id, TestPlaces.placeID)
        XCTAssertEqual(place.category, .beach)
        XCTAssertNil(place.communityScore)
        XCTAssertEqual(place.ratingCount, 0)
        XCTAssertEqual(place.latitude, 36.8969, accuracy: 0.0001)
        XCTAssertFalse(page.hasNext)
    }

    func testUnknownCategoryDoesNotFailDecoding() throws {
        let json = TestPlaces.summaryPageJSON.replacingOccurrences(of: "\"BEACH\"", with: "\"SPACEPORT\"")
        let page = try APIJSON.decoder.decode(PageDTO<PlaceSummary>.self, from: Data(json.utf8))
        XCTAssertEqual(page.content.first?.category, .unknown)
    }

    func testPlaceDetailMapsAverageScoreToCommunityAggregate() throws {
        let detail = try APIJSON.decoder.decode(PlaceDetail.self, from: Data(TestPlaces.detailJSON.utf8))
        XCTAssertEqual(detail.communityScore, 8.7)
        XCTAssertEqual(detail.ratingCount, 4)
        XCTAssertEqual(detail.dimensionScores.first?.key, "SEA")
        XCTAssertEqual(detail.recentPublicReviews.count, 1)
        XCTAssertEqual(detail.recentPublicReviews.first?.publicReview, "Visible review")
        XCTAssertFalse(TestPlaces.detailJSON.contains("privateMemory"))
    }

    func testMissingOptionalReviewFields() throws {
        let json = """
        {"id":"30000000-0000-0000-0000-000000000001","placeId":"20000000-0000-0000-0000-000000000001","placeName":"Fixture Beach","userId":"11111111-1111-1111-1111-111111111111","username":"demo","displayName":"Demo User","visitedAt":"2026-08-22","overallRating":8.0}
        """
        let review = try APIJSON.decoder.decode(ReviewSummary.self, from: Data(json.utf8))
        XCTAssertEqual(review.publicReview, "")
        XCTAssertTrue(review.photos.isEmpty)
        XCTAssertNil(review.avatarUrl)
    }

    func testFriendsSummaryOmitsNullAverage() throws {
        let json = """
        {"friendsVisitedCount":0,"friends":[]}
        """
        let summary = try APIJSON.decoder.decode(FriendPlaceSummary.self, from: Data(json.utf8))
        XCTAssertNil(summary.averageScore)
        XCTAssertEqual(summary.friendsVisitedCount, 0)
    }

    func testFriendsSummaryDecodesPreview() throws {
        let summary = try APIJSON.decoder.decode(FriendPlaceSummary.self, from: Data(TestPlaces.friendsSummaryJSON.utf8))
        XCTAssertEqual(summary.averageScore, 8.2)
        XCTAssertEqual(summary.friends.first?.displayName, "Ada")
    }

    func testFriendMetricsOmitsZeroCountScore() throws {
        let json = """
        [{"placeId":"20000000-0000-0000-0000-000000000001","friendsVisitedCount":0}]
        """
        let metrics = try APIJSON.decoder.decode([FriendPlaceMetrics].self, from: Data(json.utf8))
        XCTAssertNil(metrics.first?.friendAverageScore)
        XCTAssertEqual(metrics.first?.friendsVisitedCount, 0)
    }

    func testOwnerVisitDecodingDoesNotExposePrivateMemory() throws {
        let page = try APIJSON.decoder.decode(PageDTO<OwnerVisitDTO>.self, from: Data(TestPlaces.ownerVisitJSON.utf8))
        let visit = try XCTUnwrap(page.content.first)
        XCTAssertEqual(visit.overallRating, 9.0)
        let mirrored = Mirror(reflecting: visit).children.map { $0.label }
        XCTAssertFalse(mirrored.contains("privateMemory"))
        XCTAssertEqual(visit.place.id, TestPlaces.placeID)
    }

    func testScoreFormattingUsesOneDecimal() {
        XCTAssertEqual(ScoreFormatting.display(8.7), NumberFormatter.localizedDecimal(8.7))
        XCTAssertEqual(ScoreFormatting.display(9), NumberFormatter.localizedDecimal(9.0))
    }

    func testCategoryWireValueIsBackendEnum() {
        XCTAssertEqual(PlaceCategory.beach.wireValue, "BEACH")
        XCTAssertEqual(PlaceCategory.restaurant.wireValue, "RESTAURANT")
        XCTAssertNil(PlaceCategory.unknown.wireValue)
    }
}

private extension NumberFormatter {
    static func localizedDecimal(_ value: Double) -> String {
        let formatter = NumberFormatter()
        formatter.locale = .current
        formatter.numberStyle = .decimal
        formatter.minimumFractionDigits = 1
        formatter.maximumFractionDigits = 1
        return formatter.string(from: NSNumber(value: value)) ?? ""
    }
}
