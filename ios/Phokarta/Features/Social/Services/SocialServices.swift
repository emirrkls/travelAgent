import Foundation

public protocol SocialServing: Sendable {
    func getPublicProfile(userId: UUID) async throws -> PublicUserProfile
    func getMeProfile() async throws -> OwnerUserProfile
    func follow(userId: UUID) async throws
    func unfollow(userId: UUID) async throws
    func searchUsers(query: String, page: Int, size: Int) async throws -> SocialPageResponse<UserSummary>
    func getFollowers(page: Int, size: Int) async throws -> SocialPageResponse<UserSummary>
    func getFollowing(page: Int, size: Int) async throws -> SocialPageResponse<UserSummary>
    func getFriends(page: Int, size: Int) async throws -> SocialPageResponse<UserSummary>
}

public protocol ActivityServing: Sendable {
    func getActivity(scope: ActivityScope, page: Int, size: Int) async throws -> SocialPageResponse<ActivityEvent>
}

public struct SocialService: SocialServing {
    private let client: APIClient

    public init(client: APIClient) {
        self.client = client
    }

    public func getPublicProfile(userId: UUID) async throws -> PublicUserProfile {
        try await client.send(PublicProfileEndpoint(userId: userId))
    }

    public func getMeProfile() async throws -> OwnerUserProfile {
        try await client.send(MeProfileEndpoint())
    }

    public func follow(userId: UUID) async throws {
        _ = try await client.send(FollowUserEndpoint(userId: userId))
    }

    public func unfollow(userId: UUID) async throws {
        _ = try await client.send(UnfollowUserEndpoint(userId: userId))
    }

    public func searchUsers(query: String, page: Int = 0, size: Int = 20) async throws -> SocialPageResponse<UserSummary> {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return SocialPageResponse(content: [], page: page, size: size, totalElements: 0, totalPages: 0, hasNext: false)
        }
        return try await client.send(SearchUsersEndpoint(query: trimmed, page: page, size: size))
    }

    public func getFollowers(page: Int = 0, size: Int = 20) async throws -> SocialPageResponse<UserSummary> {
        try await client.send(FollowersEndpoint(page: page, size: size))
    }

    public func getFollowing(page: Int = 0, size: Int = 20) async throws -> SocialPageResponse<UserSummary> {
        try await client.send(FollowingEndpoint(page: page, size: size))
    }

    public func getFriends(page: Int = 0, size: Int = 20) async throws -> SocialPageResponse<UserSummary> {
        try await client.send(FriendsEndpoint(page: page, size: size))
    }
}

public struct ActivityService: ActivityServing {
    private let client: APIClient

    public init(client: APIClient) {
        self.client = client
    }

    public func getActivity(scope: ActivityScope, page: Int = 0, size: Int = 20) async throws -> SocialPageResponse<ActivityEvent> {
        try await client.send(ActivityEndpoint(scope: scope, page: page, size: size))
    }
}
