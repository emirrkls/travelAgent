import XCTest
@testable import Phokarta

final class VisitContractTests: XCTestCase {
    func testCreateRequestUsesExactEndpointAndWireKeys() throws {
        let client = APIClient(config: try TestConfig.debugHTTP(), transport: URLSessionTransport.default)
        let mutation = UUID(uuidString: "10000000-0000-4000-8000-000000000001")!
        let request = try client.makeRequest(CreateVisitEndpoint(body: VisitCreateRequest(
            clientMutationId: mutation,
            placeId: TestPlaces.placeID,
            visitedAt: "2026-09-01",
            overallRating: 8.7,
            dimensions: [VisitDimensionScore(key: "SEA", score: 9.1)],
            publicReview: "Public",
            privateMemory: "Private",
            visibility: .friends
        )))
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/api/v1/visits")
        let json = try XCTUnwrap(request.httpBody).visitJSONObject()
        XCTAssertEqual(json["clientMutationId"] as? String, mutation.uuidString.uppercased())
        XCTAssertEqual(json["placeId"] as? String, TestPlaces.placeID.uuidString.uppercased())
        XCTAssertEqual(json["visitedAt"] as? String, "2026-09-01")
        XCTAssertEqual(json["overallRating"] as? Double, 8.7)
        XCTAssertEqual(json["publicReview"] as? String, "Public")
        XCTAssertEqual(json["privateMemory"] as? String, "Private")
        XCTAssertEqual(json["visibility"] as? String, "FRIENDS")
        XCTAssertNil(json["photos"])
        XCTAssertNil(json["mediaIds"])
        let dimension = try XCTUnwrap((json["dimensions"] as? [[String: Any]])?.first)
        XCTAssertEqual(dimension["key"] as? String, "SEA")
        XCTAssertEqual(dimension["score"] as? Double, 9.1)
    }

    func testMinimalRequestOmitsWhitespaceTextAndOptionalDimensionsRemainEmpty() throws {
        let state = VisitComposerState(
            placeId: TestPlaces.placeID,
            placeName: "Beach",
            category: .beach,
            publicReview: "   ",
            privateMemory: "\n",
            clientMutationId: UUID()
        )
        XCTAssertNil(VisitValidation.trimmedOptional(state.publicReview))
        XCTAssertNil(VisitValidation.trimmedOptional(state.privateMemory))
        XCTAssertTrue(state.dimensionScores.isEmpty)
    }

    func testOwnerResponseDecodesOwnerPrivateFieldsAndDecimalScores() throws {
        let page = try APIJSON.decoder.decode(PageDTO<OwnerVisit>.self, from: Data(TestPlaces.ownerVisitJSON.utf8))
        let visit = try XCTUnwrap(page.content.first)
        XCTAssertEqual(visit.overallRating, 9.0)
        XCTAssertEqual(visit.privateMemory, "must-not-surface")
        XCTAssertEqual(visit.visibility, .privateAccess)
        XCTAssertEqual(visit.place.id, TestPlaces.placeID)
    }

    func testOwnerResponseAllowsNullableTextAndMissingDimensions() throws {
        let json = """
        {"id":"30000000-0000-0000-0000-000000000001","place":\(TestPlaces.summaryPageJSON.visitSummaryFixture()),"visitedAt":"2026-09-01","overallRating":0.0,"publicReview":null,"privateMemory":null,"visibility":"PUBLIC"}
        """
        let visit = try APIJSON.decoder.decode(OwnerVisit.self, from: Data(json.utf8))
        XCTAssertEqual(visit.publicReview, "")
        XCTAssertEqual(visit.privateMemory, "")
        XCTAssertTrue(visit.dimensions.isEmpty)
        XCTAssertEqual(visit.overallRating, 0.0)
    }

    func testOwnerHistoryEndpointUsesBackendPaginationContract() throws {
        let client = APIClient(config: try TestConfig.debugHTTP(), transport: URLSessionTransport.default)
        let request = try client.makeRequest(MyVisitsEndpoint(page: 2, size: 100))
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.url?.path, "/api/v1/me/visits")
        let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
        XCTAssertEqual(query.first(where: { $0.name == "page" })?.value, "2")
        XCTAssertEqual(query.first(where: { $0.name == "size" })?.value, "100")
    }

    func testVisibilityWireValuesAndLocalizationKeys() {
        XCTAssertEqual(VisitVisibility.publicAccess.rawValue, "PUBLIC")
        XCTAssertEqual(VisitVisibility.friends.rawValue, "FRIENDS")
        XCTAssertEqual(VisitVisibility.privateAccess.rawValue, "PRIVATE")
        XCTAssertEqual(VisitVisibility.publicAccess.localizationKey, "visit.visibility.public")
    }

    func testPublicReviewModelStructurallyExcludesPrivateMemory() {
        let fields = Mirror(reflecting: TestPlaces.review()).children.compactMap(\.label)
        XCTAssertFalse(fields.contains("privateMemory"))
        XCTAssertFalse(fields.contains("personalNote"))
    }

    func testAllBackendCategoriesUseExactDimensionWireKeys() {
        XCTAssertEqual(VisitDimensionCatalog.keys(for: .beach), ["SEA", "ATMOSPHERE", "SERVICE", "CLEANLINESS", "VALUE", "CROWD"])
        XCTAssertEqual(VisitDimensionCatalog.keys(for: .restaurant), ["FOOD", "SERVICE", "ATMOSPHERE", "VALUE", "PRESENTATION"])
        XCTAssertEqual(VisitDimensionCatalog.keys(for: .hotel), ["CLEANLINESS", "LOCATION", "ROOM", "SERVICE", "BREAKFAST", "VALUE"])
        XCTAssertEqual(VisitDimensionCatalog.keys(for: .activity), ["EXPERIENCE", "SAFETY", "GUIDE", "VALUE"])
        XCTAssertTrue(VisitDimensionCatalog.keys(for: .unknown).isEmpty)
    }

    func testPublishUsesExistingSingleFlightTokenRefreshPath() async throws {
        let sessionStore = InMemorySessionStore(session: testSession(access: "access-1"))
        let config = try TestConfig.httpsTest()
        actor Probe {
            var refreshCount = 0
            func handle(_ request: URLRequest) -> (Data, HTTPURLResponse) {
                let url = request.url!
                if url.path == "/api/v1/auth/refresh" {
                    refreshCount += 1
                    return TestJSON.http(url, status: 200,
                        data: TestJSON.tokens(access: "access-2", refresh: "refresh-token-bbbbbbbb"))
                }
                XCTAssertEqual(url.path, "/api/v1/visits")
                if request.value(forHTTPHeaderField: "Authorization") == "Bearer access-1" {
                    return TestJSON.http(url, status: 401,
                        data: TestJSON.apiError(status: 401, code: "TOKEN_EXPIRED"))
                }
                XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access-2")
                return TestJSON.http(url, status: 201,
                    data: Data(TestPlaces.ownerVisitJSON.visitOwnerFixture().utf8))
            }
        }
        let probe = Probe()
        let transport = FakeHTTPTransport { request in await probe.handle(request) }
        let refresh = TokenRefreshCoordinator(store: sessionStore, config: config, transport: transport)
        let service = VisitService(client: APIClient(config: config, transport: transport, authRetry: refresh))
        let canonical = try await service.create(VisitCreateRequest(
            clientMutationId: UUID(), placeId: TestPlaces.placeID, visitedAt: "2026-09-01",
            overallRating: 8, dimensions: [], publicReview: nil, privateMemory: nil,
            visibility: .publicAccess
        ))
        XCTAssertEqual(canonical.id, UUID(uuidString: "30000000-0000-0000-0000-000000000099")!)
        let count = await probe.refreshCount
        XCTAssertEqual(count, 1)
    }
}

@MainActor
final class VisitValidationTests: XCTestCase {
    func testDefaultDraftIsValidAndNotDirty() {
        let state = fixtureState()
        XCTAssertNil(VisitValidation.validate(state))
        XCTAssertFalse(state.isDirty)
        XCTAssertTrue(state.canPublish)
    }

    func testZeroIsAValidExplicitOverallScore() {
        var state = fixtureState()
        state.overallScore = 0
        XCTAssertNil(VisitValidation.validate(state))
    }

    func testOutOfRangeOverallFails() {
        var state = fixtureState()
        state.overallScore = 10.1
        XCTAssertEqual(VisitValidation.validate(state), .overallOutOfRange)
    }

    func testFutureDateFails() {
        var state = fixtureState()
        state.visitedAt = Date(timeIntervalSinceNow: 172_800)
        XCTAssertEqual(VisitValidation.validate(state), .futureDate)
    }

    func testTextLimitsMatchBackend() {
        var state = fixtureState()
        state.publicReview = String(repeating: "a", count: 4_001)
        XCTAssertEqual(VisitValidation.validate(state), .reviewTooLong)
        state.publicReview = ""
        state.privateMemory = String(repeating: "b", count: 4_001)
        XCTAssertEqual(VisitValidation.validate(state), .privateMemoryTooLong)
    }

    func testInvalidCategoryDimensionFailsAndOptionalOmissionPasses() {
        var state = fixtureState(category: .beach)
        XCTAssertNil(VisitValidation.validate(state))
        state.dimensionScores["FOOD"] = 8
        XCTAssertEqual(VisitValidation.validate(state), .invalidDimension)
    }

    private func fixtureState(category: PlaceCategory = .beach) -> VisitComposerState {
        VisitComposerState(
            placeId: TestPlaces.placeID,
            placeName: "Beach",
            category: category,
            clientMutationId: UUID()
        )
    }
}

@MainActor
final class VisitComposerControllerTests: XCTestCase {
    func testDoublePublishSendsOneNetworkCreation() async {
        let service = GatedVisitService(canonical: Self.visit())
        let store = activatedStore(service)
        let controller = VisitComposerController(place: TestPlaces.detail(), store: store)

        let first = Task { await controller.publish() }
        await service.waitForCreate()
        let second = Task { await controller.publish() }
        await Task.yield()
        let countBeforeComplete = await service.createCount()
        XCTAssertEqual(countBeforeComplete, 1)
        await service.complete()
        _ = await first.value
        let secondResult = await second.value
        XCTAssertNil(secondResult)
        let finalCount = await service.createCount()
        XCTAssertEqual(finalCount, 1)
    }

    func testLostAckRetryReusesMutationIDAndCanonicalVisit() async {
        let service = VisitServiceProbe(results: [.failure(.networkUnavailable), .success(Self.visit())])
        let store = activatedStore(service)
        let mutation = UUID(uuidString: "10000000-0000-4000-8000-000000000010")!
        let controller = VisitComposerController(place: TestPlaces.detail(), store: store, uuid: { mutation })
        let firstResult = await controller.publish()
        XCTAssertNil(firstResult)
        XCTAssertEqual(controller.state.publishState, .retryableFailure(.networkUnavailable))
        let canonical = await controller.publish()
        XCTAssertEqual(canonical?.id, Self.visitID)
        let requests = await service.requests()
        XCTAssertEqual(requests.count, 2)
        XCTAssertEqual(requests[0].clientMutationId, requests[1].clientMutationId)
        XCTAssertEqual(store.visits.count, 1)
    }

    func testMaterialEditAfterSentAttemptCreatesNewMutationID() async {
        let ids = [
            UUID(uuidString: "10000000-0000-4000-8000-000000000011")!,
            UUID(uuidString: "10000000-0000-4000-8000-000000000012")!
        ]
        var index = 0
        let service = VisitServiceProbe(results: [.failure(.networkUnavailable), .success(Self.visit())])
        let store = activatedStore(service)
        let controller = VisitComposerController(place: TestPlaces.detail(), store: store, uuid: {
            defer { index += 1 }
            return ids[min(index, ids.count - 1)]
        })
        _ = await controller.publish()
        controller.setReview("Changed logical payload")
        _ = await controller.publish()
        let requests = await service.requests()
        XCTAssertEqual(requests.map(\.clientMutationId), ids)
    }

    func testPolicyRequiredPreservesDraftAndDoesNotCreateVisit() async {
        let service = VisitServiceProbe(results: [.failure(.policyAcceptanceRequired(requiredVersion: "2026-08"))])
        let store = activatedStore(service)
        let controller = VisitComposerController(place: TestPlaces.detail(), store: store)
        controller.setOverall(9.2)
        controller.setReview("Keep me")
        controller.setPrivateMemory("Owner secret")
        let result = await controller.publish()
        XCTAssertNil(result)
        XCTAssertEqual(controller.state.publishState, .policyRequired(requiredVersion: "2026-08"))
        XCTAssertEqual(controller.state.publicReview, "Keep me")
        XCTAssertEqual(controller.state.privateMemory, "Owner secret")
        XCTAssertTrue(store.visits.isEmpty)
    }

    func testRetryableFailurePreservesComposerWithoutFakeSuccess() async {
        let service = VisitServiceProbe(results: [.failure(.timeout)])
        let store = activatedStore(service)
        let controller = VisitComposerController(place: TestPlaces.detail(), store: store)
        controller.setPrivateMemory("Still here")
        _ = await controller.publish()
        XCTAssertEqual(controller.state.privateMemory, "Still here")
        XCTAssertEqual(controller.state.publishState, .retryableFailure(.timeout))
        XCTAssertTrue(store.visits.isEmpty)
    }

    func testServerValidationPreservesDraftWithoutRetryLoop() async {
        let service = VisitServiceProbe(results: [.failure(.validation(message: "bad", fields: [:]))])
        let store = activatedStore(service)
        let controller = VisitComposerController(place: TestPlaces.detail(), store: store)
        controller.setReview("Still here")
        _ = await controller.publish()
        XCTAssertEqual(controller.state.publicReview, "Still here")
        let count = await service.createCount()
        XCTAssertEqual(count, 1)
        XCTAssertTrue(store.visits.isEmpty)
    }

    func testCanonicalSuccessMarksVisitedUpdatesLatestAndAppendsMultipleVisits() async {
        let older = Self.visit(id: UUID(uuidString: "30000000-0000-0000-0000-000000000002")!, score: 6)
        let latest = Self.visit(score: 9.4)
        let service = VisitServiceProbe(ownerRows: [older], results: [.success(latest)])
        let store = activatedStore(service)
        try? await store.refresh()
        let controller = VisitComposerController(place: TestPlaces.detail(), store: store)
        _ = await controller.publish()
        XCTAssertEqual(store.visits(for: TestPlaces.placeID).count, 2)
        XCTAssertEqual(store.latest(for: TestPlaces.placeID)?.id, Self.visitID)
        XCTAssertEqual(store.latest(for: TestPlaces.placeID)?.overallRating, 9.4)
    }

    func testAccountSwitchClearsOwnerVisitsAndNewComposerUsesDefaults() async {
        let service = VisitServiceProbe(results: [.success(Self.visit())])
        let store = activatedStore(service)
        let first = VisitComposerController(place: TestPlaces.detail(), store: store)
        first.setReview("A review")
        first.setPrivateMemory("A secret")
        store.activate(accountID: UUID(uuidString: "11111111-1111-1111-1111-111111111112")!)
        let second = VisitComposerController(place: TestPlaces.detail(), store: store)
        XCTAssertEqual(second.state.publicReview, "")
        XCTAssertEqual(second.state.privateMemory, "")
        XCTAssertTrue(store.visits.isEmpty)
    }

    private static let visitID = UUID(uuidString: "30000000-0000-0000-0000-000000000099")!

    private static func visit(score: Double = 9.4) -> OwnerVisit {
        visit(id: visitID, score: score)
    }

    private static func visit(id: UUID, score: Double) -> OwnerVisit {
        OwnerVisit(id: id, place: TestPlaces.summary(), visitedAt: "2026-09-01", overallRating: score,
                   publicReview: "Canonical", privateMemory: "Owner", visibility: .privateAccess)
    }

    private func activatedStore(_ service: any VisitServing) -> VisitStore {
        let store = VisitStore(service: service)
        store.activate(accountID: TestJSON.userID)
        return store
    }
}

actor VisitServiceProbe: VisitServing {
    private var ownerRows: [OwnerVisit]
    private var results: [Result<OwnerVisit, AppError>]
    private var captured: [VisitCreateRequest] = []

    init(ownerRows: [OwnerVisit] = [], results: [Result<OwnerVisit, AppError>]) {
        self.ownerRows = ownerRows
        self.results = results
    }

    func create(_ request: VisitCreateRequest) async throws -> OwnerVisit {
        captured.append(request)
        guard !results.isEmpty else { throw AppError.server }
        return try results.removeFirst().get()
    }

    func ownerVisits() async throws -> [OwnerVisit] { ownerRows }
    func requests() -> [VisitCreateRequest] { captured }
    func createCount() -> Int { captured.count }
}

actor GatedVisitService: VisitServing {
    private let canonical: OwnerVisit
    private var gate: CheckedContinuation<Void, Never>?
    private var started = false
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private var captured: [VisitCreateRequest] = []

    init(canonical: OwnerVisit) { self.canonical = canonical }

    func create(_ request: VisitCreateRequest) async throws -> OwnerVisit {
        captured.append(request)
        started = true
        waiters.forEach { $0.resume() }
        waiters.removeAll()
        await withCheckedContinuation { gate = $0 }
        return canonical
    }

    func ownerVisits() async throws -> [OwnerVisit] { [] }
    func waitForCreate() async {
        if started { return }
        await withCheckedContinuation { waiters.append($0) }
    }
    func complete() { gate?.resume(); gate = nil }
    func createCount() -> Int { captured.count }
}

private extension Data {
    func visitJSONObject() throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: self) as? [String: Any])
    }
}

private extension String {
    func visitSummaryFixture() -> String {
        let prefix = "\"content\": [{"
        guard let start = range(of: prefix)?.upperBound,
              let end = range(of: "}],", range: start..<endIndex)?.lowerBound else { return "{}" }
        return "{" + String(self[start..<end]) + "}"
    }

    func visitOwnerFixture() -> String {
        let prefix = "\"content\": [{"
        guard let start = range(of: prefix)?.upperBound,
              let end = range(of: "}],", range: start..<endIndex)?.lowerBound else { return "{}" }
        return "{" + String(self[start..<end]) + "}"
    }
}
