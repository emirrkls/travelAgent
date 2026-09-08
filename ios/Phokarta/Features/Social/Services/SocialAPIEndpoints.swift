import Foundation

struct PublicProfileEndpoint: APIEndpoint {
    typealias Response = PublicUserProfile

    let userId: UUID

    init(userId: UUID) {
        self.userId = userId
    }

    var method: HTTPMethod { .get }
    var path: String { "api/v1/users/\(userId.uuidString.lowercased())" }
    var requiresAuthentication: Bool { true }
}

struct MeProfileEndpoint: APIEndpoint {
    typealias Response = OwnerUserProfile

    init() {}

    var method: HTTPMethod { .get }
    var path: String { "api/v1/me" }
    var requiresAuthentication: Bool { true }
}

struct FollowUserEndpoint: APIEndpoint {
    typealias Response = EmptyPayload

    let userId: UUID

    init(userId: UUID) {
        self.userId = userId
    }

    var method: HTTPMethod { .post }
    var path: String { "api/v1/users/\(userId.uuidString.lowercased())/follow" }
    var requiresAuthentication: Bool { true }
}

struct UnfollowUserEndpoint: APIEndpoint {
    typealias Response = EmptyPayload

    let userId: UUID

    init(userId: UUID) {
        self.userId = userId
    }

    var method: HTTPMethod { .delete }
    var path: String { "api/v1/users/\(userId.uuidString.lowercased())/follow" }
    var requiresAuthentication: Bool { true }
}

struct SearchUsersEndpoint: APIEndpoint {
    typealias Response = SocialPageResponse<UserSummary>

    let query: String
    let page: Int
    let size: Int

    init(query: String, page: Int = 0, size: Int = 20) {
        self.query = query
        self.page = page
        self.size = size
    }

    var method: HTTPMethod { .get }
    var path: String { "api/v1/users/search" }
    var requiresAuthentication: Bool { true }

    var queryItems: [URLQueryItem] {
        [
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "page", value: String(page)),
            URLQueryItem(name: "size", value: String(size)),
        ]
    }
}

struct FollowersEndpoint: APIEndpoint {
    typealias Response = SocialPageResponse<UserSummary>

    let page: Int
    let size: Int

    init(page: Int = 0, size: Int = 20) {
        self.page = page
        self.size = size
    }

    var method: HTTPMethod { .get }
    var path: String { "api/v1/me/followers" }
    var requiresAuthentication: Bool { true }

    var queryItems: [URLQueryItem] {
        [
            URLQueryItem(name: "page", value: String(page)),
            URLQueryItem(name: "size", value: String(size)),
        ]
    }
}

struct FollowingEndpoint: APIEndpoint {
    typealias Response = SocialPageResponse<UserSummary>

    let page: Int
    let size: Int

    init(page: Int = 0, size: Int = 20) {
        self.page = page
        self.size = size
    }

    var method: HTTPMethod { .get }
    var path: String { "api/v1/me/following" }
    var requiresAuthentication: Bool { true }

    var queryItems: [URLQueryItem] {
        [
            URLQueryItem(name: "page", value: String(page)),
            URLQueryItem(name: "size", value: String(size)),
        ]
    }
}

struct FriendsEndpoint: APIEndpoint {
    typealias Response = SocialPageResponse<UserSummary>

    let page: Int
    let size: Int

    init(page: Int = 0, size: Int = 20) {
        self.page = page
        self.size = size
    }

    var method: HTTPMethod { .get }
    var path: String { "api/v1/me/friends" }
    var requiresAuthentication: Bool { true }

    var queryItems: [URLQueryItem] {
        [
            URLQueryItem(name: "page", value: String(page)),
            URLQueryItem(name: "size", value: String(size)),
        ]
    }
}

struct ActivityEndpoint: APIEndpoint {
    typealias Response = SocialPageResponse<ActivityEvent>

    let scope: ActivityScope
    let page: Int
    let size: Int

    init(scope: ActivityScope, page: Int = 0, size: Int = 20) {
        self.scope = scope
        self.page = page
        self.size = size
    }

    var method: HTTPMethod { .get }
    var path: String { "api/v1/activity" }
    var requiresAuthentication: Bool { true }

    var queryItems: [URLQueryItem] {
        [
            URLQueryItem(name: "scope", value: scope.rawValue),
            URLQueryItem(name: "page", value: String(page)),
            URLQueryItem(name: "size", value: String(size)),
        ]
    }
}
