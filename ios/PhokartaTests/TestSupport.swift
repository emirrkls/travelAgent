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

enum TestPlaces {
    static let placeID = UUID(uuidString: "20000000-0000-0000-0000-000000000001")!
    static let cafeID = UUID(uuidString: "20000000-0000-0000-0000-000000000002")!
    static let reviewID = UUID(uuidString: "30000000-0000-0000-0000-000000000001")!
    static let authorID = UUID(uuidString: "11111111-1111-1111-1111-111111111111")!

    static func summary(
        id: UUID = placeID,
        name: String = "Fixture Beach",
        category: PlaceCategory = .beach,
        communityScore: Double? = 8.7,
        ratingCount: Int64 = 4
    ) -> PlaceSummary {
        PlaceSummary(
            id: id,
            name: name,
            category: category,
            coverImage: "https://example.test/beach.jpg",
            city: "Antalya",
            region: "Mediterranean",
            country: "Turkey",
            latitude: 36.8969,
            longitude: 30.7133,
            priceLevel: 2,
            communityScore: communityScore,
            ratingCount: ratingCount
        )
    }

    static func page(
        places: [PlaceSummary],
        page: Int = 0,
        hasNext: Bool = false,
        totalElements: Int64? = nil
    ) -> PlacePage {
        PlacePage(
            places: places,
            page: page,
            totalPages: hasNext ? page + 2 : page + 1,
            totalElements: totalElements ?? Int64(places.count),
            hasNext: hasNext
        )
    }

    static func detail(
        id: UUID = placeID,
        name: String = "Fixture Beach",
        communityScore: Double? = 8.7,
        ratingCount: Int64 = 4,
        reviews: [ReviewSummary] = []
    ) -> PlaceDetail {
        PlaceDetail(
            id: id,
            name: name,
            description: "A quiet cove.",
            category: .beach,
            subcategories: ["cove"],
            latitude: 36.8969,
            longitude: 30.7133,
            city: "Antalya",
            region: "Mediterranean",
            country: "Turkey",
            address: "Kaleiçi",
            coverImage: "https://example.test/beach.jpg",
            photos: ["https://example.test/beach-2.jpg"],
            priceLevel: 2,
            communityScore: communityScore,
            ratingCount: ratingCount,
            dimensionScores: [DimensionAggregate(key: "SEA", average: 9.1)],
            recentPublicReviews: reviews
        )
    }

    static func review(
        id: UUID = reviewID,
        rating: Double = 9.0,
        text: String = "Visible review"
    ) -> ReviewSummary {
        ReviewSummary(
            id: id,
            placeId: placeID,
            placeName: "Fixture Beach",
            userId: authorID,
            username: "demo",
            displayName: "Demo User",
            avatarUrl: nil,
            visitedAt: "2026-08-22",
            overallRating: rating,
            publicReview: text
        )
    }

    static let summaryPageJSON = """
    {
      "content": [{
        "id": "20000000-0000-0000-0000-000000000001",
        "name": "Fixture Beach",
        "category": "BEACH",
        "coverImage": "https://example.test/beach.jpg",
        "city": "Antalya",
        "region": "Mediterranean",
        "country": "Turkey",
        "latitude": 36.8969,
        "longitude": 30.7133,
        "priceLevel": 2,
        "averageScore": null,
        "ratingCount": 0
      }],
      "page": 0,
      "size": 20,
      "totalElements": 1,
      "totalPages": 1,
      "hasNext": false
    }
    """

    static let detailJSON = """
    {
      "id": "20000000-0000-0000-0000-000000000001",
      "name": "Fixture Beach",
      "description": "A quiet cove.",
      "category": "BEACH",
      "subcategories": ["cove"],
      "latitude": 36.8969,
      "longitude": 30.7133,
      "city": "Antalya",
      "region": "Mediterranean",
      "country": "Turkey",
      "address": "Kaleiçi",
      "coverImage": "https://example.test/beach.jpg",
      "photos": ["https://example.test/beach-2.jpg"],
      "priceLevel": 2,
      "averageScore": 8.7,
      "ratingCount": 4,
      "dimensionScores": [{"key": "SEA", "average": 9.1}],
      "recentPublicReviews": [{
        "id": "30000000-0000-0000-0000-000000000001",
        "placeId": "20000000-0000-0000-0000-000000000001",
        "placeName": "Fixture Beach",
        "userId": "11111111-1111-1111-1111-111111111111",
        "username": "demo",
        "displayName": "Demo User",
        "avatarUrl": null,
        "visitedAt": "2026-08-22",
        "overallRating": 9.1,
        "publicReview": "Visible review",
        "photos": [],
        "media": [],
        "verificationStatus": "UNVERIFIED"
      }]
    }
    """

    static let friendsSummaryJSON = """
    {
      "averageScore": 8.2,
      "friendsVisitedCount": 2,
      "friends": [{
        "userId": "11111111-1111-1111-1111-111111111112",
        "displayName": "Ada",
        "avatarUrl": null,
        "latestScore": 8.5,
        "latestVisitedAt": "2026-08-20"
      }]
    }
    """

    static let ownerVisitJSON = """
    {
      "content": [{
        "id": "30000000-0000-0000-0000-000000000099",
        "place": {
          "id": "20000000-0000-0000-0000-000000000001",
          "name": "Fixture Beach",
          "category": "BEACH",
          "coverImage": "https://example.test/beach.jpg",
          "city": "Antalya",
          "region": "Mediterranean",
          "country": "Turkey",
          "latitude": 36.8969,
          "longitude": 30.7133,
          "priceLevel": 2,
          "averageScore": 8.7,
          "ratingCount": 4
        },
        "visitedAt": "2026-08-21",
        "overallRating": 9.0,
        "dimensions": [],
        "publicReview": "Mine",
        "privateMemory": "must-not-surface",
        "photos": [],
        "media": [],
        "visibility": "PRIVATE",
        "verificationStatus": "UNVERIFIED"
      }],
      "page": 0,
      "size": 20,
      "totalElements": 1,
      "totalPages": 1,
      "hasNext": false
    }
    """
}

final class FakePlaceService: PlaceServing, @unchecked Sendable {
    var listHandler: (@Sendable (String?, PlaceCategory?, Int, Int) async throws -> PlacePage)?
    var detailHandler: (@Sendable (UUID) async throws -> PlaceDetail)?
    var reviewsHandler: (@Sendable (UUID, ReviewScope, Int, Int) async throws -> ReviewPage)?
    var friendsHandler: (@Sendable (UUID) async throws -> FriendPlaceSummary)?
    var metricsHandler: (@Sendable ([UUID]) async throws -> [FriendPlaceMetrics])?
    var savedIDs: Set<UUID> = []
    var visits: [OwnerVisitSummary] = []
    var listCalls: [(String?, PlaceCategory?, Int, Int)] = []

    func listPlaces(search: String?, category: PlaceCategory?, page: Int, size: Int) async throws -> PlacePage {
        listCalls.append((search, category, page, size))
        if let listHandler {
            return try await listHandler(search, category, page, size)
        }
        return TestPlaces.page(places: [])
    }

    func placeDetail(id: UUID) async throws -> PlaceDetail {
        if let detailHandler {
            return try await detailHandler(id)
        }
        return TestPlaces.detail(id: id)
    }

    func reviews(placeId: UUID, scope: ReviewScope, page: Int, size: Int) async throws -> ReviewPage {
        if let reviewsHandler {
            return try await reviewsHandler(placeId, scope, page, size)
        }
        return ReviewPage(reviews: [], page: 0, totalElements: 0, hasNext: false)
    }

    func friendsSummary(placeId: UUID) async throws -> FriendPlaceSummary {
        if let friendsHandler {
            return try await friendsHandler(placeId)
        }
        return FriendPlaceSummary(averageScore: nil, friendsVisitedCount: 0, friends: [])
    }

    func friendMetrics(placeIds: [UUID]) async throws -> [FriendPlaceMetrics] {
        if let metricsHandler {
            return try await metricsHandler(placeIds)
        }
        return []
    }

    func savedPlaceIDs() async throws -> Set<UUID> { savedIDs }

    func ownerVisits() async throws -> [OwnerVisitSummary] { visits }
}

actor GatedPlaceService: PlaceServing {
    private var listGates: [String: CheckedContinuation<PlacePage, Error>] = [:]
    var recordedSearches: [String] = []

    func listPlaces(search: String?, category: PlaceCategory?, page: Int, size: Int) async throws -> PlacePage {
        let key = search ?? ""
        recordedSearches.append(key)
        return try await withCheckedThrowingContinuation { continuation in
            listGates[key] = continuation
        }
    }

    func complete(search: String, page: PlacePage) {
        listGates[search]?.resume(returning: page)
        listGates.removeValue(forKey: search)
    }

    func fail(search: String, error: AppError) {
        listGates[search]?.resume(throwing: error)
        listGates.removeValue(forKey: search)
    }

    func placeDetail(id: UUID) async throws -> PlaceDetail { TestPlaces.detail(id: id) }

    func reviews(placeId: UUID, scope: ReviewScope, page: Int, size: Int) async throws -> ReviewPage {
        ReviewPage(reviews: [], page: 0, totalElements: 0, hasNext: false)
    }

    func friendsSummary(placeId: UUID) async throws -> FriendPlaceSummary {
        FriendPlaceSummary(averageScore: nil, friendsVisitedCount: 0, friends: [])
    }

    func friendMetrics(placeIds: [UUID]) async throws -> [FriendPlaceMetrics] { [] }
    func savedPlaceIDs() async throws -> Set<UUID> { [] }
    func ownerVisits() async throws -> [OwnerVisitSummary] { [] }
}

actor TestValue<Value: Sendable> {
    private var value: Value

    init(_ value: Value) {
        self.value = value
    }

    func read() -> Value {
        value
    }

    func set(_ value: Value) {
        self.value = value
    }
}

actor TestCounter {
    private var count = 0

    func increment() {
        count += 1
    }

    func read() -> Int {
        count
    }
}
