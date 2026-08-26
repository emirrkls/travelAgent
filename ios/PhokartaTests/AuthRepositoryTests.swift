import XCTest
@testable import Phokarta

final class AuthRepositoryTests: XCTestCase {
    func testLoginPersistsSessionAndReturnsUser() async throws {
        let store = InMemorySessionStore()
        let config = try TestConfig.httpsTest()
        let transport = FakeHTTPTransport { request in
            XCTAssertEqual(request.url?.path, "/api/v1/auth/login")
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
            return TestJSON.http(request.url!, status: 200, data: TestJSON.session())
        }
        let auth = TestConfig.repository(store: store, transport: transport, config: config)
        let user = try await auth.login(identifier: "demo@phokarta.local", password: "DemoPass123!")
        XCTAssertEqual(user.email, "demo@phokarta.local")
        let persistedSession = await store.load()
        XCTAssertEqual(persistedSession?.tokens.refreshToken, "refresh-token-aaaaaaaa")
        XCTAssertEqual(persistedSession?.tokens.accessToken, "access-1")
    }

    func testRegisterSignsInImmediatelyFromReturnedTokens() async throws {
        let store = InMemorySessionStore()
        let config = try TestConfig.httpsTest()
        let transport = FakeHTTPTransport { request in
            XCTAssertEqual(request.url?.path, "/api/v1/auth/register")
            return TestJSON.http(request.url!, status: 201, data: TestJSON.session(email: "new@example.com"))
        }
        let auth = TestConfig.repository(store: store, transport: transport, config: config)
        let user = try await auth.register(
            email: "new@example.com",
            username: "new_user",
            displayName: "New",
            password: "SecurePass1"
        )
        XCTAssertEqual(user.email, "new@example.com")
        let persistedSession = await store.load()
        XCTAssertNotNil(persistedSession)
    }

    func testLoginInvalidCredentials() async throws {
        let store = InMemorySessionStore()
        let config = try TestConfig.httpsTest()
        let transport = FakeHTTPTransport { request in
            TestJSON.http(
                request.url!,
                status: 401,
                data: TestJSON.apiError(status: 401, code: "INVALID_CREDENTIALS")
            )
        }
        let auth = TestConfig.repository(store: store, transport: transport, config: config)
        do {
            _ = try await auth.login(identifier: "demo@phokarta.local", password: "wrong-password")
            XCTFail("expected error")
        } catch AppError.invalidCredentials {
            let persistedSession = await store.load()
            XCTAssertNil(persistedSession)
        }
    }

    func testLogoutClearsLocalSessionEvenIfRemoteFails() async throws {
        let store = InMemorySessionStore(session: testSession())
        let config = try TestConfig.httpsTest()
        let transport = FakeHTTPTransport { request in
            XCTAssertEqual(request.url?.path, "/api/v1/auth/logout")
            throw AppError.networkUnavailable
        }
        let auth = TestConfig.repository(store: store, transport: transport, config: config)
        await auth.logout()
        let persistedSession = await store.load()
        XCTAssertNil(persistedSession)
    }

    func testLogoutSucceedsWhenRemoteRevokes() async throws {
        let store = InMemorySessionStore(session: testSession())
        let config = try TestConfig.httpsTest()
        let transport = FakeHTTPTransport { request in
            TestJSON.http(request.url!, status: 204)
        }
        let auth = TestConfig.repository(store: store, transport: transport, config: config)
        await auth.logout()
        let persistedSession = await store.load()
        XCTAssertNil(persistedSession)
    }

    func testRestoreSessionRefreshesThenLoadsProfile() async throws {
        let store = InMemorySessionStore(session: testSession())
        let config = try TestConfig.httpsTest()
        let transport = FakeHTTPTransport { request in
            switch request.url?.path {
            case "/api/v1/auth/refresh":
                return TestJSON.http(request.url!, status: 200, data: TestJSON.tokens(access: "access-2", refresh: "refresh-token-bbbbbbbb"))
            case "api/v1/me", "/api/v1/me":
                XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access-2")
                return TestJSON.http(request.url!, status: 200, data: TestJSON.utf8(TestJSON.profile(displayName: "Restored")))
            default:
                XCTFail("unexpected \(request.url?.path ?? "")")
                throw AppError.unknown(status: 500, code: nil)
            }
        }
        let auth = TestConfig.repository(store: store, transport: transport, config: config)
        let state = try await auth.restoreSession()
        guard case .signedIn(let user) = state else {
            return XCTFail("expected signed in")
        }
        XCTAssertEqual(user.displayName, "Restored")
        let persistedSession = await store.load()
        XCTAssertEqual(persistedSession?.tokens.refreshToken, "refresh-token-bbbbbbbb")
        XCTAssertEqual(persistedSession?.tokens.accessToken, "access-2")
    }

    func testRestorePreservesSessionOnTransientNetworkFailure() async throws {
        let store = InMemorySessionStore(session: testSession())
        let config = try TestConfig.httpsTest()
        let transport = FakeHTTPTransport { _ in
            throw AppError.networkUnavailable
        }
        let auth = TestConfig.repository(store: store, transport: transport, config: config)
        let state = try await auth.restoreSession()
        guard case .signedIn(let user) = state else {
            return XCTFail("expected preserved session")
        }
        XCTAssertEqual(user.id, TestJSON.userID)
        let persistedSession = await store.load()
        XCTAssertEqual(persistedSession?.tokens.refreshToken, "refresh-token-aaaaaaaa")
    }

    func testRestoreClearsSessionOnTerminalRefreshFailure() async throws {
        let store = InMemorySessionStore(session: testSession())
        let config = try TestConfig.httpsTest()
        let transport = FakeHTTPTransport { request in
            TestJSON.http(
                request.url!,
                status: 401,
                data: TestJSON.apiError(status: 401, code: "INVALID_REFRESH_TOKEN")
            )
        }
        let auth = TestConfig.repository(store: store, transport: transport, config: config)
        let state = try await auth.restoreSession()
        XCTAssertEqual(state, .signedOut)
        let persistedSession = await store.load()
        XCTAssertNil(persistedSession)
    }

    func testPlaceholderHostRefusesAuthNetwork() async throws {
        let store = InMemorySessionStore()
        let config = try AppConfig.parse(
            baseURLString: "https://api.phokarta.invalid/",
            allowsInsecureHTTP: false
        )
        let transport = FakeHTTPTransport { _ in
            XCTFail("must not hit network")
            throw AppError.networkUnavailable
        }
        let auth = TestConfig.repository(store: store, transport: transport, config: config)
        do {
            _ = try await auth.login(identifier: "demo@phokarta.local", password: "DemoPass123!")
            XCTFail("expected placeholder error")
        } catch AppError.placeholderAPI {
            let persistedSession = await store.load()
            XCTAssertNil(persistedSession)
        }
    }
}
