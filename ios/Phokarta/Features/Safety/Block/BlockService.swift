import Foundation

// MARK: - DTOs

struct BlockedUser: Codable, Equatable, Sendable, Identifiable {
    let userId: UUID
    let username: String
    let displayName: String
    let avatarUrl: String?
    let blockedAt: String

    var id: UUID { userId }
}

// MARK: - Endpoints

struct BlockUserEndpoint: APIEndpoint {
    typealias Response = EmptyPayload
    let userId: UUID
    var method: HTTPMethod { .put }
    var path: String { "api/v1/me/blocks/\(userId.uuidString.lowercased())" }
    var requiresAuthentication: Bool { true }
}

struct UnblockUserEndpoint: APIEndpoint {
    typealias Response = EmptyPayload
    let userId: UUID
    var method: HTTPMethod { .delete }
    var path: String { "api/v1/me/blocks/\(userId.uuidString.lowercased())" }
    var requiresAuthentication: Bool { true }
}

struct ListBlockedEndpoint: APIEndpoint {
    typealias Response = SocialPageResponse<BlockedUser>
    let page: Int
    let size: Int
    var method: HTTPMethod { .get }
    var path: String { "api/v1/me/blocks" }
    var requiresAuthentication: Bool { true }
    var queryItems: [URLQueryItem] {
        [
            URLQueryItem(name: "page", value: String(page)),
            URLQueryItem(name: "size", value: String(size)),
        ]
    }
}

// MARK: - Protocol

protocol BlockServing: Sendable {
    func block(userId: UUID) async throws
    func unblock(userId: UUID) async throws
    func listBlocked(page: Int, size: Int) async throws -> SocialPageResponse<BlockedUser>
}

// MARK: - Implementation

struct BlockService: BlockServing {
    private let client: APIClient

    init(client: APIClient) {
        self.client = client
    }

    func block(userId: UUID) async throws {
        _ = try await client.send(BlockUserEndpoint(userId: userId))
    }

    func unblock(userId: UUID) async throws {
        _ = try await client.send(UnblockUserEndpoint(userId: userId))
    }

    func listBlocked(page: Int = 0, size: Int = 20) async throws -> SocialPageResponse<BlockedUser> {
        try await client.send(ListBlockedEndpoint(page: page, size: size))
    }
}
