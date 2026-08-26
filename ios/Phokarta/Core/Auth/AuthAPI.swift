import Foundation

struct RegisterRequestDTO: Encodable, Sendable, CustomStringConvertible, CustomDebugStringConvertible {
    let email: String
    let username: String
    let displayName: String
    let password: String

    var description: String {
        "RegisterRequestDTO(email: \(email), username: \(username), displayName: \(displayName), password: <redacted>)"
    }

    var debugDescription: String { description }
}

struct LoginRequestDTO: Encodable, Sendable, CustomStringConvertible, CustomDebugStringConvertible {
    let identifier: String
    let password: String

    var description: String {
        "LoginRequestDTO(identifier: \(identifier), password: <redacted>)"
    }

    var debugDescription: String { description }
}

struct RefreshRequestDTO: Encodable, Sendable, CustomStringConvertible, CustomDebugStringConvertible {
    let refreshToken: String

    var description: String { "RefreshRequestDTO(refreshToken: <redacted>)" }
    var debugDescription: String { description }
}

struct LogoutRequestDTO: Encodable, Sendable, CustomStringConvertible, CustomDebugStringConvertible {
    let refreshToken: String

    var description: String { "LogoutRequestDTO(refreshToken: <redacted>)" }
    var debugDescription: String { description }
}

struct UserProfileDTO: Decodable, Equatable, Sendable {
    let id: UUID
    let email: String
    let username: String
    let displayName: String
    let bio: String?
    let avatarUrl: String?
    let followerCount: Int64
    let followingCount: Int64
    let friendCount: Int64

    func toCurrentUser() -> CurrentUser {
        CurrentUser(id: id, email: email, username: username, displayName: displayName)
    }
}

struct TokenPairDTO: Decodable, Equatable, Sendable {
    let accessToken: String
    let refreshToken: String
    let tokenType: String
    let expiresIn: Int64
    let accessTokenExpiresAt: String?

    func toTokenPair() -> TokenPair {
        TokenPair(
            accessToken: accessToken,
            refreshToken: refreshToken,
            tokenType: tokenType,
            expiresIn: expiresIn,
            accessTokenExpiresAt: accessTokenExpiresAt
        )
    }
}

struct AuthSessionDTO: Decodable, Equatable, Sendable {
    let user: UserProfileDTO
    let accessToken: String
    let refreshToken: String
    let tokenType: String
    let expiresIn: Int64
    let accessTokenExpiresAt: String?

    func toPersistedSession() -> PersistedSession {
        PersistedSession(
            tokens: TokenPair(
                accessToken: accessToken,
                refreshToken: refreshToken,
                tokenType: tokenType,
                expiresIn: expiresIn,
                accessTokenExpiresAt: accessTokenExpiresAt
            ),
            user: user.toCurrentUser()
        )
    }
}

struct RegisterEndpoint: APIEndpoint {
    typealias Response = AuthSessionDTO
    typealias Body = RegisterRequestDTO

    let body: RegisterRequestDTO?
    var method: HTTPMethod { .post }
    var path: String { "api/v1/auth/register" }
    var requiresAuthentication: Bool { false }
    var allowsRetryAfterRefresh: Bool { false }

    init(body: RegisterRequestDTO) {
        self.body = body
    }
}

struct LoginEndpoint: APIEndpoint {
    typealias Response = AuthSessionDTO
    typealias Body = LoginRequestDTO

    let body: LoginRequestDTO?
    var method: HTTPMethod { .post }
    var path: String { "api/v1/auth/login" }
    var requiresAuthentication: Bool { false }
    var allowsRetryAfterRefresh: Bool { false }

    init(body: LoginRequestDTO) {
        self.body = body
    }
}

struct RefreshEndpoint: APIEndpoint {
    typealias Response = TokenPairDTO
    typealias Body = RefreshRequestDTO

    let body: RefreshRequestDTO?
    var method: HTTPMethod { .post }
    var path: String { "api/v1/auth/refresh" }
    var requiresAuthentication: Bool { false }
    var allowsRetryAfterRefresh: Bool { false }

    init(refreshToken: String) {
        self.body = RefreshRequestDTO(refreshToken: refreshToken)
    }
}

struct LogoutEndpoint: APIEndpoint {
    typealias Response = EmptyPayload
    typealias Body = LogoutRequestDTO

    let body: LogoutRequestDTO?
    var method: HTTPMethod { .post }
    var path: String { "api/v1/auth/logout" }
    var requiresAuthentication: Bool { false }
    var allowsRetryAfterRefresh: Bool { false }

    init(refreshToken: String) {
        self.body = LogoutRequestDTO(refreshToken: refreshToken)
    }
}

struct MeEndpoint: APIEndpoint {
    typealias Response = UserProfileDTO

    var method: HTTPMethod { .get }
    var path: String { "api/v1/me" }
    var requiresAuthentication: Bool { true }
    var allowsRetryAfterRefresh: Bool { true }
}
