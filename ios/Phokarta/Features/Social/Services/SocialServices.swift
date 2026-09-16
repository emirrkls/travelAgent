import Foundation

protocol SocialServing: Sendable {
    func getPublicProfile(userId: UUID) async throws -> PublicUserProfile
    func getMeProfile() async throws -> OwnerUserProfile
    func follow(userId: UUID) async throws
    func unfollow(userId: UUID) async throws
    func searchUsers(query: String, page: Int, size: Int) async throws -> SocialPageResponse<UserSummary>
    func getFollowers(page: Int, size: Int) async throws -> SocialPageResponse<UserSummary>
    func getFollowing(page: Int, size: Int) async throws -> SocialPageResponse<UserSummary>
    func getFriends(page: Int, size: Int) async throws -> SocialPageResponse<UserSummary>
}

protocol ActivityServing: Sendable {
    func getActivity(scope: ActivityScope, page: Int, size: Int) async throws -> SocialPageResponse<ActivityEvent>
}

struct SocialService: SocialServing {
    private let client: APIClient

    init(client: APIClient) {
        self.client = client
    }

    func getPublicProfile(userId: UUID) async throws -> PublicUserProfile {
        try await client.send(PublicProfileEndpoint(userId: userId))
    }

    func getMeProfile() async throws -> OwnerUserProfile {
        try await client.send(MeProfileEndpoint())
    }

    func follow(userId: UUID) async throws {
        _ = try await client.send(FollowUserEndpoint(userId: userId))
    }

    func unfollow(userId: UUID) async throws {
        _ = try await client.send(UnfollowUserEndpoint(userId: userId))
    }

    func searchUsers(query: String, page: Int = 0, size: Int = 20) async throws -> SocialPageResponse<UserSummary> {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return SocialPageResponse(content: [], page: page, size: size, totalElements: 0, totalPages: 0, hasNext: false)
        }
        return try await client.send(SearchUsersEndpoint(query: trimmed, page: page, size: size))
    }

    func getFollowers(page: Int = 0, size: Int = 20) async throws -> SocialPageResponse<UserSummary> {
        try await client.send(FollowersEndpoint(page: page, size: size))
    }

    func getFollowing(page: Int = 0, size: Int = 20) async throws -> SocialPageResponse<UserSummary> {
        try await client.send(FollowingEndpoint(page: page, size: size))
    }

    func getFriends(page: Int = 0, size: Int = 20) async throws -> SocialPageResponse<UserSummary> {
        try await client.send(FriendsEndpoint(page: page, size: size))
    }
}

protocol PrivacyV2Serving: Sendable {
    func capabilities() async throws -> CapabilitiesV2
    func profile(userId: UUID) async throws -> ProfileV2
    func follow(userId: UUID) async throws -> RelationshipV2
    func unfollow(userId: UUID) async throws -> RelationshipV2
    func cancelFollowRequest(userId: UUID) async throws -> RelationshipV2
    func updateProfileVisibility(_ visibility: ProfileVisibilityV2) async throws -> ProfileV2
    func followRequests(page: Int, size: Int) async throws -> SocialPageResponse<FollowRequestV2>
    func approve(requestId: UUID) async throws -> RelationshipV2
    func reject(requestId: UUID) async throws -> RelationshipV2
    func placeAggregate(placeId: UUID) async throws -> PlaceAggregateV2
}

struct PrivacyV2Service: PrivacyV2Serving {
    let client: APIClient

    func capabilities() async throws -> CapabilitiesV2 {
        try await client.send(CapabilitiesV2Endpoint())
    }

    func profile(userId: UUID) async throws -> ProfileV2 {
        try await client.send(ProfileV2Endpoint(userId: userId))
    }

    func follow(userId: UUID) async throws -> RelationshipV2 {
        try await client.send(FollowV2Endpoint(userId: userId))
    }

    func unfollow(userId: UUID) async throws -> RelationshipV2 {
        try await client.send(UnfollowV2Endpoint(userId: userId))
    }

    func cancelFollowRequest(userId: UUID) async throws -> RelationshipV2 {
        try await client.send(CancelFollowRequestV2Endpoint(userId: userId))
    }

    func updateProfileVisibility(_ visibility: ProfileVisibilityV2) async throws -> ProfileV2 {
        try await client.send(UpdateProfileVisibilityV2Endpoint(
            body: ProfileVisibilityUpdateBody(visibility: visibility)
        ))
    }

    func followRequests(page: Int = 0, size: Int = 20) async throws -> SocialPageResponse<FollowRequestV2> {
        try await client.send(FollowRequestsV2Endpoint(page: page, size: size))
    }

    func approve(requestId: UUID) async throws -> RelationshipV2 {
        try await client.send(ResolveFollowRequestV2Endpoint(requestId: requestId, resolution: .approve))
    }

    func reject(requestId: UUID) async throws -> RelationshipV2 {
        try await client.send(ResolveFollowRequestV2Endpoint(requestId: requestId, resolution: .reject))
    }

    func placeAggregate(placeId: UUID) async throws -> PlaceAggregateV2 {
        try await client.send(PlaceAggregateV2Endpoint(placeId: placeId))
    }
}

struct ActivityService: ActivityServing {
    private let client: APIClient

    init(client: APIClient) {
        self.client = client
    }

    func getActivity(scope: ActivityScope, page: Int = 0, size: Int = 20) async throws -> SocialPageResponse<ActivityEvent> {
        try await client.send(ActivityEndpoint(scope: scope, page: page, size: size))
    }
}
