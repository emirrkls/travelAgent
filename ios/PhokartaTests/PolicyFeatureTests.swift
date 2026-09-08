import XCTest
@testable import Phokarta

@MainActor
final class PolicyFeatureTests: XCTestCase {
    private let accountA = UUID(uuidString: "11111111-1111-1111-1111-111111111111")!
    private let accountB = UUID(uuidString: "22222222-2222-2222-2222-222222222222")!

    // MARK: - 90. POLICY STATUS TEST (P12)

    func testPolicyStatusDecoding() async throws {
        let store = InMemorySessionStore(session: testSession(access: "access-1"))
        let config = try TestConfig.httpsTest()
        let transport = FakeHTTPTransport { request in
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertEqual(request.url?.path, "/api/v1/me/policy-status")
            let statusJSON = """
            {
                "requiredVersion": "2026-08-beta",
                "acceptedVersion": null,
                "accepted": false
            }
            """
            return TestJSON.http(request.url!, status: 200, data: Data(statusJSON.utf8))
        }
        let refresh = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)
        let service = PolicyService(client: client)

        let status = try await service.getPolicyStatus()
        XCTAssertEqual(status.requiredVersion, "2026-08-beta")
        XCTAssertNil(status.acceptedVersion)
        XCTAssertFalse(status.accepted)
    }

    // MARK: - 91. POLICY ACCEPTANCE TEST

    func testPolicyAcceptanceEndpointAndStoreUpdate() async throws {
        let store = InMemorySessionStore(session: testSession(access: "access-1"))
        let config = try TestConfig.httpsTest()
        let transport = FakeHTTPTransport { request in
            if request.httpMethod == "GET" {
                let statusJSON = """
                {"requiredVersion":"2026-08-beta","acceptedVersion":null,"accepted":false}
                """
                return TestJSON.http(request.url!, status: 200, data: Data(statusJSON.utf8))
            }

            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.url?.path, "/api/v1/me/policy-acceptance")
            let body = try JSONDecoder().decode(PolicyAcceptanceRequestDTO.self, from: request.httpBody!)
            XCTAssertEqual(body.policyVersion, "2026-08-beta")

            let responseJSON = """
            {"requiredVersion":"2026-08-beta","acceptedVersion":"2026-08-beta","accepted":true}
            """
            return TestJSON.http(request.url!, status: 200, data: Data(responseJSON.utf8))
        }
        let refresh = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)
        let service = PolicyService(client: client)
        let policyStore = PolicyStatusStore(service: service)

        policyStore.activate(accountId: accountA)
        await policyStore.loadStatus()

        XCTAssertTrue(policyStore.needsAcceptance)
        XCTAssertEqual(policyStore.requiredVersion, "2026-08-beta")

        let success = await policyStore.acceptPolicy()
        XCTAssertTrue(success)
        XCTAssertTrue(policyStore.isAccepted)
        XCTAssertFalse(policyStore.needsAcceptance)
    }

    // MARK: - 92. POLICY STALE RESPONSE TEST (P12)

    func testStalePolicyResponseCannotOverrideNewerAcceptance() async throws {
        actor SlowGate {
            var gate: CheckedContinuation<Void, Never>?
            func wait() async {
                await withCheckedContinuation { cont in
                    self.gate = cont
                }
            }
            func open() {
                gate?.resume()
                gate = nil
            }
        }

        let slowGate = SlowGate()
        let callCounter = CallCounter()

        let store = InMemorySessionStore(session: testSession(access: "access-1"))
        let config = try TestConfig.httpsTest()
        let transport = FakeHTTPTransport { request in
            if request.httpMethod == "GET" {
                await callCounter.increment()
                let currentCount = await callCounter.count
                if currentCount == 1 {
                    // Stale slow response
                    await slowGate.wait()
                    let staleJSON = """
                    {"requiredVersion":"2026-08-beta","acceptedVersion":null,"accepted":false}
                    """
                    return TestJSON.http(request.url!, status: 200, data: Data(staleJSON.utf8))
                }
            }

            // Acceptance succeeds immediately
            let acceptedJSON = """
            {"requiredVersion":"2026-08-beta","acceptedVersion":"2026-08-beta","accepted":true}
            """
            return TestJSON.http(request.url!, status: 200, data: Data(acceptedJSON.utf8))
        }
        let refresh = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)
        let service = PolicyService(client: client)
        let policyStore = PolicyStatusStore(service: service)
        policyStore.activate(accountId: accountA)

        // Launch slow background status load
        let slowLoad = Task { await policyStore.loadStatus() }

        // Accept policy (advances generation)
        _ = await policyStore.acceptPolicy()
        XCTAssertTrue(policyStore.isAccepted)

        // Now release slow load
        await slowGate.open()
        await slowLoad.value

        // Stale unaccepted response must NOT overwrite newer acceptance
        XCTAssertTrue(policyStore.isAccepted, "P12: Newer accepted status must not be overridden by stale unaccepted response")
    }

    // MARK: - 93. POLICY VERSION BUMP TEST (P12)

    func testPolicyVersionBumpRequiresReacceptance() async throws {
        let store = InMemorySessionStore(session: testSession(access: "access-1"))
        let config = try TestConfig.httpsTest()
        let transport = FakeHTTPTransport { request in
            // Backend bumped required version to "2026-09-ga", but user only accepted "2026-08-beta"
            let bumpedJSON = """
            {
                "requiredVersion": "2026-09-ga",
                "acceptedVersion": "2026-08-beta",
                "accepted": false
            }
            """
            return TestJSON.http(request.url!, status: 200, data: Data(bumpedJSON.utf8))
        }
        let refresh = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)
        let service = PolicyService(client: client)
        let policyStore = PolicyStatusStore(service: service)
        policyStore.activate(accountId: accountA)

        await policyStore.loadStatus()

        // P12 invariant: version-aware policy check marks accepted=false
        XCTAssertFalse(policyStore.isAccepted, "P12: User who accepted old version must be unaccepted for new required version")
        XCTAssertTrue(policyStore.needsAcceptance)
        XCTAssertEqual(policyStore.requiredVersion, "2026-09-ga")
    }

    // MARK: - 95. POLICY DOES NOT LOGOUT TEST (P13)

    func testPolicyRequiredErrorDoesNotTriggerLogout() async throws {
        let store = InMemorySessionStore(session: testSession(access: "access-1"))
        let config = try TestConfig.httpsTest()

        // Endpoint returns 403 POLICY_ACCEPTANCE_REQUIRED
        let transport = FakeHTTPTransport { request in
            let errorJSON = """
            {
                "timestamp": "2026-09-08T10:00:00Z",
                "status": 403,
                "code": "POLICY_ACCEPTANCE_REQUIRED",
                "message": "Accept the current user policy before creating content",
                "requiredVersion": "2026-08-beta"
            }
            """
            return TestJSON.http(request.url!, status: 403, data: Data(errorJSON.utf8))
        }
        let refresh = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)

        let endpoint = PublicProfileEndpoint(userId: UUID())
        do {
            _ = try await client.send(endpoint)
            XCTFail("Expected error")
        } catch let error as AppError {
            // P13 invariant: 403 POLICY_ACCEPTANCE_REQUIRED is mapped to .policyAcceptanceRequired, not terminal auth
            if case .policyAcceptanceRequired(let version) = error {
                XCTAssertEqual(version, "2026-08-beta")
                XCTAssertFalse(error.isTerminalAuth, "P13: Policy error must not be classified as terminal auth")
            } else {
                XCTFail("Unexpected error: \(error)")
            }
        }

        // Session must remain intact
        let session = await store.load()
        XCTAssertNotNil(session, "P13: User session must not be cleared on policy acceptance required")
    }

    // MARK: - 96. ACCOUNT A/B POLICY TEST (P15)

    func testAccountPolicyIsolation() {
        let dummyService = PolicyService(client: APIClient(config: try! TestConfig.httpsTest(), transport: FakeHTTPTransport { _ in fatalError() }))
        let store = PolicyStatusStore(service: dummyService)

        // Account A marks required
        store.activate(accountId: accountA)
        store.markPolicyRequired(requiredVersion: "2026-08-beta")
        XCTAssertEqual(store.requiredVersion, "2026-08-beta")

        // Switch to Account B: state must be cleared
        store.activate(accountId: accountB)
        XCTAssertNil(store.requiredVersion, "P15: Account B must start with clean unpolluted policy state")
        XCTAssertFalse(store.isAccepted)
    }

    // MARK: - 94. QUEUED VISIT RESUME AFTER POLICY TEST (P14)

    func testQueuedVisitResumesAfterPolicyAcceptanceWithSameIdentity() async throws {
        let db = try PersistentDatabase(path: nil)
        let clock = TestEpochClock(initialMillis: 1_700_000_000_000)
        let draftRepo = SQLiteVisitDraftRepository(database: db, clock: clock)
        let mutationRepo = SQLiteOfflineMutationRepository(database: db, clock: clock)
        let mediaStore = DurableMediaStore(customRootDirectory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))

        let mutationId = UUID()
        let placeId = UUID()
        let payload = DurablePendingVisitPayload(
            mutationId: mutationId,
            placeId: placeId,
            visitedAtEpochDay: 19700,
            overallRating: 9.0,
            publicReview: "Great place",
            privateMemory: "Secret notes",
            visibility: VisitVisibility.publicAccess.rawValue
        )

        // 1. Commit mutation to local queue
        _ = try await mutationRepo.commitVisit(payload: payload, dimensions: [], photos: [], userId: accountA)

        // 2. Mark as failedRetryable due to POLICY_ACCEPTANCE_REQUIRED
        try await mutationRepo.markFailure(
            mutationId: mutationId,
            generation: 1,
            state: .failedRetryable,
            category: "POLICY_ACCEPTANCE_REQUIRED",
            now: Date(timeIntervalSince1970: 1700000000)
        )

        // Verify mutation is in FAILED_RETRYABLE
        let eligibleBefore = try await mutationRepo.getEligibleMutations(userId: accountA, limit: 10)
        XCTAssertEqual(eligibleBefore.count, 1)
        XCTAssertEqual(eligibleBefore.first?.mutationId, mutationId)
        XCTAssertEqual(eligibleBefore.first?.state, .failedRetryable)

        // 3. Mock remote visit creation on drain
        actor MockDrainVisitService: VisitServing {
            var receivedClientMutationId: UUID?
            var createCallCount = 0

            func create(_ request: VisitCreateRequest) async throws -> OwnerVisit {
                createCallCount += 1
                receivedClientMutationId = request.clientMutationId
                return OwnerVisit(
                    id: UUID(),
                    place: TestPlaces.summary(id: request.placeId),
                    visitedAt: request.visitedAt,
                    overallRating: request.overallRating,
                    dimensions: request.dimensions,
                    publicReview: request.publicReview ?? "",
                    privateMemory: request.privateMemory ?? "",
                    media: [],
                    visibility: request.visibility
                )
            }

            func ownerVisits() async throws -> [OwnerVisit] {
                []
            }
        }

        struct MockSessionOwner: SessionOwnerProvider {
            let userId: UUID
            func currentUserId() async -> UUID? { userId }
        }

        let visitService = MockDrainVisitService()
        let syncEngine = MutationSyncEngine(
            mutationRepository: mutationRepo,
            draftRepository: draftRepo,
            mediaStore: mediaStore,
            mediaService: NullVisitMediaService(),
            visitService: visitService,
            sessionProvider: MockSessionOwner(userId: accountA),
            clock: clock
        )

        // 4. Drain after policy acceptance
        let result = await syncEngine.drain()

        // P14 invariants:
        XCTAssertEqual(result.processed, 1, "P14: Queued visit must be processed upon drain")
        let callCount = await visitService.createCallCount
        let receivedId = await visitService.receivedClientMutationId
        XCTAssertEqual(callCount, 1, "P14: Exactly one remote create must be performed")
        XCTAssertEqual(receivedId, mutationId, "P14: Exact same clientMutationId must be preserved")

        // Mutation removed after successful reconciliation
        let bundleAfter = try await mutationRepo.getVisitBundle(mutationId: mutationId)
        XCTAssertNil(bundleAfter, "P14: Mutation must be removed after successful sync")
    }
}
