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

    func getPublicProfile(userId: UUID) async throws -> PublicUserProfile {
        if let gate = gateBeforeResponse {
            await gate.wait()
        }
        if let error = errorToThrow { throw error }
        guard let profile = publicProfiles[userId] else {
            throw AppError.notFound
        }
        return profile
    }

    func getMeProfile() async throws -> OwnerUserProfile {
        if let gate = gateBeforeResponse {
            await gate.wait()
        }
        if let error = errorToThrow { throw error }
        guard let profile = ownerProfile else {
            throw AppError.notFound
        }
        return profile
    }

    func follow(userId: UUID) async throws {
        followCallCount += 1
        followCalls.append(userId)
        if let gate = gateBeforeResponse {
            await gate.wait()
        }
        if let error = errorToThrow { throw error }
    }

    func unfollow(userId: UUID) async throws {
        unfollowCallCount += 1
        unfollowCalls.append(userId)
        if let gate = gateBeforeResponse {
            await gate.wait()
        }
        if let error = errorToThrow { throw error }
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
        return SocialPageResponse(content: filtered, page: page, size: size, totalElements: Int64(filtered.count), totalPages: 1, hasNext: false)
    }

    func getFollowers(page: Int, size: Int) async throws -> SocialPageResponse<UserSummary> {
        if let error = errorToThrow { throw error }
        return SocialPageResponse(content: followers, page: page, size: size, totalElements: Int64(followers.count), totalPages: 1, hasNext: false)
    }

    func getFollowing(page: Int, size: Int) async throws -> SocialPageResponse<UserSummary> {
        if let error = errorToThrow { throw error }
        return SocialPageResponse(content: following, page: page, size: size, totalElements: Int64(following.count), totalPages: 1, hasNext: false)
    }

    func getFriends(page: Int, size: Int) async throws -> SocialPageResponse<UserSummary> {
        if let error = errorToThrow { throw error }
        return SocialPageResponse(content: friends, page: page, size: size, totalElements: Int64(friends.count), totalPages: 1, hasNext: false)
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

    func getActivity(scope: ActivityScope, page: Int, size: Int) async throws -> SocialPageResponse<ActivityEvent> {
        fetchCalls.append((scope, page))
        if let gate = gateBeforeResponse {
            await gate.wait()
        }
        if let error = errorToThrow { throw error }
        if let response = pagesByScope[scope]?[page] {
            return response
        }
        return SocialPageResponse(content: [], page: page, size: size, totalElements: 0, totalPages: 0, hasNext: false)
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
        avatarUrl: String? = nil,
        isFollowing: Bool = false,
        followsYou: Bool = false
    ) -> UserSummary {
        UserSummary(
            id: id,
            username: username,
            displayName: displayName,
            avatarUrl: avatarUrl,
            relationship: RelationshipState(isFollowing: isFollowing, followsYou: followsYou)
        )
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
            visitId: id,
            author: author,
            place: place,
            overallScore: score,
            publicReview: review,
            visitedAt: "2026-09-01T10:00:00Z"
        )
    }
}
