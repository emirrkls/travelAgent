import XCTest
@testable import Phokarta

@MainActor
final class SocialAccountIsolationTests: XCTestCase {
    var service: MockSocialService!
    let accountA = SocialTestFixtures.aliceID
    let accountB = SocialTestFixtures.bobID
    let targetUser = SocialTestFixtures.charlieID

    override func setUp() async throws {
        service = MockSocialService()
    }

    func testAccountSwitchClearsAllOverlaysAndCaches() async throws {
        let store = SocialStateStore(service: service)

        // 1. Activate for Account A
        store.activate(accountID: accountA)
        XCTAssertEqual(store.accountID, accountA)

        let targetProfile = SocialTestFixtures.publicProfile(
            id: targetUser,
            username: "charlie",
            displayName: "Charlie",
            isFollowing: false,
            followsYou: false
        )
        await service.setPublicProfile(targetProfile)

        // Follow target user as Account A
        store.recordConfirmedProfile(targetProfile)
        store.toggleFollow(targetId: targetUser)
        await store.waitForMutation(of: targetUser)

        XCTAssertEqual(store.isFollowing(targetUser), true)
        XCTAssertEqual(store.relationship(for: targetUser)?.isFollowing, true)

        // 2. Switch account to Account B
        store.activate(accountID: accountB)
        XCTAssertEqual(store.accountID, accountB)

        // All previous overlays and caches must be wiped clean!
        XCTAssertNil(store.relationship(for: targetUser))
        XCTAssertNil(store.ownerProfile)
        XCTAssertEqual(store.isFollowing(targetUser), false)
    }

    func testClearOnLogoutWipesEverything() async throws {
        let store = SocialStateStore(service: service)
        store.activate(accountID: accountA)

        let owner = SocialTestFixtures.ownerProfile(id: accountA)
        await service.setOwnerProfile(owner)
        _ = try await store.refreshOwnerProfile()
        XCTAssertNotNil(store.ownerProfile)

        // Terminal auth loss or logout
        store.clear()

        XCTAssertNil(store.accountID)
        XCTAssertNil(store.ownerProfile)
        XCTAssertNil(store.relationship(for: targetUser))
        XCTAssertEqual(store.isFollowing(targetUser), false)
    }
}
