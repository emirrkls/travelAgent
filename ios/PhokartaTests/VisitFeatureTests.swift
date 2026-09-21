import XCTest
@testable import Phokarta

final class VisitContractTests: XCTestCase {
    func testMilestone4DesignPresentationStatesRemainDistinct() {
        XCTAssertEqual(ExperienceDetailMediaPresentation.resolve(mediaCount: 0), .noMedia)
        XCTAssertEqual(ExperienceDetailMediaPresentation.resolve(mediaCount: 1), .media)
        XCTAssertEqual(AcknowledgementPresentation.resolve(acknowledged: false), .action)
        XCTAssertEqual(AcknowledgementPresentation.resolve(acknowledged: true), .confirmed)
    }

    func testMixedCollectionSummaryModelsAllContentShapesAndLocales() {
        let places = CollectionContentPresentation(placeCount: 2, experienceCount: 0)
        let experiences = CollectionContentPresentation(placeCount: 0, experienceCount: 2)
        let mixed = CollectionContentPresentation(placeCount: 1, experienceCount: 1)

        XCTAssertEqual(places.kind, .places)
        XCTAssertEqual(experiences.kind, .experiences)
        XCTAssertEqual(mixed.kind, .mixed)
        XCTAssertEqual(mixed.totalCount, 2)
        XCTAssertEqual(mixed.label(locale: Locale(identifier: "en")), "2 items")
        XCTAssertEqual(mixed.label(locale: Locale(identifier: "tr")), "2 öğe")
    }

    func testAcknowledgementPrefillDoesNotEmitDraftRestoreFeedback() {
        let userID = UUID(uuidString: "10000000-0000-0000-0000-000000000001")!
        let placeID = UUID(uuidString: "20000000-0000-0000-0000-000000000001")!
        let acknowledgementID = UUID(uuidString: "30000000-0000-0000-0000-000000000001")!
        let prefill = DurableVisitDraft(
            userId: userID, placeId: placeID, overallScore: 8, publicReview: "",
            privateMemory: "", visitedAtEpochDay: 0, visibility: VisitVisibility.publicAccess.rawValue,
            dimensionsExpanded: false, createdAtEpochMillis: 1, updatedAtEpochMillis: 1,
            payloadVersion: 2, primaryExperienceCode: PrimaryExperienceCode.gunBatimi.rawValue,
            originAcknowledgementId: acknowledgementID
        )

        XCTAssertNil(VisitComposerRestorePolicy.feedback(for: prefill))
        var edited = prefill
        edited.overallFeelingCode = OverallFeelingCode.guzeldi.rawValue
        XCTAssertEqual(VisitComposerRestorePolicy.feedback(for: edited), .restoredUserDraft)
        XCTAssertEqual(phokartaString("visit.draft_restored", locale: Locale(identifier: "en")), "Your draft was restored")
        XCTAssertEqual(phokartaString("visit.draft_restored", locale: Locale(identifier: "tr")), "Taslağın geri yüklendi")
    }

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

    func testNativeExperienceRequestUsesV2EndpointAndHasNoNumericOverallField() throws {
        let client = APIClient(config: try TestConfig.debugHTTP(), transport: URLSessionTransport.default)
        let request = try client.makeRequest(CreateExperienceV2Endpoint(body: ExperienceV2CreateRequest(
            clientMutationId: UUID(), placeId: TestPlaces.placeID, visitDate: "2026-09-16",
            primaryExperienceCode: .gunBatimi, rawExperienceLabel: nil,
            overallFeelingCode: .bayildim, companionCode: .partner, timeOfDayCode: .evening,
            vibeCodes: [.calm, .scenic], practicalSignalCodes: [.arriveEarly],
            dimensions: [ExperienceV2CreateDimension(
                key: "SCENERY", semanticStateCode: .veryGood, templateVersion: 1
            )], title: nil, titleSource: .generated, story: "story", tip: nil,
            privateMemory: "owner only", visibility: .publicAccess, mediaIds: []
        )))
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/api/v2/experiences")
        let json = try XCTUnwrap(request.httpBody).visitJSONObject()
        XCTAssertEqual(json["overallFeelingCode"] as? String, "BAYILDIM")
        XCTAssertNil(json["overallRating"])
        XCTAssertEqual(json["titleSource"] as? String, "GENERATED")
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

    func testNativeV2RequiresPrimaryFeelingContentAndCapsVibesAndMedia() {
        var state = fixtureState()
        state.payloadVersion = 2
        XCTAssertEqual(VisitValidation.validate(state), .primaryExperienceRequired)
        state.primaryExperience = .gunBatimi
        XCTAssertEqual(VisitValidation.validate(state), .feelingRequired)
        state.overallFeeling = .guzeldi
        XCTAssertEqual(VisitValidation.validate(state), .contentRequired)
        state.story = "story"
        XCTAssertNil(VisitValidation.validate(state))
        state.vibes = [.calm, .scenic, .romantic]
        XCTAssertEqual(VisitValidation.validate(state), .tooManyVibes)
        state.vibes = [.calm, .scenic]
        state.mediaCount = 7
        XCTAssertEqual(VisitValidation.validate(state), .tooManyMedia)
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

final class ExperienceV2FoundationTests: XCTestCase {
    func testEndpointUsesReadOnlyV2Route() throws {
        let client = APIClient(config: try TestConfig.debugHTTP(), transport: URLSessionTransport.default)
        let id = UUID(uuidString: "30000000-0000-0000-0000-000000000001")!
        let request = try client.makeRequest(ExperienceV2Endpoint(experienceId: id))
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.url?.path, "/api/v2/experiences/\(id.uuidString.uppercased())")
        XCTAssertNil(request.httpBody)
    }

    func testNativeExperienceDecodesStableCodesAndOrderedMedia() throws {
        let experience = try APIJSON.decoder.decode(ExperienceV2.self, from: Data(Self.fixture.utf8))

        XCTAssertEqual(experience.classification, .nativeV2)
        XCTAssertEqual(experience.title, "Persisted sunset title")
        XCTAssertEqual(experience.titleSource, .generated)
        XCTAssertTrue(experience.titlePersisted)
        XCTAssertEqual(experience.feeling.code, .bayildim)
        XCTAssertEqual(experience.feeling.source, .explicit)
        XCTAssertEqual(experience.primaryExperience.code, .gunBatimi)
        XCTAssertEqual(experience.primaryExperience.family, .sceneryAndMoment)
        XCTAssertEqual(experience.vibes, [.calm, .romantic])
        XCTAssertEqual(experience.practicalSignals, [.arriveEarly])
        XCTAssertEqual(experience.dimensions.first?.semanticState, .veryGood)
        XCTAssertEqual(experience.dimensions.first?.semanticState?.compatibilityScore, 10)
        XCTAssertEqual(experience.media.map(\.position), [0, 1])
        XCTAssertEqual(experience.media.map(\.kind), [.legacyURL, .managed])
        XCTAssertFalse(Mirror(reflecting: experience).children.compactMap(\.label).contains("privateMemory"))
    }

    func testUnknownFutureCodesUseSafeFallbacks() throws {
        let future = Self.fixture
            .replacingOccurrences(of: "GUN_BATIMI", with: "FUTURE_PRIMARY")
            .replacingOccurrences(of: "SCENERY_AND_MOMENT", with: "FUTURE_FAMILY")
            .replacingOccurrences(of: "CALM", with: "FUTURE_VIBE")
            .replacingOccurrences(of: "ARRIVE_EARLY", with: "FUTURE_SIGNAL")
            .replacingOccurrences(of: "VERY_GOOD", with: "FUTURE_STATE")
        let experience = try APIJSON.decoder.decode(ExperienceV2.self, from: Data(future.utf8))

        XCTAssertEqual(experience.primaryExperience.code, .unknown)
        XCTAssertEqual(experience.primaryExperience.family, .unknown)
        XCTAssertEqual(experience.vibes.first, .unknown)
        XCTAssertEqual(experience.practicalSignals.first, .unknown)
        XCTAssertEqual(experience.dimensions.first?.semanticState, .unknown)
    }

    func testLegacyCompatibilityDoesNotFabricateCanonicalPrimaryOrPersistedTitle() throws {
        let legacy = Self.fixture
            .replacingOccurrences(of: "NATIVE_V2", with: "LEGACY_COMPATIBILITY")
            .replacingOccurrences(of: "\"titleSource\":\"GENERATED\"", with: "\"titleSource\":null")
            .replacingOccurrences(of: "\"titlePersisted\":true", with: "\"titlePersisted\":false")
            .replacingOccurrences(of: "GUN_BATIMI", with: "UNKNOWN_LEGACY")
            .replacingOccurrences(of: "\"canonical\":true", with: "\"canonical\":false")
            .replacingOccurrences(of: "\"family\":\"SCENERY_AND_MOMENT\"", with: "\"family\":null")
        let experience = try APIJSON.decoder.decode(ExperienceV2.self, from: Data(legacy.utf8))

        XCTAssertEqual(experience.classification, .legacyCompatibility)
        XCTAssertEqual(experience.primaryExperience.code, .unknownLegacy)
        XCTAssertFalse(experience.primaryExperience.canonical)
        XCTAssertNil(experience.primaryExperience.family)
        XCTAssertNil(experience.titleSource)
        XCTAssertFalse(experience.titlePersisted)
    }

    func testTaxonomyV1CatalogCountsAndDimensionCompatibility() {
        XCTAssertEqual(PrimaryExperienceCode.allCases.filter { $0 != .unknown && $0 != .unknownLegacy }.count, 55)
        XCTAssertEqual(PracticalSignalCode.allCases.filter { $0 != .unknown }.count, 16)
        XCTAssertEqual(DimensionStateCode.veryGood.compatibilityScore, 10)
        XCTAssertEqual(DimensionStateCode.veryWeak.compatibilityScore, 2)
    }

    func testMixedCollectionDecodesExperienceIdentityWithoutCollapsingToPlace() throws {
        let json = """
        {
          "id":"40000000-0000-0000-0000-000000000099",
          "ownerUserId":"11111111-1111-1111-1111-111111111111",
          "title":"Mixed","description":"","visibility":"PRIVATE","coverImage":"",
          "createdAt":"2026-09-17T10:00:00Z","updatedAt":"2026-09-17T10:00:00Z",
          "items":[{
            "type":"EXPERIENCE","displayOrder":0,"addedAt":"2026-09-17T10:00:00Z",
            "place":null,"experience":\(Self.fixture)
          }]
        }
        """

        let detail = try APIJSON.decoder.decode(CollectionDetail.self, from: Data(json.utf8))

        XCTAssertTrue(detail.places.isEmpty)
        XCTAssertEqual(detail.items.count, 1)
        XCTAssertEqual(detail.items.first?.type, .experience)
        XCTAssertEqual(detail.items.first?.experience?.id.uuidString.lowercased(),
                       "30000000-0000-0000-0000-000000000001")
        XCTAssertEqual(detail.items.first?.experience?.place.name, "Foça")
    }

    func testConversationEndpointsUseV2RoutesAndStableMutationIdentity() throws {
        let client = APIClient(config: try TestConfig.debugHTTP(), transport: URLSessionTransport.default)
        let experienceId = UUID(uuidString: "30000000-0000-4000-8000-000000000001")!
        let mutationId = UUID(uuidString: "40000000-0000-4000-8000-000000000001")!
        let rootId = UUID(uuidString: "50000000-0000-4000-8000-000000000001")!
        let create = try client.makeRequest(CreateConversationEntryEndpoint(
            experienceId: experienceId,
            requestBody: CreateConversationEntryRequest(
                clientMutationId: mutationId, type: .question, body: "Is it quiet?"
            )
        ))
        let reply = try client.makeRequest(CreateConversationReplyEndpoint(
            rootId: rootId,
            requestBody: CreateConversationReplyRequest(clientMutationId: mutationId, body: "Yes")
        ))

        XCTAssertEqual(create.httpMethod, "POST")
        XCTAssertEqual(create.url?.path, "/api/v2/experiences/\(experienceId.uuidString.uppercased())/conversation")
        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: XCTUnwrap(create.httpBody)) as? [String: Any])
        XCTAssertEqual(json["clientMutationId"] as? String, mutationId.uuidString.uppercased())
        XCTAssertEqual(json["type"] as? String, "QUESTION")
        XCTAssertEqual(reply.url?.path, "/api/v2/conversation/\(rootId.uuidString.uppercased())/replies")
    }

    func testConversationDecodesOneLevelOrderAndAuthorAnswerPresentation() throws {
        let page = try APIJSON.decoder.decode(
            CursorPageDTO<ConversationEntry>.self,
            from: Data(Self.conversationFixture.utf8)
        )

        XCTAssertEqual(page.items.map(\.body), ["new question", "older comment"])
        XCTAssertEqual(page.items.first?.replies.map(\.body), ["first answer", "follow-up"])
        let authorReply = try XCTUnwrap(page.items.first?.replies.first)
        XCTAssertEqual(
            ConversationPresentation.authorBadgeKey(
                experienceAuthor: authorReply.experienceAuthor,
                rootType: .question,
                isReply: true
            ),
            "conversation.author_answer"
        )
        XCTAssertFalse(ConversationPresentation.canReply(to: authorReply))
        XCTAssertTrue(ConversationPresentation.canReply(to: try XCTUnwrap(page.items.first)))
    }

    func testConversationTimestampFormattingInEnglishAndTurkish() throws {
        let now = try XCTUnwrap(ISO8601DateFormatter().date(from: "2026-09-22T12:00:00Z"))
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(secondsFromGMT: 0))
        let english = Locale(identifier: "en_US")
        let turkish = Locale(identifier: "tr_TR")

        XCTAssertEqual(ConversationPresentation.timestamp(
            createdAt: "2026-09-22T11:59:30Z", now: now, calendar: calendar, locale: english
        ), "Just now")
        XCTAssertEqual(ConversationPresentation.timestamp(
            createdAt: "2026-09-22T11:55:00Z", now: now, calendar: calendar, locale: english
        ), "5m")
        XCTAssertEqual(ConversationPresentation.timestamp(
            createdAt: "2026-09-22T10:00:00Z", now: now, calendar: calendar, locale: english
        ), "2h")
        XCTAssertEqual(ConversationPresentation.timestamp(
            createdAt: "2026-09-21T18:00:00Z", now: now, calendar: calendar, locale: english
        ), "Yesterday")
        XCTAssertEqual(ConversationPresentation.timestamp(
            createdAt: "2026-08-21T10:00:00Z", now: now, calendar: calendar, locale: english
        ), "Aug 21")
        XCTAssertEqual(ConversationPresentation.timestamp(
            createdAt: "2025-08-21T10:00:00Z", now: now, calendar: calendar, locale: english
        ), "Aug 21, 2025")

        XCTAssertEqual(ConversationPresentation.timestamp(
            createdAt: "2026-09-22T11:59:30Z", now: now, calendar: calendar, locale: turkish
        ), "Az önce")
        XCTAssertEqual(ConversationPresentation.timestamp(
            createdAt: "2026-09-22T11:55:00Z", now: now, calendar: calendar, locale: turkish
        ), "5 dk")
        XCTAssertEqual(ConversationPresentation.timestamp(
            createdAt: "2026-09-22T10:00:00Z", now: now, calendar: calendar, locale: turkish
        ), "2 sa")
        XCTAssertEqual(ConversationPresentation.timestamp(
            createdAt: "2026-09-21T18:00:00Z", now: now, calendar: calendar, locale: turkish
        ), "Dün")
        XCTAssertEqual(ConversationPresentation.timestamp(
            createdAt: "2026-08-21T10:00:00Z", now: now, calendar: calendar, locale: turkish
        ), "21 Ağu")
        XCTAssertEqual(ConversationPresentation.timestamp(
            createdAt: "2025-08-21T10:00:00Z", now: now, calendar: calendar, locale: turkish
        ), "21 Ağu 2025")
    }

    func testConversationMetadataComposerModesAndCompactCount() {
        XCTAssertEqual(
            ConversationPresentation.metadata(
                typeLabel: "Question", timestamp: "2h", edited: true, editedLabel: "Edited"
            ),
            "Question · 2h · Edited"
        )
        XCTAssertEqual(
            ConversationPresentation.metadata(
                typeLabel: nil, timestamp: "1h", edited: true, editedLabel: "Edited"
            ),
            "1h · Edited"
        )
        XCTAssertEqual(
            ConversationPresentation.metadata(
                typeLabel: "Yorum", timestamp: "21 Eyl", edited: false, editedLabel: "Düzenlendi"
            ),
            "Yorum · 21 Eyl"
        )
        XCTAssertEqual(ConversationPresentation.composerMode(
            visibleRootCount: 0, expansionRequested: false, draft: ""
        ), .expanded)
        XCTAssertEqual(ConversationPresentation.composerMode(
            visibleRootCount: 2, expansionRequested: false, draft: ""
        ), .compact)
        XCTAssertEqual(ConversationPresentation.composerMode(
            visibleRootCount: 2, expansionRequested: true, draft: ""
        ), .expanded)
        XCTAssertEqual(ConversationPresentation.composerMode(
            visibleRootCount: 2, expansionRequested: false, draft: "Unsent draft"
        ), .expanded)
        XCTAssertNil(ConversationPresentation.count(0, locale: Locale(identifier: "en")))
        XCTAssertEqual(
            ConversationPresentation.count(1, locale: Locale(identifier: "en")),
            ConversationCountPresentation(visual: "1", accessibilityLabel: "1 question or comment")
        )
        XCTAssertEqual(
            ConversationPresentation.count(2, locale: Locale(identifier: "en")),
            ConversationCountPresentation(visual: "2", accessibilityLabel: "2 questions and comments")
        )
        XCTAssertEqual(
            ConversationPresentation.count(1, locale: Locale(identifier: "tr")),
            ConversationCountPresentation(visual: "1", accessibilityLabel: "1 soru veya yorum")
        )
        XCTAssertEqual(
            ConversationPresentation.count(2, locale: Locale(identifier: "tr")),
            ConversationCountPresentation(visual: "2", accessibilityLabel: "2 soru ve yorum")
        )
    }

    func testConversationReportTargetUsesExistingModerationWireType() throws {
        let encoded = try APIJSON.encoder.encode(CreateReportRequestDTO(
            targetType: .conversationEntry,
            targetId: UUID(uuidString: "50000000-0000-4000-8000-000000000001")!,
            reason: .spam,
            details: nil
        ))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        XCTAssertEqual(json["targetType"] as? String, "CONVERSATION_ENTRY")
    }

    func testConversationLocalizationCoversEnglishAndTurkish() {
        let english = Locale(identifier: "en")
        let turkish = Locale(identifier: "tr")
        XCTAssertEqual(phokartaString("conversation.title", locale: english), "Questions & Comments")
        XCTAssertEqual(phokartaString("conversation.author_answer", locale: english), "Author answer")
        XCTAssertEqual(phokartaString("conversation.pending", locale: english), "Sending…")
        XCTAssertEqual(phokartaString("conversation.composer.compact", locale: english), "Ask a question or add a comment")
        XCTAssertEqual(phokartaString("conversation.time.just_now", locale: english), "Just now")
        XCTAssertEqual(phokartaString("conversation.title", locale: turkish), "Sorular ve Yorumlar")
        XCTAssertEqual(
            phokartaString("conversation.subtitle", locale: turkish),
            "Bu deneyim hakkında soru sor veya yararlı bir yorum ekle."
        )
        XCTAssertEqual(phokartaString("conversation.author_answer", locale: turkish), "Deneyim sahibinin yanıtı")
        XCTAssertEqual(phokartaString("conversation.pending", locale: turkish), "Gönderiliyor…")
        XCTAssertEqual(phokartaString("conversation.composer.compact", locale: turkish), "Soru sor veya yorum ekle")
        XCTAssertEqual(phokartaString("conversation.time.just_now", locale: turkish), "Az önce")
    }

    private static let fixture = """
    {
      "id":"30000000-0000-0000-0000-000000000001",
      "classification":"NATIVE_V2",
      "author":{"id":"11111111-1111-1111-1111-111111111111","username":"author","displayName":"Author","avatarUrl":null},
      "place":{"id":"20000000-0000-0000-0000-000000000001","name":"Foça","category":"BEACH","city":"İzmir","region":"Aegean","country":"Türkiye","coverImage":"https://images.test/cover.jpg"},
      "experiencedAt":"2026-09-01",
      "title":"Persisted sunset title",
      "titleSource":"GENERATED",
      "titlePersisted":true,
      "story":"Native story",
      "tip":"Arrive early",
      "feeling":{"code":"BAYILDIM","source":"EXPLICIT","compatibilityNumericRating":10.0},
      "primaryExperience":{"code":"GUN_BATIMI","canonical":true,"family":"SCENERY_AND_MOMENT","rawLabel":null},
      "companion":"PARTNER",
      "timeOfDay":"EVENING",
      "vibes":["CALM","ROMANTIC"],
      "practicalSignals":["ARRIVE_EARLY"],
      "dimensions":[{"key":"SCENERY","numericScore":10.0,"semanticState":"VERY_GOOD","templateVersion":1}],
      "media":[
        {"kind":"LEGACY_URL","position":0,"id":null,"url":"https://legacy.test/0.jpg","accessExpiresAt":null},
        {"kind":"MANAGED","position":1,"id":"40000000-0000-0000-0000-000000000001","url":"https://media.test/signed","accessExpiresAt":"2026-09-16T12:00:00Z"}
      ],
      "visibility":"PUBLIC",
      "taxonomyVersion":1,
      "privateMemory":"must-not-decode"
    }
    """

    private static let conversationFixture = """
    {
      "items":[{
        "id":"50000000-0000-4000-8000-000000000001",
        "experienceId":"30000000-0000-4000-8000-000000000001",
        "type":"QUESTION","body":"new question",
        "author":{"id":"60000000-0000-4000-8000-000000000001","username":"reader","displayName":"Reader","avatarUrl":null},
        "createdAt":"2026-09-20T12:00:00Z","updatedAt":"2026-09-20T12:00:00Z",
        "edited":false,"experienceAuthor":false,"ownedByViewer":false,"reportableByViewer":true,
        "replies":[{
          "id":"70000000-0000-4000-8000-000000000001","experienceId":"30000000-0000-4000-8000-000000000001",
          "type":"REPLY","body":"first answer",
          "author":{"id":"11111111-1111-1111-1111-111111111111","username":"author","displayName":"Author","avatarUrl":null},
          "createdAt":"2026-09-20T12:01:00Z","updatedAt":"2026-09-20T12:01:00Z",
          "edited":false,"experienceAuthor":true,"ownedByViewer":false,"reportableByViewer":true
        },{
          "id":"70000000-0000-4000-8000-000000000002","experienceId":"30000000-0000-4000-8000-000000000001",
          "type":"REPLY","body":"follow-up",
          "author":{"id":"60000000-0000-4000-8000-000000000001","username":"reader","displayName":"Reader","avatarUrl":null},
          "createdAt":"2026-09-20T12:02:00Z","updatedAt":"2026-09-20T12:02:00Z",
          "edited":false,"experienceAuthor":false,"ownedByViewer":true,"reportableByViewer":false
        }]
      },{
        "id":"50000000-0000-4000-8000-000000000002","experienceId":"30000000-0000-4000-8000-000000000001",
        "type":"COMMENT","body":"older comment",
        "author":{"id":"60000000-0000-4000-8000-000000000001","username":"reader","displayName":"Reader","avatarUrl":null},
        "createdAt":"2026-09-20T11:00:00Z","updatedAt":"2026-09-20T11:00:00Z",
        "edited":false,"experienceAuthor":false,"ownedByViewer":true,"reportableByViewer":false
      }],"nextCursor":null,"hasMore":false
    }
    """
}

@MainActor
final class PrivacyAggregateV2FoundationTests: XCTestCase {
    func testRelationshipAndPendingRequestDecodeWithoutApprovedFollowGuessing() throws {
        let profile = try decodeProfile(Self.identityOnlyProfile)
        let request = try APIJSON.decoder.decode(FollowRequestV2.self, from: Data(Self.requestFixture.utf8))
        XCTAssertEqual(profile.relationship?.state, .requestPending)
        XCTAssertTrue(profile.relationship?.isPending == true)
        XCTAssertFalse(profile.relationship?.isFriend == true)
        XCTAssertEqual(request.status, .pending)
        XCTAssertNil(request.resolvedAt)
    }

    func testPrivateIdentityOnlyAndOwnerFullPresentationStates() throws {
        let identityOnly = try decodeProfile(Self.identityOnlyProfile)
        let owner = try decodeProfile(Self.fullProfile)
        XCTAssertEqual(identityOnly.profileVisibility, .privateAccess)
        XCTAssertTrue(identityOnly.isIdentityOnly)
        XCTAssertNil(identityOnly.cityCount)
        XCTAssertNil(identityOnly.followerCount)
        XCTAssertNil(identityOnly.visibleExperienceCount)
        XCTAssertTrue(owner.fullProfile)
        XCTAssertEqual(owner.visibleExperienceCount, 8)
    }

    func testMutualFriendIsDistinctFromOneWayFollowing() throws {
        let friend = try decodeProfile(Self.fullProfile)
        let following = try decodeProfile(Self.fullProfile.replacingOccurrences(of: "FRIENDS", with: "FOLLOWING"))
        XCTAssertTrue(friend.relationship?.isFriend == true)
        XCTAssertFalse(following.relationship?.isFriend == true)
    }

    func testBlockInvalidationHidesRelationshipDirection() throws {
        let relationship = try XCTUnwrap(decodeProfile(Self.fullProfile).relationship)
            .invalidatedByBlock()
        XCTAssertEqual(relationship.state, .unavailable)
        XCTAssertFalse(relationship.followsYou)
        XCTAssertFalse(relationship.canFollow)
        XCTAssertFalse(relationship.canCancelRequest)
    }

    func testAggregateDecodesSeparateCountsAndCompatibilityDetails() throws {
        let aggregate = try APIJSON.decoder.decode(PlaceAggregateV2.self, from: Data(Self.aggregateFixture.utf8))
        XCTAssertEqual(aggregate.visibleExperienceCount, 1)
        XCTAssertEqual(aggregate.communityContributionCount, 4)
        XCTAssertEqual(aggregate.feelings.first?.code, .bayildim)
        XCTAssertEqual(aggregate.dimensions.first?.legacyNumericContributionCount, 1)
        XCTAssertEqual(aggregate.dimensions.first?.semanticDistribution.first?.state, .veryGood)
        XCTAssertEqual(aggregate.practicalSignals.first?.code, .arriveEarly)
        XCTAssertEqual(aggregate.practicalSignals.first?.eligibleContributionDenominator, 4)
    }

    func testUnknownFuturePrivacyAndAggregateCodesUseFallbacks() throws {
        let profile = try decodeProfile(Self.identityOnlyProfile
            .replacingOccurrences(of: "PRIVATE", with: "FUTURE_PRIVACY")
            .replacingOccurrences(of: "REQUEST_PENDING", with: "FUTURE_RELATIONSHIP"))
        let aggregate = try APIJSON.decoder.decode(PlaceAggregateV2.self, from: Data(Self.aggregateFixture
            .replacingOccurrences(of: "BAYILDIM", with: "FUTURE_FEELING")
            .replacingOccurrences(of: "VERY_GOOD", with: "FUTURE_STATE")
            .replacingOccurrences(of: "ARRIVE_EARLY", with: "FUTURE_SIGNAL").utf8))
        XCTAssertEqual(profile.profileVisibility, .unknown)
        XCTAssertEqual(profile.relationship?.state, .unknown)
        XCTAssertEqual(aggregate.feelings.first?.code, .unknown)
        XCTAssertEqual(aggregate.dimensions.first?.semanticDistribution.first?.state, .unknown)
        XCTAssertEqual(aggregate.practicalSignals.first?.code, .unknown)
    }

    func testAccountSwitchClearsPrivacyAndRequestState() throws {
        let store = PrivacySocialStateStore()
        let first = UUID(uuidString: "11111111-1111-1111-1111-111111111111")!
        let second = UUID(uuidString: "44444444-4444-4444-4444-444444444444")!
        store.activate(accountID: first)
        store.replace(
            profile: try decodeProfile(Self.fullProfile),
            incomingRequests: [try APIJSON.decoder.decode(
                FollowRequestV2.self, from: Data(Self.requestFixture.utf8)
            )]
        )
        store.activate(accountID: second)
        XCTAssertNil(store.profile)
        XCTAssertTrue(store.incomingRequests.isEmpty)
        XCTAssertEqual(store.accountID, second)
    }

    func testV2FollowAndPlaceRoutesAreVersionedWithoutV1Mutation() throws {
        let client = APIClient(config: try TestConfig.debugHTTP(), transport: URLSessionTransport.default)
        let user = UUID(uuidString: "22222222-2222-2222-2222-222222222222")!
        let place = UUID(uuidString: "20000000-0000-0000-0000-000000000001")!
        let follow = try client.makeRequest(FollowV2Endpoint(userId: user))
        let aggregate = try client.makeRequest(PlaceAggregateV2Endpoint(placeId: place))
        XCTAssertEqual(follow.httpMethod, "POST")
        XCTAssertEqual(follow.url?.path, "/api/v2/users/\(user.uuidString.lowercased())/follow")
        XCTAssertEqual(aggregate.httpMethod, "GET")
        XCTAssertEqual(aggregate.url?.path, "/api/v2/places/\(place.uuidString.lowercased())")
    }

    private func decodeProfile(_ fixture: String) throws -> ProfileV2 {
        try APIJSON.decoder.decode(ProfileV2.self, from: Data(fixture.utf8))
    }

    private static let identityOnlyProfile = """
    {
      "id":"22222222-2222-2222-2222-222222222222",
      "username":"private_user",
      "displayName":"Private User",
      "avatarUrl":null,
      "bio":"Short bio",
      "profileVisibility":"PRIVATE",
      "fullProfile":false,
      "relationship":{"state":"REQUEST_PENDING","followsYou":false,"canFollow":false,"canCancelRequest":true},
      "cityCount":null,
      "countryCount":null,
      "followerCount":null,
      "followingCount":null,
      "friendCount":null,
      "visibleExperienceCount":null
    }
    """

    private static let fullProfile = identityOnlyProfile
        .replacingOccurrences(of: "\"fullProfile\":false", with: "\"fullProfile\":true")
        .replacingOccurrences(of: "REQUEST_PENDING", with: "FRIENDS")
        .replacingOccurrences(of: "\"cityCount\":null", with: "\"cityCount\":5")
        .replacingOccurrences(of: "\"countryCount\":null", with: "\"countryCount\":2")
        .replacingOccurrences(of: "\"followerCount\":null", with: "\"followerCount\":10")
        .replacingOccurrences(of: "\"followingCount\":null", with: "\"followingCount\":4")
        .replacingOccurrences(of: "\"friendCount\":null", with: "\"friendCount\":3")
        .replacingOccurrences(of: "\"visibleExperienceCount\":null", with: "\"visibleExperienceCount\":8")

    private static let requestFixture = """
    {
      "id":"33333333-3333-3333-3333-333333333333",
      "requester":{"id":"11111111-1111-1111-1111-111111111111","username":"requester","displayName":"Requester","avatarUrl":null},
      "status":"PENDING",
      "createdAt":"2026-09-16T10:00:00Z",
      "resolvedAt":null
    }
    """

    private static let aggregateFixture = """
    {
      "place":{"id":"20000000-0000-0000-0000-000000000001","name":"Foça","category":"BEACH","city":"İzmir","region":"Aegean","country":"Türkiye","coverImage":""},
      "visibleExperienceCount":1,
      "communityContributionCount":4,
      "feelings":[{"code":"BAYILDIM","contributionCount":2}],
      "dimensions":[{"key":"SCENERY","contributionCount":2,"numericAverage":8.0,"legacyNumericContributionCount":1,"semanticDistribution":[{"state":"VERY_GOOD","contributionCount":1}]}],
      "practicalSignals":[{"code":"ARRIVE_EARLY","contributionCount":1,"eligibleContributionDenominator":4}]
    }
    """
}
