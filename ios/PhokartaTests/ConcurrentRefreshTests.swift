import XCTest
@testable import Phokarta

final class ConcurrentRefreshTests: XCTestCase {
    func testTenConcurrentUnauthorizedRequestsCauseExactlyOneRefresh() async throws {
        let store = InMemorySessionStore(session: testSession(access: "access-1", refresh: "refresh-token-aaaaaaaa"))
        let config = try TestConfig.httpsTest()
        let probe = RefreshProbe()
        let transport = FakeHTTPTransport { request in
            try await probe.handle(request)
        }
        let refresh = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)

        try await withThrowingTaskGroup(of: UserProfileDTO.self) { group in
            for _ in 0..<10 {
                group.addTask {
                    try await client.send(MeEndpoint())
                }
            }
            var received = 0
            for try await profile in group {
                XCTAssertEqual(profile.id, TestJSON.userID)
                received += 1
            }
            XCTAssertEqual(received, 10)
        }

        let refreshCount = await probe.refreshCount
        XCTAssertEqual(refreshCount, 1)
        let persistedSession = await store.load()
        XCTAssertEqual(persistedSession?.tokens.accessToken, "access-2")
        XCTAssertEqual(persistedSession?.tokens.refreshToken, "refresh-token-bbbbbbbb")
    }
}

actor RefreshProbe {
    private(set) var refreshCount = 0
    private var stale401Count = 0
    private var refreshWait: CheckedContinuation<Void, Never>?
    private let expectedStale = 10

    func handle(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let url = request.url!
        if url.path.hasSuffix("/api/v1/auth/refresh") {
            refreshCount += 1
            if stale401Count < expectedStale {
                await withCheckedContinuation { continuation in
                    refreshWait = continuation
                }
            }
            return TestJSON.http(
                url,
                status: 200,
                data: TestJSON.tokens(access: "access-2", refresh: "refresh-token-bbbbbbbb")
            )
        }

        let authorization = request.value(forHTTPHeaderField: "Authorization")
        if authorization == "Bearer access-1" {
            stale401Count += 1
            if stale401Count >= expectedStale, let wait = refreshWait {
                refreshWait = nil
                wait.resume()
            }
            return TestJSON.http(
                url,
                status: 401,
                data: TestJSON.apiError(status: 401, code: "TOKEN_EXPIRED")
            )
        }
        if authorization == "Bearer access-2" {
            return TestJSON.http(url, status: 200, data: TestJSON.utf8(TestJSON.profile()))
        }
        throw AppError.unknown(status: 500, code: authorization)
    }
}
