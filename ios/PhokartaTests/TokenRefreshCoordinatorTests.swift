import XCTest
@testable import Phokarta

final class TokenRefreshCoordinatorTests: XCTestCase {
    func testRefreshRotatesAndPersistsNewRefreshToken() async throws {
        let store = InMemorySessionStore(session: testSession())
        let config = try TestConfig.httpsTest()
        let transport = FakeHTTPTransport { request in
            XCTAssertEqual(request.url?.path, "/api/v1/auth/refresh")
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
            guard let bodyData = request.httpBody,
                  let body = try JSONSerialization.jsonObject(with: bodyData) as? [String: Any] else {
                throw AppError.decoding
            }
            XCTAssertEqual(body["refreshToken"] as? String, "refresh-token-aaaaaaaa")
            return TestJSON.http(
                request.url!,
                status: 200,
                data: TestJSON.tokens(access: "access-2", refresh: "refresh-token-bbbbbbbb")
            )
        }
        let coordinator = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let pair = try await coordinator.refreshSession()
        XCTAssertEqual(pair.accessToken, "access-2")
        XCTAssertEqual(pair.refreshToken, "refresh-token-bbbbbbbb")
        XCTAssertEqual(await store.load()?.tokens.refreshToken, "refresh-token-bbbbbbbb")
        XCTAssertNotEqual(await store.load()?.tokens.refreshToken, "refresh-token-aaaaaaaa")
    }

    func testTransientRefreshFailureKeepsStoredSession() async throws {
        let store = InMemorySessionStore(session: testSession())
        let config = try TestConfig.httpsTest()
        let transport = FakeHTTPTransport { _ in
            throw AppError.networkUnavailable
        }
        let coordinator = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        do {
            _ = try await coordinator.refreshSession()
            XCTFail("expected transient failure")
        } catch AppError.networkUnavailable {
            XCTAssertEqual(await store.load()?.tokens.refreshToken, "refresh-token-aaaaaaaa")
        }
    }

    func testTerminalRefreshFailureClearsSession() async throws {
        let store = InMemorySessionStore(session: testSession())
        let config = try TestConfig.httpsTest()
        let transport = FakeHTTPTransport { request in
            TestJSON.http(
                request.url!,
                status: 401,
                data: TestJSON.apiError(status: 401, code: "INVALID_REFRESH_TOKEN")
            )
        }
        let coordinator = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        do {
            _ = try await coordinator.refreshSession()
            XCTFail("expected terminal failure")
        } catch AppError.unauthorized {
            XCTAssertNil(await store.load())
        }
    }

    func testRetryOnceDoesNotRefreshLogin() async throws {
        var loginCalls = 0
        let store = InMemorySessionStore()
        let config = try TestConfig.httpsTest()
        let transport = FakeHTTPTransport { request in
            loginCalls += 1
            return TestJSON.http(
                request.url!,
                status: 401,
                data: TestJSON.apiError(status: 401, code: "INVALID_CREDENTIALS")
            )
        }
        let refresh = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)
        let auth = AuthRepository(client: client, store: store, refresh: refresh, config: config)
        do {
            _ = try await auth.login(identifier: "x", password: "DemoPass123!")
            XCTFail("expected invalid credentials")
        } catch AppError.invalidCredentials {
            XCTAssertEqual(loginCalls, 1)
        }
    }
}
