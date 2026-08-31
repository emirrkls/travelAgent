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

actor SavedServiceProbe: SavedPlaceServing {
    private var currentRows: [SavedPlaceDTO]
    private var mutationError: AppError?
    private(set) var mutations: [Bool] = []

    init(rows: [SavedPlaceDTO] = [], mutationError: AppError? = nil) {
        currentRows = rows
        self.mutationError = mutationError
    }

    func savedPlaces() async throws -> [SavedPlaceDTO] { currentRows }

    func setSaved(placeId: UUID, desired: Bool) async throws -> SavedPlaceDTO? {
        mutations.append(desired)
        if let mutationError { throw mutationError }
        if desired {
            let row = SavedPlaceDTO(place: TestPlaces.summary(id: placeId), savedAt: "2026-08-31T10:00:00Z")
            currentRows.removeAll { $0.place.id == placeId }
            currentRows.append(row)
            return row
        }
        currentRows.removeAll { $0.place.id == placeId }
        return nil
    }
}

actor GatedSavedService: SavedPlaceServing {
    private var fetch: CheckedContinuation<[SavedPlaceDTO], Error>?
    private var fetchStarted = false
    private var startWaiters: [CheckedContinuation<Void, Never>] = []

    func savedPlaces() async throws -> [SavedPlaceDTO] {
        fetchStarted = true
        startWaiters.forEach { $0.resume() }
        startWaiters.removeAll()
        return try await withCheckedThrowingContinuation { fetch = $0 }
    }

    func waitForFetch() async {
        guard !fetchStarted else { return }
        await withCheckedContinuation { startWaiters.append($0) }
    }

    func completeFetch(_ rows: [SavedPlaceDTO]) {
        fetch?.resume(returning: rows)
        fetch = nil
    }

    func setSaved(placeId: UUID, desired: Bool) async throws -> SavedPlaceDTO? {
        desired ? SavedPlaceDTO(place: TestPlaces.summary(id: placeId), savedAt: "2026-08-31T10:00:00Z") : nil
    }
}

actor CollectionServiceProbe: CollectionServing {
    private var listRows: [CollectionSummary]
    private var detailRows: [UUID: CollectionDetail]
    private var createError: AppError?
    private(set) var addCalls = 0
    private(set) var removeCalls = 0

    init(
        summaries: [CollectionSummary] = [],
        details: [UUID: CollectionDetail] = [:],
        createError: AppError? = nil
    ) {
        listRows = summaries
        detailRows = details
        self.createError = createError
    }

    func collections() async throws -> [CollectionSummary] { listRows }

    func create(_ request: CreateCollectionRequestDTO) async throws -> CollectionDetail {
        if let createError { throw createError }
        let detail = TestCollections.detail(title: request.title, visibility: request.visibility)
        detailRows[detail.id] = detail
        listRows.insert(TestCollections.summary(from: detail), at: 0)
        return detail
    }

    func detail(id: UUID) async throws -> CollectionDetail {
        guard let value = detailRows[id] else { throw AppError.notFound }
        return value
    }

    func add(placeId: UUID, to collectionId: UUID) async throws -> CollectionDetail {
        addCalls += 1
        guard let old = detailRows[collectionId] else { throw AppError.notFound }
        if old.places.contains(where: { $0.place.id == placeId }) { throw AppError.conflict(code: "CONFLICT") }
        let row = CollectionPlace(
            place: TestPlaces.summary(id: placeId),
            displayOrder: old.places.count,
            addedAt: "2026-08-31T10:00:00Z"
        )
        let next = TestCollections.copy(old, places: old.places + [row])
        detailRows[collectionId] = next
        return next
    }

    func remove(placeId: UUID, from collectionId: UUID) async throws {
        removeCalls += 1
        guard let old = detailRows[collectionId] else { throw AppError.notFound }
        detailRows[collectionId] = TestCollections.copy(
            old,
            places: old.places.filter { $0.place.id != placeId }
        )
    }
}

actor GatedCollectionService: CollectionServing {
    private var canonical: CollectionDetail
    private var listGate: CheckedContinuation<[CollectionSummary], Error>?
    private var detailGate: CheckedContinuation<CollectionDetail, Error>?
    private var listStarted = false
    private var detailStarted = false
    private var listWaiters: [CheckedContinuation<Void, Never>] = []
    private var detailWaiters: [CheckedContinuation<Void, Never>] = []

    init(detail: CollectionDetail = TestCollections.detail()) { canonical = detail }

    func collections() async throws -> [CollectionSummary] {
        listStarted = true
        listWaiters.forEach { $0.resume() }
        listWaiters.removeAll()
        return try await withCheckedThrowingContinuation { listGate = $0 }
    }

    func waitForList() async {
        guard !listStarted else { return }
        await withCheckedContinuation { listWaiters.append($0) }
    }

    func completeList(_ rows: [CollectionSummary]) {
        listGate?.resume(returning: rows)
        listGate = nil
    }

    func create(_ request: CreateCollectionRequestDTO) async throws -> CollectionDetail {
        canonical = TestCollections.detail(title: request.title, visibility: request.visibility)
        return canonical
    }

    func detail(id: UUID) async throws -> CollectionDetail {
        detailStarted = true
        detailWaiters.forEach { $0.resume() }
        detailWaiters.removeAll()
        return try await withCheckedThrowingContinuation { detailGate = $0 }
    }

    func waitForDetail() async {
        guard !detailStarted else { return }
        await withCheckedContinuation { detailWaiters.append($0) }
    }

    func completeDetail(_ value: CollectionDetail) {
        detailGate?.resume(returning: value)
        detailGate = nil
    }

    func add(placeId: UUID, to collectionId: UUID) async throws -> CollectionDetail {
        let row = CollectionPlace(
            place: TestPlaces.summary(id: placeId),
            displayOrder: canonical.places.count,
            addedAt: "2026-08-31T10:00:00Z"
        )
        canonical = TestCollections.copy(canonical, places: canonical.places + [row])
        return canonical
    }

    func remove(placeId: UUID, from collectionId: UUID) async throws {
        canonical = TestCollections.copy(canonical, places: canonical.places.filter { $0.place.id != placeId })
    }
}

enum TestCollections {
    static let id = UUID(uuidString: "40000000-0000-0000-0000-000000000001")!

    static func detail(
        id: UUID = TestCollections.id,
        userID: UUID = TestJSON.userID,
        title: String = "Summer",
        visibility: CollectionVisibility = .privateAccess,
        places: [CollectionPlace] = []
    ) -> CollectionDetail {
        CollectionDetail(
            id: id,
            userId: userID,
            title: title,
            description: "",
            visibility: visibility,
            coverImage: "https://example.test/cover.jpg",
            createdAt: "2026-08-31T09:00:00Z",
            updatedAt: "2026-08-31T10:00:00Z",
            places: places
        )
    }

    static func summary(from detail: CollectionDetail) -> CollectionSummary {
        CollectionSummary(
            id: detail.id,
            userId: detail.userId,
            title: detail.title,
            description: detail.description,
            visibility: detail.visibility,
            coverImage: detail.coverImage,
            placeCount: Int64(detail.places.count),
            updatedAt: detail.updatedAt
        )
    }

    static func copy(_ detail: CollectionDetail, places: [CollectionPlace]) -> CollectionDetail {
        CollectionDetail(
            id: detail.id,
            userId: detail.userId,
            title: detail.title,
            description: detail.description,
            visibility: detail.visibility,
            coverImage: detail.coverImage,
            createdAt: detail.createdAt,
            updatedAt: "2026-08-31T11:00:00Z",
            places: places
        )
    }

    static func detailJSON() -> String {
        """
        {
          "id":"\(id.uuidString.lowercased())",
          "userId":"\(TestJSON.userID.uuidString.lowercased())",
          "title":"Summer",
          "description":"Sea",
          "visibility":"PRIVATE",
          "coverImage":"https://example.test/cover.jpg",
          "createdAt":"2026-08-31T09:00:00Z",
          "updatedAt":"2026-08-31T10:00:00Z",
          "places":[{
            "place":\(TestPlaces.summaryPageJSON.extractSummaryForFixture()),
            "displayOrder":0,
            "addedAt":"2026-08-31T10:00:00Z"
          }]
        }
        """
    }
}

private extension String {
    func extractSummaryForFixture() -> String {
        let prefix = "\"content\": [{"
        guard let start = range(of: prefix)?.upperBound,
              let end = range(of: "}],", range: start..<endIndex)?.lowerBound else { return "{}" }
        return "{" + String(self[start..<end]) + "}"
    }
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
