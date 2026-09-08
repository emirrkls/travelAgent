import Foundation

public struct RelationshipState: Codable, Equatable, Sendable {
    public let isFollowing: Bool
    public let followsYou: Bool
    public let isFriend: Bool

    public init(isFollowing: Bool, followsYou: Bool) {
        self.isFollowing = isFollowing
        self.followsYou = followsYou
        self.isFriend = isFollowing && followsYou
    }

    public init(isFollowing: Bool, followsYou: Bool, isFriend: Bool) {
        self.isFollowing = isFollowing
        self.followsYou = followsYou
        self.isFriend = isFriend
    }
}

public struct PublicUserProfile: Codable, Equatable, Sendable, Identifiable {
    public let id: UUID
    public let username: String
    public let displayName: String
    public let avatarUrl: String?
    public let bio: String?
    public let cityCount: Int
    public let countryCount: Int
    public let followerCount: Int64
    public let followingCount: Int64
    public let friendCount: Int64
    public let relationship: RelationshipState?

    public init(
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

public struct OwnerUserProfile: Codable, Equatable, Sendable, Identifiable {
    public let id: UUID
    public let email: String
    public let username: String
    public let displayName: String
    public let bio: String?
    public let avatarUrl: String?
    public let followerCount: Int64
    public let followingCount: Int64
    public let friendCount: Int64

    public init(
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

public struct UserSummary: Codable, Equatable, Sendable, Identifiable {
    public let id: UUID
    public let username: String
    public let displayName: String
    public let avatarUrl: String?
    public let relationship: RelationshipState?

    public init(
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

public struct ActivityAuthor: Codable, Equatable, Sendable, Identifiable {
    public let id: UUID
    public let username: String
    public let displayName: String
    public let avatarUrl: String?

    public init(id: UUID, username: String, displayName: String, avatarUrl: String? = nil) {
        self.id = id
        self.username = username
        self.displayName = displayName
        self.avatarUrl = avatarUrl
    }
}

public struct ActivityPlace: Codable, Equatable, Sendable, Identifiable {
    public let id: UUID
    public let name: String
    public let category: PlaceCategory
    public let city: String
    public let coverImage: String?

    public init(id: UUID, name: String, category: PlaceCategory, city: String, coverImage: String? = nil) {
        self.id = id
        self.name = name
        self.category = category
        self.city = city
        self.coverImage = coverImage
    }
}

/// Cross-place public visit event for the Activity feed.
/// Structurally excludes owner memory, personal notes, and private user fields.
public struct ActivityEvent: Codable, Equatable, Sendable, Identifiable {
    public let visitId: UUID
    public let author: ActivityAuthor
    public let place: ActivityPlace
    public let overallScore: Double
    public let publicReview: String
    public let visitedAt: String

    public var id: UUID { visitId }

    public init(
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

public struct SocialPageResponse<T: Decodable & Sendable>: Decodable, Sendable {
    public let content: [T]
    public let page: Int
    public let size: Int
    public let totalElements: Int64
    public let totalPages: Int
    public let hasNext: Bool

    public init(
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

public enum ActivityScope: String, CaseIterable, Sendable {
    case community = "community"
    case friends = "friends"
}

public enum SocialListKind: String, CaseIterable, Hashable, Sendable {
    case followers
    case following
    case friends
}
