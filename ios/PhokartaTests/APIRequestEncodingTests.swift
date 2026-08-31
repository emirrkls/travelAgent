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

    func testPlaceListQueryUsesBackendParameterNames() throws {
        let config = try TestConfig.debugHTTP()
        let client = APIClient(config: config, transport: URLSessionTransport.default)
        let request = try client.makeRequest(
            PlaceListEndpoint(category: .cafe, search: "bodrum", page: 1, size: 20)
        )
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.url?.path, "/api/v1/places")
        let items = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
        let byName = Dictionary(uniqueKeysWithValues: items.compactMap { item in
            item.value.map { (item.name, $0) }
        })
        XCTAssertEqual(byName["category"], "CAFE")
        XCTAssertEqual(byName["search"], "bodrum")
        XCTAssertEqual(byName["sort"], "averageScore,desc")
        XCTAssertEqual(byName["page"], "1")
        XCTAssertEqual(byName["size"], "20")
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

    func testSavedDesiredStateEndpointsMatchBackendContract() throws {
        let client = APIClient(config: try TestConfig.debugHTTP(), transport: URLSessionTransport.default)
        let fetch = try client.makeRequest(SavedPlacesEndpoint(page: 2, size: 100))
        XCTAssertEqual(fetch.httpMethod, "GET")
        XCTAssertEqual(fetch.url?.path, "/api/v1/me/saved-places")
        XCTAssertEqual(URLComponents(url: fetch.url!, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "page" })?.value, "2")
        let save = try client.makeRequest(SavePlaceEndpoint(placeId: TestPlaces.placeID))
        XCTAssertEqual(save.httpMethod, "POST")
        XCTAssertNil(save.httpBody)
        let remove = try client.makeRequest(UnsavePlaceEndpoint(placeId: TestPlaces.placeID))
        XCTAssertEqual(remove.httpMethod, "DELETE")
        XCTAssertEqual(save.url?.path, remove.url?.path)
    }

    func testCreateCollectionBodyUsesOnlyExactBackendFields() throws {
        let client = APIClient(config: try TestConfig.debugHTTP(), transport: URLSessionTransport.default)
        let endpoint = CreateCollectionEndpoint(body: CreateCollectionRequestDTO(
            title: "Summer",
            description: "Sea",
            visibility: .friends,
            coverImage: "https://example.test/cover.jpg"
        ))
        let request = try client.makeRequest(endpoint)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/api/v1/me/collections")
        let json = try XCTUnwrap(request.httpBody).asJSONObject()
        XCTAssertEqual(json["title"] as? String, "Summer")
        XCTAssertEqual(json["description"] as? String, "Sea")
        XCTAssertEqual(json["visibility"] as? String, "FRIENDS")
        XCTAssertEqual(json["coverImage"] as? String, "https://example.test/cover.jpg")
        XCTAssertEqual(json.count, 4)
    }

    func testCollectionListDetailAddAndRemovePaths() throws {
        let client = APIClient(config: try TestConfig.debugHTTP(), transport: URLSessionTransport.default)
        let collectionID = TestCollections.id
        let list = try client.makeRequest(CollectionsEndpoint(page: 0, size: 100))
        XCTAssertEqual(list.url?.path, "/api/v1/me/collections")
        let detail = try client.makeRequest(CollectionDetailEndpoint(collectionId: collectionID))
        XCTAssertEqual(detail.httpMethod, "GET")
        XCTAssertEqual(detail.url?.path, "/api/v1/collections/\(collectionID.uuidString.lowercased())")
        let add = try client.makeRequest(AddCollectionPlaceEndpoint(collectionId: collectionID, placeId: TestPlaces.placeID))
        let remove = try client.makeRequest(RemoveCollectionPlaceEndpoint(collectionId: collectionID, placeId: TestPlaces.placeID))
        XCTAssertEqual(add.httpMethod, "POST")
        XCTAssertEqual(remove.httpMethod, "DELETE")
        XCTAssertEqual(add.url?.path, remove.url?.path)
    }
}

private extension Data {
    func asJSONObject() throws -> [String: Any] {
        let object = try JSONSerialization.jsonObject(with: self)
        return try XCTUnwrap(object as? [String: Any])
    }
}
