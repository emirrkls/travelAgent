import XCTest
@testable import Phokarta

@MainActor
final class UserProfileControllerTests: XCTestCase {
    var service: MockSocialService!
    var store: SocialStateStore!
    let ownerID = SocialTestFixtures.aliceID
    let targetID = SocialTestFixtures.bobID

    override func setUp() async throws {
        service = MockSocialService()
        store = SocialStateStore(service: service)
        store.activate(accountID: ownerID)
    }

    func testOwnProfileLoadsOwnerProfileFromService() async throws {
        let owner = SocialTestFixtures.ownerProfile(
            id: ownerID,
            username: "alice",
            displayName: "Alice",
            followerCount: 25,
            followingCount: 15,
            friendCount: 8
        )
        await service.setOwnerProfile(owner)

        let controller = UserProfileController(
            userId: ownerID,
            isOwnProfile: true,
            service: service,
            store: store
        )

        XCTAssertEqual(controller.phase, .idle)
        await controller.load()

        XCTAssertEqual(controller.phase, .content)
        XCTAssertEqual(controller.ownerProfile?.username, "alice")
        XCTAssertEqual(controller.ownerProfile?.displayName, "Alice")
        XCTAssertEqual(controller.ownerProfile?.followerCount, 25)
    }

    func testOtherUserProfileLoadsPublicProfileWithRelationship() async throws {
        let bob = SocialTestFixtures.publicProfile(
            id: targetID,
            username: "bob",
            displayName: "Bob",
            followerCount: 12,
            followingCount: 8,
            friendCount: 3,
            isFollowing: false,
            followsYou: true
        )
        await service.setPublicProfile(bob)

        let controller = UserProfileController(
            userId: targetID,
            isOwnProfile: false,
            service: service,
            store: store
        )

        await controller.load()

        XCTAssertEqual(controller.phase, .content)
        XCTAssertEqual(controller.profile?.displayName, "Bob")
        XCTAssertEqual(controller.effectiveRelationship?.isFollowing, false)
        XCTAssertEqual(controller.effectiveRelationship?.followsYou, true)
        XCTAssertEqual(controller.effectiveRelationship?.isFriend, false)
    }

    func testNotFoundErrorSetsPhaseToUnavailable() async throws {
        let controller = UserProfileController(
            userId: targetID,
            isOwnProfile: false,
            service: service,
            store: store
        )

        await controller.load()

        XCTAssertEqual(controller.phase, .unavailable)
    }

    func testToggleFollowInControllerUpdatesRelationship() async throws {
        let bob = SocialTestFixtures.publicProfile(
            id: targetID,
            username: "bob",
            displayName: "Bob",
            followerCount: 5,
            isFollowing: false,
            followsYou: true
        )
        await service.setPublicProfile(bob)

        let controller = UserProfileController(
            userId: targetID,
            isOwnProfile: false,
            service: service,
            store: store
        )

        await controller.load()
        XCTAssertEqual(controller.effectiveRelationship?.isFollowing, false)

        controller.toggleFollow()

        // Effective relationship should immediately reflect following and friend
        XCTAssertEqual(controller.effectiveRelationship?.isFollowing, true)
        XCTAssertEqual(controller.effectiveRelationship?.isFriend, true)
    }
}
