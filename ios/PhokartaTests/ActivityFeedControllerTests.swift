import XCTest
@testable import Phokarta

@MainActor
final class ActivityFeedControllerTests: XCTestCase {
    var socialService: MockSocialService!
    var activityService: MockActivityService!
    var socialState: SocialStateStore!
    let ownerID = SocialTestFixtures.aliceID

    override func setUp() async throws {
        socialService = MockSocialService()
        activityService = MockActivityService()
        socialState = SocialStateStore(service: socialService)
        socialState.activate(accountID: ownerID)
    }

    func testScopeSwitchingLoadsRespectiveFeeds() async throws {
        let event1 = SocialTestFixtures.activityEvent(id: UUID(), score: 9.0, review: "Community visit")
        let event2 = SocialTestFixtures.activityEvent(id: UUID(), score: 8.5, review: "Friend visit")

        await activityService.setPage(
            scope: .community,
            page: 0,
            response: SocialPageResponse(content: [event1], page: 0, size: 20, totalElements: 1, totalPages: 1, last: true)
        )
        await activityService.setPage(
            scope: .friends,
            page: 0,
            response: SocialPageResponse(content: [event2], page: 0, size: 20, totalElements: 1, totalPages: 1, last: true)
        )

        let controller = ActivityFeedController(activityService: activityService, socialState: socialState)
        XCTAssertEqual(controller.scope, .community)

        await controller.load(initial: true)

        XCTAssertEqual(controller.items.count, 1)
        XCTAssertEqual(controller.items.first?.publicReview, "Community visit")

        // Switch to Friends scope
        controller.switchScope(.friends)
        XCTAssertEqual(controller.scope, .friends)

        await controller.load(initial: true)

        XCTAssertEqual(controller.items.count, 1)
        XCTAssertEqual(controller.items.first?.publicReview, "Friend visit")
    }

    func testPaginationAndDeduplicationByVisitUUID() async throws {
        let eventID1 = UUID()
        let eventID2 = UUID()
        let eventID3 = UUID()

        let event1 = SocialTestFixtures.activityEvent(id: eventID1, review: "Event 1")
        let event2 = SocialTestFixtures.activityEvent(id: eventID2, review: "Event 2")
        // Page 1 includes event2 again (e.g. shift due to new post) and event3
        let event2Duplicate = SocialTestFixtures.activityEvent(id: eventID2, review: "Event 2 Dup")
        let event3 = SocialTestFixtures.activityEvent(id: eventID3, review: "Event 3")

        await activityService.setPage(
            scope: .community,
            page: 0,
            response: SocialPageResponse(content: [event1, event2], page: 0, size: 2, totalElements: 3, totalPages: 2, last: false)
        )
        await activityService.setPage(
            scope: .community,
            page: 1,
            response: SocialPageResponse(content: [event2Duplicate, event3], page: 1, size: 2, totalElements: 3, totalPages: 2, last: true)
        )

        let controller = ActivityFeedController(activityService: activityService, socialState: socialState)
        await controller.load(initial: true)
        XCTAssertEqual(controller.items.count, 2)

        // Load next page
        await controller.loadNextPageIfNeeded(currentItem: controller.items.last!)

        // Items should be 3 (event1, event2, event3), duplicate event2 excluded!
        XCTAssertEqual(controller.items.count, 3)
        let ids = controller.items.map(\.id)
        XCTAssertEqual(ids, [eventID1, eventID2, eventID3])
        XCTAssertTrue(controller.hasReachedEnd)
    }

    func testFriendshipChangeInvalidatesFriendsFeedAndReloads() async throws {
        let event = SocialTestFixtures.activityEvent(id: UUID(), review: "Old friend visit")
        await activityService.setPage(
            scope: .friends,
            page: 0,
            response: SocialPageResponse(content: [event], page: 0, size: 20, totalElements: 1, totalPages: 1, last: true)
        )

        let controller = ActivityFeedController(activityService: activityService, socialState: socialState)
        controller.switchScope(.friends)
        await controller.load(initial: true)
        XCTAssertEqual(controller.items.count, 1)

        // Add a new event to friends feed page 0
        let newEvent = SocialTestFixtures.activityEvent(id: UUID(), review: "New friend visit")
        await activityService.setPage(
            scope: .friends,
            page: 0,
            response: SocialPageResponse(content: [newEvent], page: 0, size: 20, totalElements: 1, totalPages: 1, last: true)
        )

        // Notify socialState of friendship change (e.g. newly followed someone who follows you)
        socialState.onFriendshipChanged?(SocialTestFixtures.bobID, true)

        // Since scope is currently .friends, controller should refresh immediately or on load
        await controller.load(initial: true)
        XCTAssertEqual(controller.items.count, 1)
        XCTAssertEqual(controller.items.first?.publicReview, "New friend visit")
    }

    func testStaleGenerationResponsesAreDiscarded() async throws {
        let gate = TestGate()
        await activityService.setGate(gate)

        let slowEvent = SocialTestFixtures.activityEvent(id: UUID(), review: "Slow community")
        await activityService.setPage(
            scope: .community,
            page: 0,
            response: SocialPageResponse(content: [slowEvent], page: 0, size: 20, totalElements: 1, totalPages: 1, last: true)
        )

        let fastEvent = SocialTestFixtures.activityEvent(id: UUID(), review: "Fast friends")
        await activityService.setPage(
            scope: .friends,
            page: 0,
            response: SocialPageResponse(content: [fastEvent], page: 0, size: 20, totalElements: 1, totalPages: 1, last: true)
        )

        let controller = ActivityFeedController(activityService: activityService, socialState: socialState)

        // Start loading community (will pause at gate)
        let t1 = Task { await controller.load(initial: true) }

        // Switch to friends before gate opens
        controller.switchScope(.friends)

        // Open the gate
        await gate.open()
        await t1.value

        // Controller switched to friends scope, so slow community response from generation 1 was dropped
        XCTAssertEqual(controller.scope, .friends)
        XCTAssertTrue(controller.items.isEmpty)
    }

    func testHardPrivacyInvariantNoPrivateMemoryInActivityDTO() throws {
        // Structural privacy verification: ActivityEvent only decodes public fields
        let sampleJSON = """
        {
            "id": "11111111-2222-3333-4444-555555555555",
            "author": {
                "id": "22222222-3333-4444-5555-666666666666",
                "username": "secret_traveler",
                "displayName": "Secret Traveler",
                "avatarUrl": null
            },
            "place": {
                "id": "33333333-4444-5555-6666-777777777777",
                "name": "Secret Spot",
                "category": "BEACH",
                "city": "Bodrum",
                "coverImage": null
            },
            "visitedAt": "2026-09-02T12:00:00Z",
            "overallScore": 9.5,
            "publicReview": "Public thoughts only",
            "privateMemory": "SHOULD NEVER BE EXPOSED"
        }
        """

        let event = try JSONDecoder().decode(ActivityEvent.self, from: Data(sampleJSON.utf8))
        XCTAssertEqual(event.publicReview, "Public thoughts only")
        XCTAssertEqual(event.overallScore, 9.5)

        // Mirror assertion: Ensure ActivityEvent struct has NO field named privateMemory
        let mirror = Mirror(reflecting: event)
        let propertyNames = mirror.children.compactMap(\.label)
        XCTAssertFalse(propertyNames.contains("privateMemory"), "ActivityEvent must never contain privateMemory field!")
    }
}

private extension MockActivityService {
    func setGate(_ gate: TestGate) {
        self.gateBeforeResponse = gate
    }
}
