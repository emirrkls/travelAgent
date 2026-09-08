import XCTest
@testable import Phokarta

final class BlockServiceTests: XCTestCase {
    private let targetUserId = UUID(uuidString: "22222222-2222-2222-2222-222222222222")!

    func testBlockEndpointMethodAndAuth() async throws {
        let store = InMemorySessionStore(session: testSession(access: "access-1"))
        let config = try TestConfig.httpsTest()
        let targetId = targetUserId
        let transport = FakeHTTPTransport { request in
            XCTAssertEqual(request.httpMethod, "PUT")
            XCTAssertEqual(request.url?.path, "/api/v1/me/blocks/\(targetId.uuidString.lowercased())")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access-1")
            return TestJSON.http(request.url!, status: 204)
        }
        let refresh = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)
        let service = BlockService(client: client)

        try await service.block(userId: targetUserId)
    }

    func testUnblockEndpointMethodAndAuth() async throws {
        let store = InMemorySessionStore(session: testSession(access: "access-1"))
        let config = try TestConfig.httpsTest()
        let targetId = targetUserId
        let transport = FakeHTTPTransport { request in
            XCTAssertEqual(request.httpMethod, "DELETE")
            XCTAssertEqual(request.url?.path, "/api/v1/me/blocks/\(targetId.uuidString.lowercased())")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access-1")
            return TestJSON.http(request.url!, status: 204)
        }
        let refresh = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)
        let service = BlockService(client: client)

        try await service.unblock(userId: targetUserId)
    }

    func testListBlockedEndpoint() async throws {
        let store = InMemorySessionStore(session: testSession(access: "access-1"))
        let config = try TestConfig.httpsTest()
        let blockedJSON = """
        {
            "content": [
                {
                    "userId": "\(targetUserId.uuidString.lowercased())",
                    "username": "blocked_user",
                    "displayName": "Blocked User",
                    "avatarUrl": null,
                    "blockedAt": "2026-09-08T10:00:00Z"
                }
            ],
            "page": 0,
            "size": 20,
            "totalElements": 1,
            "totalPages": 1,
            "hasNext": false
        }
        """
        let transport = FakeHTTPTransport { request in
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertEqual(request.url?.path, "/api/v1/me/blocks")
            let queryItems = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
            XCTAssertEqual(queryItems.first(where: { $0.name == "page" })?.value, "0")
            XCTAssertEqual(queryItems.first(where: { $0.name == "size" })?.value, "20")
            return TestJSON.http(request.url!, status: 200, data: Data(blockedJSON.utf8))
        }
        let refresh = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)
        let service = BlockService(client: client)

        let result = try await service.listBlocked(page: 0, size: 20)
        XCTAssertEqual(result.content.count, 1)
        XCTAssertEqual(result.content.first?.userId, targetUserId)
        XCTAssertEqual(result.content.first?.username, "blocked_user")
    }

    func testBlockIdempotency() async throws {
        let store = InMemorySessionStore(session: testSession(access: "access-1"))
        let config = try TestConfig.httpsTest()
        let callCounter = CallCounter()
        let transport = FakeHTTPTransport { request in
            await callCounter.increment()
            return TestJSON.http(request.url!, status: 204)
        }
        let refresh = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)
        let service = BlockService(client: client)

        try await service.block(userId: targetUserId)
        try await service.block(userId: targetUserId)
        let count = await callCounter.count
        XCTAssertEqual(count, 2)
    }

    func testSelfBlockThrowsBadRequest() async throws {
        let store = InMemorySessionStore(session: testSession(access: "access-1"))
        let config = try TestConfig.httpsTest()
        let transport = FakeHTTPTransport { request in
            return TestJSON.http(
                request.url!,
                status: 400,
                data: TestJSON.apiError(status: 400, code: "CANNOT_BLOCK_SELF", message: "You cannot block yourself")
            )
        }
        let refresh = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)
        let service = BlockService(client: client)

        do {
            try await service.block(userId: TestJSON.userID)
            XCTFail("Expected error not thrown")
        } catch let error as AppError {
            if case .validation(let message, _) = error {
                XCTAssertEqual(message, "You cannot block yourself")
            } else {
                XCTFail("Unexpected error: \(error)")
            }
        }
    }

    func testBlockRateLimited() async throws {
        let store = InMemorySessionStore(session: testSession(access: "access-1"))
        let config = try TestConfig.httpsTest()
        let transport = FakeHTTPTransport { request in
            return TestJSON.http(
                request.url!,
                status: 429,
                data: TestJSON.apiError(status: 429, code: "RATE_LIMITED", message: "Too many attempts.")
            )
        }
        let refresh = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)
        let service = BlockService(client: client)

        do {
            try await service.block(userId: targetUserId)
            XCTFail("Expected error not thrown")
        } catch let error as AppError {
            XCTAssertEqual(error, .rateLimited)
        }
    }

    func testBlockTargetNotFound() async throws {
        let store = InMemorySessionStore(session: testSession(access: "access-1"))
        let config = try TestConfig.httpsTest()
        let transport = FakeHTTPTransport { request in
            return TestJSON.http(
                request.url!,
                status: 404,
                data: TestJSON.apiError(status: 404, code: "NOT_FOUND", message: "User not found")
            )
        }
        let refresh = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)
        let service = BlockService(client: client)

        do {
            try await service.block(userId: targetUserId)
            XCTFail("Expected error not thrown")
        } catch let error as AppError {
            XCTAssertEqual(error, .notFound)
        }
    }
}
