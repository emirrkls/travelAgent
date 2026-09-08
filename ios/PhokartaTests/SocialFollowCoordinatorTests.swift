import XCTest
@testable import Phokarta

@MainActor
final class SocialFollowCoordinatorTests: XCTestCase {
    var service: MockSocialService!
    var store: SocialStateStore!
    let ownerID = SocialTestFixtures.aliceID
    let targetID = SocialTestFixtures.bobID

    override func setUp() async throws {
        service = MockSocialService()
        store = SocialStateStore(service: service)
        store.activate(accountID: ownerID)
    }

    func testInitialFollowAndMutualFriendshipTransition() async throws {
        // Target follows you, but you are not following target yet
        let targetProfile = SocialTestFixtures.publicProfile(
            id: targetID,
            username: "bob",
            displayName: "Bob",
            followerCount: 5,
            followingCount: 10,
            friendCount: 1,
            isFollowing: false,
            followsYou: true
        )
        await service.setPublicProfile(targetProfile)

        // Seed target profile into store
        let fetched = try await store.refreshPublicProfile(id: targetID)
        XCTAssertEqual(fetched.relationship.isFollowing, false)
        XCTAssertEqual(fetched.relationship.followsYou, true)
        XCTAssertEqual(fetched.relationship.isFriend, false)

        var friendshipChangedTarget: UUID?
        var friendshipWasFriend: Bool?
        store.onFriendshipChanged = { id, isFriend in
            friendshipChangedTarget = id
            friendshipWasFriend = isFriend
        }

        // Toggle follow
        try await store.toggleFollow(targetUserId: targetID)

        // Overlay state must immediately and definitely be following + friend
        let state = store.relationship(for: targetID)
        XCTAssertEqual(state?.isFollowing, true)
        XCTAssertEqual(state?.followsYou, true)
        XCTAssertEqual(state?.isFriend, true)

        // Friendship callback fired
        XCTAssertEqual(friendshipChangedTarget, targetID)
        XCTAssertEqual(friendshipWasFriend, true)

        // Target profile in store has incremented follower count and updated friendCount
        let updatedProfile = store.cachedProfile(for: targetID)
        XCTAssertEqual(updatedProfile?.followerCount, 6)
        XCTAssertEqual(updatedProfile?.friendCount, 2)

        // Backend follow service was called exactly once
        let followCount = await service.followCallCount
        let unfollowCount = await service.unfollowCallCount
        XCTAssertEqual(followCount, 1)
        XCTAssertEqual(unfollowCount, 0)
    }

    func testRapidIntentRaceMergesToLatestIntentDeterministically() async throws {
        // Target profile initially not followed
        let targetProfile = SocialTestFixtures.publicProfile(
            id: targetID,
            username: "bob",
            displayName: "Bob",
            followerCount: 10,
            followingCount: 5,
            friendCount: 0,
            isFollowing: false,
            followsYou: false
        )
        await service.setPublicProfile(targetProfile)
        _ = try await store.refreshPublicProfile(id: targetID)

        // Gate the backend to simulate in-flight execution
        let gate = TestGate()
        await service.setGate(gate)

        // User rapidly taps: Follow -> Unfollow -> Follow
        // First toggle: Follow (starts in-flight request)
        let t1 = Task { try await store.toggleFollow(targetUserId: targetID) }
        // Immediate second toggle: Unfollow
        let t2 = Task { try await store.toggleFollow(targetUserId: targetID) }
        // Immediate third toggle: Follow
        let t3 = Task { try await store.toggleFollow(targetUserId: targetID) }

        // Local state should already optimistically reflect final intent (following: true)
        let immediateRel = store.relationship(for: targetID)
        XCTAssertEqual(immediateRel?.isFollowing, true)

        // Open the gate so backend work proceeds
        await gate.open()

        try await t1.value
        try await t2.value
        try await t3.value

        // Final state is strictly following
        let finalRel = store.relationship(for: targetID)
        XCTAssertEqual(finalRel?.isFollowing, true)

        // Follower count increased from 10 to 11, without duplicate drift
        let cached = store.cachedProfile(for: targetID)
        XCTAssertEqual(cached?.followerCount, 11)
    }

    func testRollbackOnErrorRestoresPreviousStateAndCount() async throws {
        let targetProfile = SocialTestFixtures.publicProfile(
            id: targetID,
            username: "bob",
            displayName: "Bob",
            followerCount: 10,
            followingCount: 5,
            friendCount: 0,
            isFollowing: false,
            followsYou: false
        )
        await service.setPublicProfile(targetProfile)
        _ = try await store.refreshPublicProfile(id: targetID)

        // Configure service to throw server error
        await service.setError(AppError.server)

        do {
            try await store.toggleFollow(targetUserId: targetID)
            XCTFail("Expected toggleFollow to throw")
        } catch {
            // Expected
        }

        // Overlay should be rolled back to not following
        let rel = store.relationship(for: targetID)
        XCTAssertEqual(rel?.isFollowing, false)

        // Follower count should remain 10 (no drift)
        let cached = store.cachedProfile(for: targetID)
        XCTAssertEqual(cached?.followerCount, 10)
    }

    func testStaleProfileRefreshDoesNotOverwriteLocalOptimisticMutation() async throws {
        let staleBackendProfile = SocialTestFixtures.publicProfile(
            id: targetID,
            username: "bob",
            displayName: "Bob",
            followerCount: 10,
            followingCount: 5,
            friendCount: 0,
            isFollowing: false,
            followsYou: false
        )
        await service.setPublicProfile(staleBackendProfile)
        _ = try await store.refreshPublicProfile(id: targetID)

        // Follow target locally
        try await store.toggleFollow(targetUserId: targetID)
        XCTAssertEqual(store.relationship(for: targetID)?.isFollowing, true)

        // Backend still returns isFollowing: false (e.g. read replica lag)
        let refreshed = try await store.refreshPublicProfile(id: targetID)

        // The returned profile from store must have local overlay applied!
        XCTAssertEqual(refreshed.relationship.isFollowing, true)
        XCTAssertEqual(refreshed.followerCount, 11)
    }

    func testSelfFollowIsRejectedWithoutMutation() async throws {
        // Owner attempts to follow own account
        do {
            try await store.toggleFollow(targetUserId: ownerID)
            XCTFail("Self-follow should fail or be a no-op")
        } catch let error as AppError {
            XCTAssertEqual(error, .badRequest)
        } catch {
            XCTFail("Unexpected error: \(error)")
        }

        let followCount = await service.followCallCount
        XCTAssertEqual(followCount, 0)
    }
}

private extension MockSocialService {
    func setGate(_ gate: TestGate) {
        self.gateBeforeResponse = gate
    }
    func setError(_ error: AppError?) {
        self.errorToThrow = error
    }
}
