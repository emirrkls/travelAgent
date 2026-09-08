import Foundation

public struct PublicProfileEndpoint: APIEndpoint {
    public typealias Response = PublicUserProfile

    public let userId: UUID

    public init(userId: UUID) {
        self.userId = userId
    }

    public var method: HTTPMethod { .get }
    public var path: String { "api/v1/users/\(userId.uuidString.lowercased())" }
    public var requiresAuthentication: Bool { true }
}

public struct MeProfileEndpoint: APIEndpoint {
    public typealias Response = OwnerUserProfile

    public init() {}

    public var method: HTTPMethod { .get }
    public var path: String { "api/v1/me" }
    public var requiresAuthentication: Bool { true }
}

public struct FollowUserEndpoint: APIEndpoint {
    public typealias Response = EmptyPayload

    public let userId: UUID

    public init(userId: UUID) {
        self.userId = userId
    }

    public var method: HTTPMethod { .post }
    public var path: String { "api/v1/users/\(userId.uuidString.lowercased())/follow" }
    public var requiresAuthentication: Bool { true }
}

public struct UnfollowUserEndpoint: APIEndpoint {
    public typealias Response = EmptyPayload

    public let userId: UUID

    public init(userId: UUID) {
        self.userId = userId
    }

    public var method: HTTPMethod { .delete }
    public var path: String { "api/v1/users/\(userId.uuidString.lowercased())/follow" }
    public var requiresAuthentication: Bool { true }
}

public struct SearchUsersEndpoint: APIEndpoint {
    public typealias Response = SocialPageResponse<UserSummary>

    public let query: String
    public let page: Int
    public let size: Int

    public init(query: String, page: Int = 0, size: Int = 20) {
        self.query = query
        self.page = page
        self.size = size
    }

    public var method: HTTPMethod { .get }
    public var path: String { "api/v1/users/search" }
    public var requiresAuthentication: Bool { true }

    public var queryItems: [URLQueryItem] {
        [
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "page", value: String(page)),
            URLQueryItem(name: "size", value: String(size)),
        ]
    }
}

public struct FollowersEndpoint: APIEndpoint {
    public typealias Response = SocialPageResponse<UserSummary>

    public let page: Int
    public let size: Int

    public init(page: Int = 0, size: Int = 20) {
        self.page = page
        self.size = size
    }

    public var method: HTTPMethod { .get }
    public var path: String { "api/v1/me/followers" }
    public var requiresAuthentication: Bool { true }

    public var queryItems: [URLQueryItem] {
        [
            URLQueryItem(name: "page", value: String(page)),
            URLQueryItem(name: "size", value: String(size)),
        ]
    }
}

public struct FollowingEndpoint: APIEndpoint {
    public typealias Response = SocialPageResponse<UserSummary>

    public let page: Int
    public let size: Int

    public init(page: Int = 0, size: Int = 20) {
        self.page = page
        self.size = size
    }

    public var method: HTTPMethod { .get }
    public var path: String { "api/v1/me/following" }
    public var requiresAuthentication: Bool { true }

    public var queryItems: [URLQueryItem] {
        [
            URLQueryItem(name: "page", value: String(page)),
            URLQueryItem(name: "size", value: String(size)),
        ]
    }
}

public struct FriendsEndpoint: APIEndpoint {
    public typealias Response = SocialPageResponse<UserSummary>

    public let page: Int
    public let size: Int

    public init(page: Int = 0, size: Int = 20) {
        self.page = page
        self.size = size
    }

    public var method: HTTPMethod { .get }
    public var path: String { "api/v1/me/friends" }
    public var requiresAuthentication: Bool { true }

    public var queryItems: [URLQueryItem] {
        [
            URLQueryItem(name: "page", value: String(page)),
            URLQueryItem(name: "size", value: String(size)),
        ]
    }
}

public struct ActivityEndpoint: APIEndpoint {
    public typealias Response = SocialPageResponse<ActivityEvent>

    public let scope: ActivityScope
    public let page: Int
    public let size: Int

    public init(scope: ActivityScope, page: Int = 0, size: Int = 20) {
        self.scope = scope
        self.page = page
        self.size = size
    }

    public var method: HTTPMethod { .get }
    public var path: String { "api/v1/activity" }
    public var requiresAuthentication: Bool { true }

    public var queryItems: [URLQueryItem] {
        [
            URLQueryItem(name: "scope", value: scope.rawValue),
            URLQueryItem(name: "page", value: String(page)),
            URLQueryItem(name: "size", value: String(size)),
        ]
    }
}
