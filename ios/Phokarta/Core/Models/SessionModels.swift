import Foundation

struct CurrentUser: Equatable, Sendable, Codable {
    let id: UUID
    let email: String
    let username: String
    let displayName: String
}

struct TokenPair: Equatable, Sendable, Codable, CustomStringConvertible, CustomDebugStringConvertible {
    let accessToken: String
    let refreshToken: String
    let tokenType: String
    let expiresIn: Int64
    let accessTokenExpiresAt: String?

    var description: String {
        "TokenPair(tokenType: \(tokenType), expiresIn: \(expiresIn), accessToken: <redacted>, refreshToken: <redacted>)"
    }

    var debugDescription: String { description }
}

struct PersistedSession: Equatable, Sendable, Codable {
    var tokens: TokenPair
    var user: CurrentUser
}
