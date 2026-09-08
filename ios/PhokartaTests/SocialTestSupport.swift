import Foundation
@testable import Phokarta

actor TestGate {
    private var continuation: CheckedContinuation<Void, Never>?
    private var isOpened = false

    init(isOpened: Bool = false) {
        self.isOpened = isOpened
    }

    func wait() async {
        if isOpened { return }
        await withCheckedContinuation { cont in
            self.continuation = cont
        }
    }

    func open() {
        isOpened = true
        continuation?.resume()
        continuation = nil
    }
}

actor MockSocialService: SocialServing {
    var publicProfiles: [UUID: PublicUserProfile] = [:]
    var ownerProfile: OwnerUserProfile?
    var followers: [UserSummary] = []
    var following: [UserSummary] = []
    var friends: [UserSummary] = []
    var searchResults: [UserSummary] = []

    var followHandler: (@Sendable (UUID) async throws -> RelationshipState)?
    var unfollowHandler: (@Sendable (UUID) async throws -> RelationshipState)?
    var errorToThrow: AppError?
    var gateBeforeResponse: TestGate?

    var followCallCount = 0
    var unfollowCallCount = 0
    var followCalls: [UUID] = []
    var unfollowCalls: [UUID] = []

    func setPublicProfile(_ profile: PublicUserProfile) {
        publicProfiles[profile.id] = profile
    }

    func setOwnerProfile(_ profile: OwnerUserProfile) {
        ownerProfile = profile
    }

    func fetchPublicProfile(id: UUID) async throws -> PublicUserProfile {
        if let gate = gateBeforeResponse {
            await gate.wait()
        }
        if let error = errorToThrow { throw error }
        guard let profile = publicProfiles[id] else {
            throw AppError.notFound
        }
        return profile
    }

    func fetchOwnerProfile() async throws -> OwnerUserProfile {
        if let gate = gateBeforeResponse {
            await gate.wait()
        }
        if let error = errorToThrow { throw error }
        guard let profile = ownerProfile else {
            throw AppError.notFound
        }
        return profile
    }

    func follow(userId: UUID) async throws -> RelationshipState {
        followCallCount += 1
        followCalls.append(userId)
        if let gate = gateBeforeResponse {
            await gate.wait()
        }
        if let error = errorToThrow { throw error }
        if let handler = followHandler {
            return try await handler(userId)
        }
        let followsYou = publicProfiles[userId]?.relationship.followsYou ?? false
        return RelationshipState(
            isFollowing: true,
            followsYou: followsYou,
            isFriend: followsYou,
            canMessage: false
        )
    }

    func unfollow(userId: UUID) async throws -> RelationshipState {
        unfollowCallCount += 1
        unfollowCalls.append(userId)
        if let gate = gateBeforeResponse {
            await gate.wait()
        }
        if let error = errorToThrow { throw error }
        if let handler = unfollowHandler {
            return try await handler(userId)
        }
        let followsYou = publicProfiles[userId]?.relationship.followsYou ?? false
        return RelationshipState(
            isFollowing: false,
            followsYou: followsYou,
            isFriend: false,
            canMessage: false
        )
    }

    func searchUsers(query: String, page: Int, size: Int) async throws -> SocialPageResponse<UserSummary> {
        if let gate = gateBeforeResponse {
            await gate.wait()
        }
        if let error = errorToThrow { throw error }
        let filtered = searchResults.filter {
            $0.username.localizedCaseInsensitiveContains(query) ||
            $0.displayName.localizedCaseInsensitiveContains(query)
        }
        return SocialPageResponse(content: filtered, page: page, size: size, totalElements: filtered.count, totalPages: 1, last: true)
    }

    func fetchFollowers(page: Int, size: Int) async throws -> SocialPageResponse<UserSummary> {
        if let error = errorToThrow { throw error }
        return SocialPageResponse(content: followers, page: page, size: size, totalElements: followers.count, totalPages: 1, last: true)
    }

    func fetchFollowing(page: Int, size: Int) async throws -> SocialPageResponse<UserSummary> {
        if let error = errorToThrow { throw error }
        return SocialPageResponse(content: following, page: page, size: size, totalElements: following.count, totalPages: 1, last: true)
    }

    func fetchFriends(page: Int, size: Int) async throws -> SocialPageResponse<UserSummary> {
        if let error = errorToThrow { throw error }
        return SocialPageResponse(content: friends, page: page, size: size, totalElements: friends.count, totalPages: 1, last: true)
    }
}

actor MockActivityService: ActivityServing {
    var pagesByScope: [ActivityScope: [Int: SocialPageResponse<ActivityEvent>]] = [:]
    var errorToThrow: AppError?
    var fetchCalls: [(scope: ActivityScope, page: Int)] = []
    var gateBeforeResponse: TestGate?

    func setPage(scope: ActivityScope, page: Int, response: SocialPageResponse<ActivityEvent>) {
        if pagesByScope[scope] == nil {
            pagesByScope[scope] = [:]
        }
        pagesByScope[scope]?[page] = response
    }

    func fetchActivity(scope: ActivityScope, page: Int, size: Int) async throws -> SocialPageResponse<ActivityEvent> {
        fetchCalls.append((scope, page))
        if let gate = gateBeforeResponse {
            await gate.wait()
        }
        if let error = errorToThrow { throw error }
        if let response = pagesByScope[scope]?[page] {
            return response
        }
        return SocialPageResponse(content: [], page: page, size: size, totalElements: 0, totalPages: 0, last: true)
    }
}

enum SocialTestFixtures {
    static let aliceID = UUID(uuidString: "aaaaaaaa-0000-0000-0000-000000000001")!
    static let bobID = UUID(uuidString: "bbbbbbbb-0000-0000-0000-000000000002")!
    static let charlieID = UUID(uuidString: "cccccccc-0000-0000-0000-000000000003")!

    static func userSummary(
        id: UUID,
        username: String,
        displayName: String,
        avatarUrl: String? = nil
    ) -> UserSummary {
        UserSummary(id: id, username: username, displayName: displayName, avatarUrl: avatarUrl)
    }

    static func publicProfile(
        id: UUID,
        username: String,
        displayName: String,
        bio: String? = "Traveler",
        followerCount: Int64 = 10,
        followingCount: Int64 = 5,
        friendCount: Int64 = 2,
        isFollowing: Bool = false,
        followsYou: Bool = false
    ) -> PublicUserProfile {
        PublicUserProfile(
            id: id,
            username: username,
            displayName: displayName,
            bio: bio,
            avatarUrl: nil,
            countryCount: 3,
            cityCount: 8,
            followerCount: followerCount,
            followingCount: followingCount,
            friendCount: friendCount,
            relationship: RelationshipState(
                isFollowing: isFollowing,
                followsYou: followsYou,
                isFriend: isFollowing && followsYou,
                canMessage: false
            )
        )
    }

    static func ownerProfile(
        id: UUID = aliceID,
        username: String = "alice",
        displayName: String = "Alice",
        followerCount: Int64 = 10,
        followingCount: Int64 = 5,
        friendCount: Int64 = 2
    ) -> OwnerUserProfile {
        OwnerUserProfile(
            id: id,
            email: "alice@test.local",
            username: username,
            displayName: displayName,
            bio: "Alice Bio",
            avatarUrl: nil,
            followerCount: followerCount,
            followingCount: followingCount,
            friendCount: friendCount
        )
    }

    static func activityEvent(
        id: UUID = UUID(),
        author: ActivityAuthor = ActivityAuthor(id: bobID, username: "bob", displayName: "Bob", avatarUrl: nil),
        place: ActivityPlace = ActivityPlace(id: UUID(), name: "Cappadocia Cave", category: .historic, city: "Nevsehir", coverImage: nil),
        score: Double = 9.0,
        review: String = "Amazing stay in Göreme!"
    ) -> ActivityEvent {
        ActivityEvent(
            id: id,
            author: author,
            place: place,
            visitedAt: "2026-09-01T10:00:00Z",
            overallScore: score,
            publicReview: review
        )
    }
}
