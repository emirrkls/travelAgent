import XCTest
@testable import Phokarta

@MainActor
final class ExperienceDiscoveryTests: XCTestCase {
    func testFeedSummaryDecodesCursorMediaDistanceAndOneWayRelationship() throws {
        let page = try APIJSON.decoder.decode(
            CursorPageDTO<ExperienceSummaryV2>.self,
            from: Data(Self.feedFixture.utf8)
        )

        XCTAssertEqual(page.nextCursor, "opaque-next")
        XCTAssertTrue(page.hasMore)
        XCTAssertEqual(page.items.first?.mediaCount, 2)
        XCTAssertEqual(page.items.first?.place.distanceMeters, 1_250.5)
        XCTAssertEqual(page.items.first?.author.relationship?.state, .following)
        XCTAssertFalse(page.items.first?.author.relationship?.isFriend ?? true)
        XCTAssertFalse(Mirror(reflecting: page.items[0]).children.compactMap(\.label).contains("privateMemory"))
    }

    func testFeedEndpointEncodesAllFourLensesAndNearbyCoordinates() throws {
        let config = try TestConfig.httpsTest()
        let client = APIClient(config: config, transport: FakeHTTPTransport { request in
            TestJSON.http(request.url!, status: 200, data: Data())
        })

        XCTAssertEqual(ExperienceFeedLens.allCases.map(\.rawValue), ["FOR_YOU", "FOLLOWING", "NEARBY", "POPULAR"])
        XCTAssertEqual(ExperienceDiscoveryFilter.calm.vibe, .calm)
        XCTAssertEqual(ExperienceDiscoveryFilter.sunset.primary, .gunBatimi)
        let request = try client.makeRequest(ExperienceFeedEndpoint(
            lens: .nearby,
            cursor: "opaque",
            size: 20,
            query: "sunset",
            primary: .gunBatimi,
            vibe: .calm,
            latitude: 41.0,
            longitude: 29.0,
            radiusMeters: 25_000
        ))
        let components = URLComponents(url: try XCTUnwrap(request.url), resolvingAgainstBaseURL: false)
        let query = Dictionary(uniqueKeysWithValues: try XCTUnwrap(components?.queryItems).map { ($0.name, $0.value ?? "") })
        XCTAssertEqual(query["lens"]!, "NEARBY")
        XCTAssertEqual(query["lat"]!, "41.0")
        XCTAssertEqual(query["lon"]!, "29.0")
        XCTAssertEqual(query["radiusMeters"]!, "25000.0")
    }

    func testControllerDefaultsToForYouAndPaginatesWithoutDuplicates() async throws {
        let first = try Self.page(id: Self.firstID, cursor: "next", hasMore: true)
        let second = try Self.page(id: Self.firstID, extraID: Self.secondID, cursor: nil, hasMore: false)
        let service = SequenceExperienceService(pages: [first, second])
        let controller = ExperienceExploreController(
            service: service,
            privacy: ThrowingPrivacyService(),
            location: MockLocationService()
        )

        XCTAssertEqual(controller.lens, .forYou)
        controller.start()
        try await eventually { controller.items.count == 1 }
        controller.loadMoreIfNeeded(current: controller.items[0])
        try await eventually { controller.items.count == 2 }

        XCTAssertEqual(controller.items.map(\.id), [Self.firstID, Self.secondID])
        XCTAssertFalse(controller.hasMore)
    }

    func testStaleForYouResponseCannotOverwritePopularLens() async throws {
        let service = DelayedExperienceService()
        let controller = ExperienceExploreController(
            service: service,
            privacy: ThrowingPrivacyService(),
            location: MockLocationService()
        )

        controller.start()
        controller.selectLens(.popular)
        try await eventually { controller.items.first?.id == Self.secondID }
        try await Task.sleep(for: .milliseconds(250))

        XCTAssertEqual(controller.lens, .popular)
        XCTAssertEqual(controller.items.map(\.id), [Self.secondID])
    }

    private func eventually(
        timeoutNanoseconds: UInt64 = 1_000_000_000,
        condition: @escaping @MainActor () -> Bool
    ) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .nanoseconds(Int64(timeoutNanoseconds)))
        while !condition() {
            if clock.now >= deadline {
                XCTFail("Timed out waiting for controller state")
                return
            }
            try await Task.sleep(for: .milliseconds(10))
        }
    }

    fileprivate static func page(
        id: UUID,
        extraID: UUID? = nil,
        cursor: String?,
        hasMore: Bool
    ) throws -> CursorPageDTO<ExperienceSummaryV2> {
        var items = [try summary(id: id)]
        if let extraID { items.append(try summary(id: extraID)) }
        return CursorPageDTO(items: items, nextCursor: cursor, hasMore: hasMore)
    }

    fileprivate static func summary(id: UUID) throws -> ExperienceSummaryV2 {
        let json = feedFixture.replacingOccurrences(of: firstID.uuidString.lowercased(), with: id.uuidString.lowercased())
        return try APIJSON.decoder.decode(
            CursorPageDTO<ExperienceSummaryV2>.self,
            from: Data(json.utf8)
        ).items[0]
    }

    fileprivate static let firstID = UUID(uuidString: "30000000-0000-0000-0000-000000000001")!
    fileprivate static let secondID = UUID(uuidString: "30000000-0000-0000-0000-000000000002")!
    fileprivate static let feedFixture = """
    {
      "items": [{
        "id": "30000000-0000-0000-0000-000000000001",
        "classification": "NATIVE_V2",
        "author": {
          "id": "11111111-1111-1111-1111-111111111111",
          "username": "author",
          "displayName": "Author",
          "avatarUrl": null,
          "relationship": {
            "state": "FOLLOWING",
            "followsYou": false,
            "canFollow": false,
            "canCancelRequest": false
          }
        },
        "place": {
          "id": "20000000-0000-0000-0000-000000000001",
          "name": "Foça",
          "category": "BEACH",
          "city": "İzmir",
          "region": "Aegean",
          "country": "Türkiye",
          "coverImage": "https://images.test/cover.jpg",
          "distanceMeters": 1250.5
        },
        "experiencedAt": "2026-09-16",
        "title": "A sunset",
        "titleSource": "GENERATED",
        "primaryExperience": {
          "code": "GUN_BATIMI",
          "canonical": true,
          "family": "SCENERY_AND_MOMENT",
          "rawLabel": null
        },
        "feeling": "GUZELDI",
        "storyPreview": "Preview only",
        "tipPreview": "Arrive early",
        "companion": "FRIENDS",
        "timeOfDay": "EVENING",
        "vibes": ["CALM"],
        "practicalSignals": ["ARRIVE_EARLY"],
        "mediaPreview": {
          "kind": "MANAGED",
          "id": "40000000-0000-0000-0000-000000000001",
          "url": "https://media.test/signed",
          "accessExpiresAt": "2026-09-16T12:00:00Z"
        },
        "mediaCount": 2,
        "visibility": "PUBLIC",
        "privateMemory": "must be ignored"
      }],
      "nextCursor": "opaque-next",
      "hasMore": true
    }
    """
}

private actor SequenceExperienceService: ExperienceDiscoveryServing {
    private var pages: [CursorPageDTO<ExperienceSummaryV2>]

    init(pages: [CursorPageDTO<ExperienceSummaryV2>]) { self.pages = pages }

    func feed(
        lens: ExperienceFeedLens,
        cursor: String?,
        query: String?,
        primary: PrimaryExperienceCode?,
        vibe: VibeCode?,
        latitude: Double?,
        longitude: Double?,
        radiusMeters: Double?
    ) async throws -> CursorPageDTO<ExperienceSummaryV2> {
        pages.removeFirst()
    }

    func experience(id: UUID) async throws -> ExperienceV2 { throw AppError.server }
    func placeExperiences(placeId: UUID, cursor: String?, primary: PrimaryExperienceCode?) async throws -> CursorPageDTO<ExperienceSummaryV2> { throw AppError.server }
    func profileExperiences(userId: UUID, cursor: String?) async throws -> CursorPageDTO<ExperienceSummaryV2> { throw AppError.server }
    func renewMedia(id: UUID) async throws -> MediaAccessResponse { throw AppError.server }
}

private actor DelayedExperienceService: ExperienceDiscoveryServing {
    func feed(
        lens: ExperienceFeedLens,
        cursor: String?,
        query: String?,
        primary: PrimaryExperienceCode?,
        vibe: VibeCode?,
        latitude: Double?,
        longitude: Double?,
        radiusMeters: Double?
    ) async throws -> CursorPageDTO<ExperienceSummaryV2> {
        if lens == .forYou { try await Task.sleep(for: .milliseconds(200)) }
        return try ExperienceDiscoveryTests.page(
            id: lens == .popular ? ExperienceDiscoveryTests.secondID : ExperienceDiscoveryTests.firstID,
            cursor: nil,
            hasMore: false
        )
    }

    func experience(id: UUID) async throws -> ExperienceV2 { throw AppError.server }
    func placeExperiences(placeId: UUID, cursor: String?, primary: PrimaryExperienceCode?) async throws -> CursorPageDTO<ExperienceSummaryV2> { throw AppError.server }
    func profileExperiences(userId: UUID, cursor: String?) async throws -> CursorPageDTO<ExperienceSummaryV2> { throw AppError.server }
    func renewMedia(id: UUID) async throws -> MediaAccessResponse { throw AppError.server }
}

private struct ThrowingPrivacyService: PrivacyV2Serving {
    func capabilities() async throws -> CapabilitiesV2 { throw AppError.server }
    func profile(userId: UUID) async throws -> ProfileV2 { throw AppError.server }
    func follow(userId: UUID) async throws -> RelationshipV2 { throw AppError.server }
    func unfollow(userId: UUID) async throws -> RelationshipV2 { throw AppError.server }
    func cancelFollowRequest(userId: UUID) async throws -> RelationshipV2 { throw AppError.server }
    func updateProfileVisibility(_ visibility: ProfileVisibilityV2) async throws -> ProfileV2 { throw AppError.server }
    func followRequests(page: Int, size: Int) async throws -> SocialPageResponse<FollowRequestV2> { throw AppError.server }
    func approve(requestId: UUID) async throws -> RelationshipV2 { throw AppError.server }
    func reject(requestId: UUID) async throws -> RelationshipV2 { throw AppError.server }
    func placeAggregate(placeId: UUID) async throws -> PlaceAggregateV2 { throw AppError.server }
}
