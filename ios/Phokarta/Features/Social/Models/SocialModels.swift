import Foundation

struct RelationshipState: Codable, Equatable, Sendable {
    let isFollowing: Bool
    let followsYou: Bool
    let isFriend: Bool

    init(isFollowing: Bool, followsYou: Bool) {
        self.isFollowing = isFollowing
        self.followsYou = followsYou
        self.isFriend = isFollowing && followsYou
    }

    init(isFollowing: Bool, followsYou: Bool, isFriend: Bool) {
        self.isFollowing = isFollowing
        self.followsYou = followsYou
        self.isFriend = isFriend
    }
}

struct PublicUserProfile: Codable, Equatable, Sendable, Identifiable {
    let id: UUID
    let username: String
    let displayName: String
    let avatarUrl: String?
    let bio: String?
    let cityCount: Int
    let countryCount: Int
    let followerCount: Int64
    let followingCount: Int64
    let friendCount: Int64
    let relationship: RelationshipState?

    init(
        id: UUID,
        username: String,
        displayName: String,
        avatarUrl: String? = nil,
        bio: String? = nil,
        cityCount: Int = 0,
        countryCount: Int = 0,
        followerCount: Int64 = 0,
        followingCount: Int64 = 0,
        friendCount: Int64 = 0,
        relationship: RelationshipState? = nil
    ) {
        self.id = id
        self.username = username
        self.displayName = displayName
        self.avatarUrl = avatarUrl
        self.bio = bio
        self.cityCount = cityCount
        self.countryCount = countryCount
        self.followerCount = followerCount
        self.followingCount = followingCount
        self.friendCount = friendCount
        self.relationship = relationship
    }
}

struct OwnerUserProfile: Codable, Equatable, Sendable, Identifiable {
    let id: UUID
    let email: String
    let username: String
    let displayName: String
    let bio: String?
    let avatarUrl: String?
    let followerCount: Int64
    let followingCount: Int64
    let friendCount: Int64

    init(
        id: UUID,
        email: String,
        username: String,
        displayName: String,
        bio: String? = nil,
        avatarUrl: String? = nil,
        followerCount: Int64 = 0,
        followingCount: Int64 = 0,
        friendCount: Int64 = 0
    ) {
        self.id = id
        self.email = email
        self.username = username
        self.displayName = displayName
        self.bio = bio
        self.avatarUrl = avatarUrl
        self.followerCount = followerCount
        self.followingCount = followingCount
        self.friendCount = friendCount
    }
}

struct UserSummary: Codable, Equatable, Sendable, Identifiable {
    let id: UUID
    let username: String
    let displayName: String
    let avatarUrl: String?
    let relationship: RelationshipState?

    init(
        id: UUID,
        username: String,
        displayName: String,
        avatarUrl: String? = nil,
        relationship: RelationshipState? = nil
    ) {
        self.id = id
        self.username = username
        self.displayName = displayName
        self.avatarUrl = avatarUrl
        self.relationship = relationship
    }
}

struct ActivityAuthor: Codable, Equatable, Sendable, Identifiable {
    let id: UUID
    let username: String
    let displayName: String
    let avatarUrl: String?

    init(id: UUID, username: String, displayName: String, avatarUrl: String? = nil) {
        self.id = id
        self.username = username
        self.displayName = displayName
        self.avatarUrl = avatarUrl
    }
}

struct ActivityPlace: Codable, Equatable, Sendable, Identifiable {
    let id: UUID
    let name: String
    let category: PlaceCategory
    let city: String
    let coverImage: String?

    init(id: UUID, name: String, category: PlaceCategory, city: String, coverImage: String? = nil) {
        self.id = id
        self.name = name
        self.category = category
        self.city = city
        self.coverImage = coverImage
    }
}

/// Cross-place public visit event for the Activity feed.
/// Structurally excludes owner memory, personal notes, and private user fields.
struct ActivityEvent: Codable, Equatable, Sendable, Identifiable {
    let visitId: UUID
    let author: ActivityAuthor
    let place: ActivityPlace
    let overallScore: Double
    let publicReview: String
    let visitedAt: String

    var id: UUID { visitId }

    init(
        visitId: UUID,
        author: ActivityAuthor,
        place: ActivityPlace,
        overallScore: Double,
        publicReview: String,
        visitedAt: String
    ) {
        self.visitId = visitId
        self.author = author
        self.place = place
        self.overallScore = overallScore
        self.publicReview = publicReview
        self.visitedAt = visitedAt
    }
}

struct SocialPageResponse<T: Decodable & Sendable>: Decodable, Sendable {
    let content: [T]
    let page: Int
    let size: Int
    let totalElements: Int64
    let totalPages: Int
    let hasNext: Bool

    init(
        content: [T],
        page: Int,
        size: Int,
        totalElements: Int64,
        totalPages: Int,
        hasNext: Bool
    ) {
        self.content = content
        self.page = page
        self.size = size
        self.totalElements = totalElements
        self.totalPages = totalPages
        self.hasNext = hasNext
    }
}

enum ActivityScope: String, CaseIterable, Sendable {
    case community = "community"
    case friends = "friends"
}

enum SocialListKind: String, CaseIterable, Hashable, Sendable {
    case followers
    case following
    case friends
}
