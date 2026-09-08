import XCTest
@testable import Phokarta

@MainActor
final class SocialFollowCoordinatorTests: XCTestCase {
    var service: MockSocialService!
    var store: SocialStateStore!
    let ownerID = SocialTestFixtures.aliceID
    let targetID = SocialTestFixtures.bobID
    private var friendshipChangedTarget: UUID?
    private var friendshipWasFriend: Bool?

    override func setUp() async throws {
        service = MockSocialService()
        store = SocialStateStore(service: service)
        store.activate(accountID: ownerID)
        friendshipChangedTarget = nil
        friendshipWasFriend = nil
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
        store.recordConfirmedProfile(targetProfile)
        XCTAssertEqual(store.isFollowing(targetID), false)
        XCTAssertEqual(store.relationship(for: targetID)?.followsYou, true)
        XCTAssertEqual(store.isFriend(targetID), false)

        store.onFriendshipChanged = { [weak self] id, isFriend in
            self?.friendshipChangedTarget = id
            self?.friendshipWasFriend = isFriend
        }

        // Toggle follow (optimistic)
        store.toggleFollow(targetId: targetID)

        // Overlay state must immediately and definitely be following + friend
        XCTAssertEqual(store.isFollowing(targetID), true)
        let immediateRel = store.relationship(for: targetID)
        XCTAssertEqual(immediateRel?.isFollowing, true)
        XCTAssertEqual(immediateRel?.followsYou, true)
        XCTAssertEqual(immediateRel?.isFriend, true)
        XCTAssertEqual(store.isFriend(targetID), true)

        // Effective follower count reflects delta
        let effectiveCount = store.effectiveFollowerCount(baseCount: 5, for: targetID)
        XCTAssertEqual(effectiveCount, 6)

        // Wait for mutation loop to finish
        await store.waitForMutation(of: targetID)

        // Friendship callback fired
        XCTAssertEqual(friendshipChangedTarget, targetID)
        XCTAssertEqual(friendshipWasFriend, true)

        // Backend follow service was called exactly once
        let followCount = await service.followCallCount
        let unfollowCount = await service.unfollowCallCount
        XCTAssertEqual(followCount, 1)
        XCTAssertEqual(unfollowCount, 0)
    }

    func testRapidIntentRaceMergesToLatestIntentDeterministically() async throws {
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
        store.recordConfirmedProfile(targetProfile)

        // Gate the backend to simulate in-flight execution
        let gate = TestGate()
        await service.setGate(gate)

        // User rapidly taps: Follow -> Unfollow -> Follow
        store.toggleFollow(targetId: targetID) // Follow
        store.toggleFollow(targetId: targetID) // Unfollow
        store.toggleFollow(targetId: targetID) // Follow

        // Local state should already optimistically reflect final intent (following: true)
        XCTAssertEqual(store.isFollowing(targetID), true)

        // Open the gate so backend work proceeds
        await gate.open()
        await store.waitForMutation(of: targetID)

        // Final state is strictly following
        let finalRel = store.relationship(for: targetID)
        XCTAssertEqual(finalRel?.isFollowing, true)

        // Effective follower count reflects +1 delta
        let finalCount = store.effectiveFollowerCount(baseCount: 10, for: targetID)
        XCTAssertEqual(finalCount, 11)
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
        store.recordConfirmedProfile(targetProfile)

        // Configure service to throw server error
        await service.setError(AppError.server)

        store.toggleFollow(targetId: targetID)
        XCTAssertEqual(store.isFollowing(targetID), true)

        await store.waitForMutation(of: targetID)

        // Overlay should be rolled back to not following
        XCTAssertEqual(store.isFollowing(targetID), false)
        XCTAssertEqual(store.error(for: targetID), .server)

        // Follower count delta should be cleared
        let count = store.effectiveFollowerCount(baseCount: 10, for: targetID)
        XCTAssertEqual(count, 10)
    }

    func testStaleProfileRefreshDoesNotOverwriteLocalOptimisticMutation() async throws {
        let targetProfile = SocialTestFixtures.publicProfile(
            id: targetID,
            username: "bob",
            displayName: "Bob",
            followerCount: 10,
            isFollowing: false,
            followsYou: false
        )
        await service.setPublicProfile(targetProfile)
        store.recordConfirmedProfile(targetProfile)

        // Start mutation loop to follow target
        store.toggleFollow(targetId: targetID)
        await store.waitForMutation(of: targetID)
        XCTAssertEqual(store.isFollowing(targetID), true)

        // Simulate a stale background profile fetch returning isFollowing: false with older revision (revision 0)
        store.recordConfirmedProfile(targetProfile, requestRevision: 0)

        // The local mutation must NOT be overwritten!
        XCTAssertEqual(store.isFollowing(targetID), true)
    }

    func testSelfFollowIsRejectedWithoutMutation() async throws {
        // Owner attempts to follow own account
        store.setDesired(true, for: ownerID)

        // Must report validation error and not schedule mutation
        XCTAssertNotNil(store.error(for: ownerID))
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
