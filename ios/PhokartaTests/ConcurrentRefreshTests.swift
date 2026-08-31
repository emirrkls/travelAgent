import XCTest
@testable import Phokarta

final class ConcurrentRefreshTests: XCTestCase {
    func testTenConcurrentUnauthorizedRequestsCauseExactlyOneRefresh() async throws {
        let store = InMemorySessionStore(session: testSession(access: "access-1", refresh: "refresh-token-aaaaaaaa"))
        let config = try TestConfig.httpsTest()
        let probe = RefreshProbe()
        let transport = FakeHTTPTransport { request in
            try await probe.handle(request)
        }
        let refresh = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)

        try await withThrowingTaskGroup(of: UserProfileDTO.self) { group in
            for _ in 0..<10 {
                group.addTask {
                    try await client.send(MeEndpoint())
                }
            }
            var received = 0
            for try await profile in group {
                XCTAssertEqual(profile.id, TestJSON.userID)
                received += 1
            }
            XCTAssertEqual(received, 10)
        }

        let refreshCount = await probe.refreshCount
        XCTAssertEqual(refreshCount, 1)
        let persistedSession = await store.load()
        XCTAssertEqual(persistedSession?.tokens.accessToken, "access-2")
        XCTAssertEqual(persistedSession?.tokens.refreshToken, "refresh-token-bbbbbbbb")
    }
}

actor RefreshProbe {
    private(set) var refreshCount = 0
    private var stale401Count = 0
    private var refreshWait: CheckedContinuation<Void, Never>?
    private let expectedStale = 10

    func handle(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let url = request.url!
        if url.path.hasSuffix("/api/v1/auth/refresh") {
            refreshCount += 1
            if stale401Count < expectedStale {
                await withCheckedContinuation { continuation in
                    refreshWait = continuation
                }
            }
            return TestJSON.http(
                url,
                status: 200,
                data: TestJSON.tokens(access: "access-2", refresh: "refresh-token-bbbbbbbb")
            )
        }

        let authorization = request.value(forHTTPHeaderField: "Authorization")
        if authorization == "Bearer access-1" {
            stale401Count += 1
            if stale401Count >= expectedStale, let wait = refreshWait {
                refreshWait = nil
                wait.resume()
            }
            return TestJSON.http(
                url,
                status: 401,
                data: TestJSON.apiError(status: 401, code: "TOKEN_EXPIRED")
            )
        }
        if authorization == "Bearer access-2" {
            return TestJSON.http(url, status: 200, data: TestJSON.utf8(TestJSON.profile()))
        }
        throw AppError.unknown(status: 500, code: authorization)
    }
}

@MainActor
final class SavedPlaceStoreTests: XCTestCase {
    func testInitialLoadContentAndEmpty() async throws {
        let row = SavedPlaceDTO(place: TestPlaces.summary(), savedAt: "2026-08-31T10:00:00Z")
        let service = SavedServiceProbe(rows: [row])
        let store = SavedPlaceStore(service: service)
        store.activate(accountID: TestJSON.userID)
        try await store.refresh()
        XCTAssertEqual(store.savedRows, [row])
        XCTAssertTrue(store.isSaved(TestPlaces.placeID))

        let emptyStore = SavedPlaceStore(service: SavedServiceProbe())
        emptyStore.activate(accountID: TestJSON.userID)
        let controller = SavedController(store: emptyStore)
        await controller.load()
        XCTAssertEqual(controller.phase, .empty)
    }

    func testSaveAndUnsaveUseCanonicalConfirmedState() async {
        let service = SavedServiceProbe()
        let store = SavedPlaceStore(service: service)
        store.activate(accountID: TestJSON.userID)
        store.setDesired(true, for: TestPlaces.placeID)
        XCTAssertTrue(store.isSaved(TestPlaces.placeID))
        await store.waitForMutation(of: TestPlaces.placeID)
        XCTAssertTrue(store.confirmedIDs.contains(TestPlaces.placeID))
        XCTAssertEqual(store.savedRows.first?.place.id, TestPlaces.placeID)

        store.setDesired(false, for: TestPlaces.placeID)
        XCTAssertFalse(store.isSaved(TestPlaces.placeID))
        await store.waitForMutation(of: TestPlaces.placeID)
        XCTAssertFalse(store.confirmedIDs.contains(TestPlaces.placeID))
        XCTAssertTrue(store.savedRows.isEmpty)
    }

    func testOptimisticFailureRollsBackToLastConfirmedState() async {
        let service = SavedServiceProbe(mutationError: .networkUnavailable)
        let store = SavedPlaceStore(service: service)
        store.activate(accountID: TestJSON.userID)
        store.setDesired(true, for: TestPlaces.placeID)
        XCTAssertTrue(store.isSaved(TestPlaces.placeID))
        await store.waitForMutation(of: TestPlaces.placeID)
        XCTAssertFalse(store.isSaved(TestPlaces.placeID))
        XCTAssertEqual(store.error(for: TestPlaces.placeID), .networkUnavailable)
    }

    func testRapidSaveUnsaveSaveSerializesPerPlaceAndKeepsLatestIntent() async {
        let service = SavedServiceProbe()
        let store = SavedPlaceStore(service: service)
        store.activate(accountID: TestJSON.userID)

        store.setDesired(true, for: TestPlaces.placeID)
        store.setDesired(false, for: TestPlaces.placeID)
        store.setDesired(true, for: TestPlaces.placeID)
        await store.waitForMutation(of: TestPlaces.placeID)

        XCTAssertTrue(store.isSaved(TestPlaces.placeID))
        XCTAssertTrue(store.confirmedIDs.contains(TestPlaces.placeID))
        XCTAssertNil(store.desiredByID[TestPlaces.placeID])
        let mutations = await service.mutations
        XCTAssertEqual(mutations.last, true)
    }

    func testStaleRefreshCannotEraseNewerConfirmedSave() async throws {
        let service = GatedSavedService()
        let store = SavedPlaceStore(service: service)
        store.activate(accountID: TestJSON.userID)

        let refresh = Task { try await store.refresh() }
        await service.waitForFetch()
        store.setDesired(true, for: TestPlaces.placeID)
        await store.waitForMutation(of: TestPlaces.placeID)
        await service.completeFetch([])
        try await refresh.value

        XCTAssertTrue(store.isSaved(TestPlaces.placeID))
        XCTAssertEqual(store.savedRows.first?.place.id, TestPlaces.placeID)
    }

    func testAccountSwitchClearsSavedAndPendingMutationState() async {
        let service = SavedServiceProbe()
        let store = SavedPlaceStore(service: service)
        store.activate(accountID: TestJSON.userID)
        store.setDesired(true, for: TestPlaces.placeID)
        await store.waitForMutation(of: TestPlaces.placeID)

        let userB = UUID(uuidString: "11111111-1111-1111-1111-111111111222")!
        store.activate(accountID: userB)
        XCTAssertEqual(store.accountID, userB)
        XCTAssertTrue(store.savedRows.isEmpty)
        XCTAssertTrue(store.confirmedIDs.isEmpty)
        XCTAssertTrue(store.desiredByID.isEmpty)
        XCTAssertTrue(store.busyIDs.isEmpty)
    }
}

@MainActor
final class CollectionStoreTests: XCTestCase {
    func testListContentAndEmptyControllers() async {
        let detail = TestCollections.detail()
        let service = CollectionServiceProbe(
            summaries: [TestCollections.summary(from: detail)],
            details: [detail.id: detail]
        )
        let store = CollectionStore(service: service)
        store.activate(accountID: TestJSON.userID)
        let controller = CollectionsController(store: store)
        await controller.load()
        XCTAssertEqual(controller.phase, .content)
        XCTAssertEqual(store.summaries.first?.title, "Summer")

        let empty = CollectionStore(service: CollectionServiceProbe())
        empty.activate(accountID: TestJSON.userID)
        let emptyController = CollectionsController(store: empty)
        await emptyController.load()
        XCTAssertEqual(emptyController.phase, .empty)
    }

    func testCreateUsesCanonicalDetailAndUpdatesList() async throws {
        let store = CollectionStore(service: CollectionServiceProbe())
        store.activate(accountID: TestJSON.userID)
        let created = try await store.create(CreateCollectionRequestDTO(
            title: "Autumn",
            description: "",
            visibility: .friends,
            coverImage: "https://example.test/autumn.jpg"
        ))
        XCTAssertEqual(created.title, "Autumn")
        XCTAssertEqual(store.summaries.first?.title, "Autumn")
        XCTAssertEqual(store.details[created.id], created)
    }

    func testPolicyRequiredDoesNotAddCollectionOrClearAccount() async {
        let service = CollectionServiceProbe(
            createError: .policyAcceptanceRequired(requiredVersion: "2026-08-01")
        )
        let store = CollectionStore(service: service)
        store.activate(accountID: TestJSON.userID)
        do {
            _ = try await store.create(CreateCollectionRequestDTO(
                title: "Blocked",
                description: "",
                visibility: .privateAccess,
                coverImage: "https://example.test/cover.jpg"
            ))
            XCTFail("Expected policy error")
        } catch let error as AppError {
            XCTAssertEqual(error, .policyAcceptanceRequired(requiredVersion: "2026-08-01"))
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
        XCTAssertEqual(store.accountID, TestJSON.userID)
        XCTAssertTrue(store.summaries.isEmpty)
    }

    func testAddDuplicateSuppressionProducesOneRelationship() async throws {
        let detail = TestCollections.detail()
        let service = CollectionServiceProbe(details: [detail.id: detail])
        let store = CollectionStore(service: service)
        store.activate(accountID: TestJSON.userID)
        try await store.refreshDetail(id: detail.id)

        async let first: Void = store.add(placeID: TestPlaces.placeID, to: detail.id)
        async let second: Void = store.add(placeID: TestPlaces.placeID, to: detail.id)
        _ = try await (first, second)

        XCTAssertEqual(store.details[detail.id]?.places.map(\.place.id), [TestPlaces.placeID])
        let addCalls = await service.addCalls
        XCTAssertEqual(addCalls, 1)
    }

    func testRemoveReloadsCanonicalDetail() async throws {
        let row = CollectionPlace(
            place: TestPlaces.summary(),
            displayOrder: 0,
            addedAt: "2026-08-31T10:00:00Z"
        )
        let detail = TestCollections.detail(places: [row])
        let service = CollectionServiceProbe(details: [detail.id: detail])
        let store = CollectionStore(service: service)
        store.activate(accountID: TestJSON.userID)
        try await store.refreshDetail(id: detail.id)
        try await store.remove(placeID: TestPlaces.placeID, from: detail.id)
        XCTAssertEqual(store.details[detail.id]?.places, [])
        let removeCalls = await service.removeCalls
        XCTAssertEqual(removeCalls, 1)
    }

    func testAccountSwitchClearsCollectionsDetailsAndBusyRelations() async throws {
        let detail = TestCollections.detail()
        let store = CollectionStore(service: CollectionServiceProbe(details: [detail.id: detail]))
        store.activate(accountID: TestJSON.userID)
        try await store.refreshDetail(id: detail.id)
        let userB = UUID(uuidString: "11111111-1111-1111-1111-111111111222")!
        store.activate(accountID: userB)
        XCTAssertEqual(store.accountID, userB)
        XCTAssertTrue(store.summaries.isEmpty)
        XCTAssertTrue(store.details.isEmpty)
        XCTAssertTrue(store.busyRelations.isEmpty)
    }

    func testStaleCollectionListCannotEraseCreateSuccess() async throws {
        let service = GatedCollectionService()
        let store = CollectionStore(service: service)
        store.activate(accountID: TestJSON.userID)
        let refresh = Task { try await store.refreshList() }
        await service.waitForList()
        let created = try await store.create(CreateCollectionRequestDTO(
            title: "Created during refresh",
            description: "",
            visibility: .privateAccess,
            coverImage: "https://example.test/cover.jpg"
        ))
        await service.completeList([])
        try await refresh.value
        XCTAssertEqual(store.summaries.map(\.id), [created.id])
    }

    func testStaleDetailCannotRevertCanonicalAdd() async throws {
        let old = TestCollections.detail()
        let service = GatedCollectionService(detail: old)
        let store = CollectionStore(service: service)
        store.activate(accountID: TestJSON.userID)
        _ = try await store.create(CreateCollectionRequestDTO(
            title: old.title,
            description: old.description,
            visibility: old.visibility,
            coverImage: old.coverImage
        ))
        let refresh = Task { try await store.refreshDetail(id: old.id) }
        await service.waitForDetail()
        try await store.add(placeID: TestPlaces.placeID, to: old.id)
        await service.completeDetail(old)
        try await refresh.value
        XCTAssertEqual(store.details[old.id]?.places.map(\.place.id), [TestPlaces.placeID])
    }
}
