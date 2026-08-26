import XCTest
@testable import Phokarta

final class APIRequestEncodingTests: XCTestCase {
    func testLoginBodyUsesCamelCaseAndExactContractKeys() throws {
        let config = try TestConfig.debugHTTP()
        let client = APIClient(config: config, transport: URLSessionTransport.default)
        let request = try client.makeRequest(
            LoginEndpoint(body: LoginRequestDTO(identifier: "demo@phokarta.local", password: "DemoPass123!"))
        )
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/api/v1/auth/login")
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
        let json = try XCTUnwrap(request.httpBody).asJSONObject()
        XCTAssertEqual(json["identifier"] as? String, "demo@phokarta.local")
        XCTAssertEqual(json["password"] as? String, "DemoPass123!")
        XCTAssertEqual(json.count, 2)
    }

    func testRegisterBodyMatchesBackendRequiredFields() throws {
        let config = try TestConfig.debugHTTP()
        let client = APIClient(config: config, transport: URLSessionTransport.default)
        let request = try client.makeRequest(
            RegisterEndpoint(
                body: RegisterRequestDTO(
                    email: "traveler@example.com",
                    username: "traveler_1",
                    displayName: "Traveler",
                    password: "SecurePass1"
                )
            )
        )
        XCTAssertEqual(request.url?.path, "/api/v1/auth/register")
        let json = try XCTUnwrap(request.httpBody).asJSONObject()
        XCTAssertEqual(json["email"] as? String, "traveler@example.com")
        XCTAssertEqual(json["username"] as? String, "traveler_1")
        XCTAssertEqual(json["displayName"] as? String, "Traveler")
        XCTAssertEqual(json["password"] as? String, "SecurePass1")
        XCTAssertNil(json["bio"])
    }

    func testRefreshAndLogoutBodiesAreCamelCaseRefreshToken() throws {
        let config = try TestConfig.debugHTTP()
        let client = APIClient(config: config, transport: URLSessionTransport.default)
        let refresh = try client.makeRequest(RefreshEndpoint(refreshToken: "refresh-token-aaaaaaaa"))
        XCTAssertEqual(try XCTUnwrap(refresh.httpBody).asJSONObject()["refreshToken"] as? String, "refresh-token-aaaaaaaa")
        let logout = try client.makeRequest(LogoutEndpoint(refreshToken: "refresh-token-aaaaaaaa"))
        XCTAssertEqual(logout.httpMethod, "POST")
        XCTAssertEqual(try XCTUnwrap(logout.httpBody).asJSONObject()["refreshToken"] as? String, "refresh-token-aaaaaaaa")
    }

    func testMePathAndAuthRequirement() throws {
        let config = try TestConfig.debugHTTP()
        let client = APIClient(config: config, transport: URLSessionTransport.default)
        let request = try client.makeRequest(MeEndpoint())
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.url?.path, "/api/v1/me")
        XCTAssertNil(request.httpBody)
    }

    func testSecretDTOsDoNotPrintPasswordOrTokens() {
        let login = LoginRequestDTO(identifier: "a", password: "super-secret")
        XCTAssertFalse(String(describing: login).contains("super-secret"))
        XCTAssertFalse(String(reflecting: login).contains("super-secret"))
        let tokens = TokenPair(
            accessToken: "access-secret",
            refreshToken: "refresh-secret",
            tokenType: "Bearer",
            expiresIn: 1,
            accessTokenExpiresAt: nil
        )
        XCTAssertFalse(String(describing: tokens).contains("access-secret"))
        XCTAssertFalse(String(describing: tokens).contains("refresh-secret"))
    }
}

private extension Data {
    func asJSONObject() throws -> [String: Any] {
        let object = try JSONSerialization.jsonObject(with: self)
        return try XCTUnwrap(object as? [String: Any])
    }
}
