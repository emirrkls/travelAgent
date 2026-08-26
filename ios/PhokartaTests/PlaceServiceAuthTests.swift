import XCTest
@testable import Phokarta

final class PlaceServiceAuthTests: XCTestCase {
    func testPlaceListUsesExistingAuthenticatedAPIClientPath() async throws {
        let store = InMemorySessionStore(session: testSession(access: "access-1", refresh: "refresh-token-aaaaaaaa"))
        let config = try TestConfig.httpsTest()
        let transport = FakeHTTPTransport { request in
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertEqual(request.url?.path, "/api/v1/places")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access-1")
            let items = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
            XCTAssertEqual(items.first(where: { $0.name == "sort" })?.value, "averageScore,desc")
            XCTAssertEqual(items.first(where: { $0.name == "page" })?.value, "0")
            XCTAssertEqual(items.first(where: { $0.name == "search" })?.value, "cafe")
            XCTAssertEqual(items.first(where: { $0.name == "category" })?.value, "CAFE")
            return TestJSON.http(request.url!, status: 200, data: Data(TestPlaces.summaryPageJSON.utf8))
        }
        let refresh = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)
        let service = PlaceService(client: client)
        let page = try await service.listPlaces(search: "cafe", category: .cafe, page: 0, size: 20)
        XCTAssertEqual(page.places.first?.id, TestPlaces.placeID)
        XCTAssertNil(page.places.first?.communityScore)
    }

    func testPlaceDetailRetryUsesSingleFlightRefresh() async throws {
        let store = InMemorySessionStore(session: testSession(access: "access-1", refresh: "refresh-token-aaaaaaaa"))
        let config = try TestConfig.httpsTest()
        actor Probe {
            var refreshCount = 0
            func handle(_ request: URLRequest) throws -> (Data, HTTPURLResponse) {
                let url = request.url!
                if url.path.hasSuffix("/api/v1/auth/refresh") {
                    refreshCount += 1
                    return TestJSON.http(
                        url,
                        status: 200,
                        data: TestJSON.tokens(access: "access-2", refresh: "refresh-token-bbbbbbbb")
                    )
                }
                XCTAssertEqual(url.path, "/api/v1/places/\(TestPlaces.placeID.uuidString.lowercased())")
                let authorization = request.value(forHTTPHeaderField: "Authorization")
                if authorization == "Bearer access-1" {
                    return TestJSON.http(
                        url,
                        status: 401,
                        data: TestJSON.apiError(status: 401, code: "TOKEN_EXPIRED")
                    )
                }
                XCTAssertEqual(authorization, "Bearer access-2")
                return TestJSON.http(url, status: 200, data: Data(TestPlaces.detailJSON.utf8))
            }
        }
        let probe = Probe()
        let transport = FakeHTTPTransport { request in
            try await probe.handle(request)
        }
        let refresh = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)
        let service = PlaceService(client: client)
        let detail = try await service.placeDetail(id: TestPlaces.placeID)
        XCTAssertEqual(detail.communityScore, 8.7)
        let refreshCount = await probe.refreshCount
        XCTAssertEqual(refreshCount, 1)
        XCTAssertEqual(await store.load()?.tokens.accessToken, "access-2")
    }

    func testFriendMetricsRequestBodyUsesPlaceIds() throws {
        let config = try TestConfig.debugHTTP()
        let client = APIClient(config: config, transport: URLSessionTransport.default)
        let request = try client.makeRequest(FriendMetricsEndpoint(placeIds: [TestPlaces.placeID]))
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/api/v1/me/places/friend-metrics")
        let json = try XCTUnwrap(request.httpBody).asJSONObject()
        let ids = try XCTUnwrap(json["placeIds"] as? [String])
        XCTAssertEqual(ids.count, 1)
        XCTAssertEqual(ids[0].lowercased(), TestPlaces.placeID.uuidString.lowercased())
    }
}

private extension Data {
    func asJSONObject() throws -> [String: Any] {
        let object = try JSONSerialization.jsonObject(with: self)
        return try XCTUnwrap(object as? [String: Any])
    }
}
