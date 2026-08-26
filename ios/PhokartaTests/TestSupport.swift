import Foundation
@testable import Phokarta

enum TestJSON {
    static let userID = UUID(uuidString: "11111111-1111-1111-1111-111111111111")!

    static func profile(
        id: UUID = userID,
        email: String = "demo@phokarta.local",
        username: String = "emir_demo",
        displayName: String = "Emir"
    ) -> String {
        """
        {"id":"\(id.uuidString.lowercased())","email":"\(email)","username":"\(username)","displayName":"\(displayName)","bio":null,"avatarUrl":null,"followerCount":0,"followingCount":0,"friendCount":0}
        """
    }

    static func session(
        access: String = "access-1",
        refresh: String = "refresh-token-aaaaaaaa",
        email: String = "demo@phokarta.local"
    ) -> Data {
        utf8(
            """
            {"user":\(profile(email: email)),"accessToken":"\(access)","refreshToken":"\(refresh)","tokenType":"Bearer","expiresIn":900,"accessTokenExpiresAt":"2026-08-26T16:24:00Z"}
            """
        )
    }

    static func tokens(access: String, refresh: String) -> Data {
        utf8(
            """
            {"accessToken":"\(access)","refreshToken":"\(refresh)","tokenType":"Bearer","expiresIn":900,"accessTokenExpiresAt":"2026-08-26T16:39:00Z"}
            """
        )
    }

    static func apiError(
        status: Int,
        code: String,
        message: String = "error",
        requestId: String = "11111111-1111-1111-1111-111111111111",
        fieldErrors: String = "{}"
    ) -> Data {
        utf8(
            """
            {"timestamp":"2026-08-26T16:00:00Z","status":\(status),"code":"\(code)","message":"\(message)","path":"/api/v1/auth/login","requestId":"\(requestId)","fieldErrors":\(fieldErrors)}
            """
        )
    }

    static func utf8(_ string: String) -> Data {
        Data(string.utf8)
    }

    static func http(_ url: URL, status: Int, data: Data = Data()) -> (Data, HTTPURLResponse) {
        let response = HTTPURLResponse(
            url: url,
            statusCode: status,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "application/json", "X-Request-Id": "req-test"]
        )!
        return (data, response)
    }
}

actor FakeHTTPTransport: HTTPTransport {
    typealias Handler = @Sendable (URLRequest) async throws -> (Data, HTTPURLResponse)

    private let handler: Handler

    init(handler: @escaping Handler) {
        self.handler = handler
    }

    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        try await handler(request)
    }
}

enum TestConfig {
    static func httpsTest() throws -> AppConfig {
        try AppConfig.parse(
            baseURLString: "https://api.example.test/",
            allowsInsecureHTTP: false
        )
    }

    static func debugHTTP() throws -> AppConfig {
        try AppConfig.parse(
            baseURLString: "http://127.0.0.1:8080/",
            allowsInsecureHTTP: true
        )
    }

    static func repository(
        store: InMemorySessionStore,
        transport: FakeHTTPTransport,
        config: AppConfig
    ) -> AuthRepository {
        let refresh = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)
        return AuthRepository(client: client, store: store, refresh: refresh, config: config)
    }
}

func testSession(
    access: String = "access-1",
    refresh: String = "refresh-token-aaaaaaaa",
    user: CurrentUser = CurrentUser(
        id: TestJSON.userID,
        email: "demo@phokarta.local",
        username: "emir_demo",
        displayName: "Emir"
    )
) -> PersistedSession {
    PersistedSession(
        tokens: TokenPair(
            accessToken: access,
            refreshToken: refresh,
            tokenType: "Bearer",
            expiresIn: 900,
            accessTokenExpiresAt: "2026-08-26T16:24:00Z"
        ),
        user: user
    )
}
