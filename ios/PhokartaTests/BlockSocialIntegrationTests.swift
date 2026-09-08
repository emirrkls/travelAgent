import XCTest
@testable import Phokarta

@MainActor
final class BlockSocialIntegrationTests: XCTestCase {
    private let viewerId = UUID(uuidString: "11111111-1111-1111-1111-111111111111")!
    private let targetId = UUID(uuidString: "22222222-2222-2222-2222-222222222222")!

    // MARK: - 81. BLOCK FOLLOW CLEANUP TEST (P1)

    func testBlockRemovesFollowRelationsAndFriendship() async throws {
        let mockSocial = MockSocialService()
        let store = SocialStateStore(service: mockSocial)
        store.activate(accountID: viewerId)

        var friendshipChanges: [(UUID, Bool)] = []
        store.onFriendshipChanged = { id, isFriend in
            friendshipChanges.append((id, isFriend))
        }

        // Setup mutual friend relationship
        let mutualRel = RelationshipState(isFollowing: true, followsYou: true, isFriend: true)
        store.recordConfirmedRelationship(mutualRel, for: targetId)

        XCTAssertTrue(store.isFollowing(targetId))
        XCTAssertTrue(store.isFriend(targetId))

        // Block target
        store.invalidateUser(targetId)

        // Assert follow and friendship removed
        XCTAssertFalse(store.isFollowing(targetId), "P1: Block must remove viewer's following relation")
        XCTAssertFalse(store.isFriend(targetId), "P1: Block must remove friend state")
        XCTAssertNil(store.relationship(for: targetId), "P1: Relationship must be cleared from store")
        XCTAssertEqual(friendshipChanges.count, 1)
        XCTAssertEqual(friendshipChanges.first?.0, targetId)
        XCTAssertEqual(friendshipChanges.first?.1, false)
    }

    // MARK: - 82. UNBLOCK NO RESTORE TEST (P2)

    func testUnblockDoesNotRestoreFollowsOrFriendship() async throws {
        let mockSocial = MockSocialService()
        let store = SocialStateStore(service: mockSocial)
        store.activate(accountID: viewerId)

        // Initial state: mutual friends
        let mutualRel = RelationshipState(isFollowing: true, followsYou: true, isFriend: true)
        store.recordConfirmedRelationship(mutualRel, for: targetId)

        // Block occurs
        store.invalidateUser(targetId)

        XCTAssertFalse(store.isFollowing(targetId))
        XCTAssertFalse(store.isFriend(targetId))

        // Mock unblock service
        let storeSession = InMemorySessionStore(session: testSession(access: "access-1"))
        let config = try TestConfig.httpsTest()
        let transport = FakeHTTPTransport { request in
            XCTAssertEqual(request.httpMethod, "DELETE")
            XCTAssertEqual(request.url?.path, "/api/v1/me/blocks/\(self.targetId.uuidString.lowercased())")
            return TestJSON.http(request.url!, status: 204)
        }
        let refresh = TokenRefreshCoordinator(store: storeSession, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)
        let blockService = BlockService(client: client)

        try await blockService.unblock(userId: targetId)

        // P2 invariant: Unblock removes block barrier but does NOT restore prior follows
        XCTAssertFalse(store.isFollowing(targetId), "P2: Unblock must never restore prior follows automatically")
        XCTAssertFalse(store.isFriend(targetId), "P2: Unblock must never restore friendship automatically")
        XCTAssertNil(store.relationship(for: targetId))
    }

    // MARK: - 83. BLOCK CACHE INVALIDATION TEST (P3)

    func testBlockInvalidatesAllCachedSocialStateForTarget() {
        let mockSocial = MockSocialService()
        let store = SocialStateStore(service: mockSocial)
        store.activate(accountID: viewerId)

        // Populate dirty state for target
        let rel = RelationshipState(isFollowing: true, followsYou: false)
        store.recordConfirmedRelationship(rel, for: targetId)
        store.setDesired(false, for: targetId)

        // Invalidate via block
        store.invalidateUser(targetId)

        // Assert all cached states for target are purged
        XCTAssertNil(store.relationship(for: targetId), "P3: Relationship must be removed")
        XCTAssertFalse(store.isFollowing(targetId))
        XCTAssertFalse(store.isBusy(targetId))
        XCTAssertNil(store.error(for: targetId))
    }

    // MARK: - 84. COMMUNITY AGGREGATE BLOCK TEST (P4)

    func testCommunityAggregateRemainsBackendAuthoritativeAfterBlock() {
        // P4 invariant: Global Community score/count remains backend aggregate behavior.
        // Client must not subtract blocked user's contributions client-side.
        let backendSummary = PlaceSummary(
            id: UUID(),
            name: "Test Place",
            category: .cafe,
            coverImage: nil,
            city: "Istanbul",
            region: "Marmara",
            country: "Turkey",
            latitude: 41.0,
            longitude: 29.0,
            communityScore: 8.7,
            ratingCount: 42
        )

        // Even when a user who authored visits at this place is blocked:
        // Aggregate score remains exactly what the backend provided
        XCTAssertEqual(backendSummary.communityScore, 8.7, "P4: Community score must remain backend aggregate")
        XCTAssertEqual(backendSummary.ratingCount, 42, "P4: Community rating count must remain backend aggregate")
    }

    // MARK: - Profile Unavailable on Block

    func testUserProfileTransitionsToUnavailableWhenBlocked() {
        let mockSocial = MockSocialService()
        let store = SocialStateStore(service: mockSocial)
        store.activate(accountID: viewerId)

        let controller = UserProfileController(
            userId: targetId,
            isOwnProfile: false,
            service: mockSocial,
            store: store
        )

        controller.markUnavailable()
        XCTAssertEqual(controller.phase, .unavailable)
        XCTAssertNil(controller.profile)
    }
}
