import XCTest
import SQLite3
@testable import Phokarta

@MainActor
final class AccountDeletionFeatureTests: XCTestCase {
    private var tempDir: URL!
    private let userA = UUID(uuidString: "11111111-1111-1111-1111-111111111111")!
    private let userB = UUID(uuidString: "22222222-2222-2222-2222-222222222222")!

    override func setUp() async throws {
        try await super.setUp()
        tempDir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        if let tempDir {
            try? FileManager.default.removeItem(at: tempDir)
        }
        try await super.tearDown()
    }

    // MARK: - 76. CANONICAL DELETE SUCCESS TEST (P8)

    func testCanonicalAccountDeletionSuccess() async throws {
        let mockPurger = MockAccountPurger()

        var sessionResetCalled = false
        var signOutCalled = false

        let store = InMemorySessionStore(session: testSession(access: "access-1"))
        let config = try TestConfig.httpsTest()
        let transport = FakeHTTPTransport { request in
            XCTAssertEqual(request.httpMethod, "DELETE")
            XCTAssertEqual(request.url?.path, "/api/v1/me")
            let body = try JSONDecoder().decode(DeleteAccountRequestDTO.self, from: request.httpBody!)
            XCTAssertEqual(body.currentPassword, "SecretPassword123!")
            return TestJSON.http(request.url!, status: 204)
        }
        let refresh = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)
        let service = AccountDeletionService(client: client)

        let targetUser = userA
        let controller = DeleteAccountController(
            service: service,
            purger: mockPurger,
            sessionReset: { sessionResetCalled = true },
            signOut: { signOutCalled = true },
            currentUserId: { targetUser }
        )

        controller.showConfirmation()
        controller.currentPassword = "SecretPassword123!"
        controller.confirmDeletion()

        try await Task.sleep(nanoseconds: 50_000_000)

        // P8 invariants:
        XCTAssertEqual(controller.phase, DeleteAccountPhase.success, "P8: Deletion controller must reach .success phase")
        XCTAssertTrue(sessionResetCalled, "P8: In-memory session state must be reset")
        XCTAssertTrue(signOutCalled, "P8: Session must be signed out")
        let purgedUser = await mockPurger.purgedUser
        XCTAssertEqual(purgedUser, userA, "P8: Purger must be called for deleted user")
    }

    // MARK: - 75. LOST ACK DELETE TEST (P9)

    func testLostAckDeleteConvergesToSignedOut() async throws {
        let mockPurger = MockAccountPurger()

        var sessionResetCalled = false
        var signOutCalled = false

        let store = InMemorySessionStore(session: testSession(access: "access-1"))
        let config = try TestConfig.httpsTest()

        // Deletion succeeded on server, but client sees 401 UNAUTHORIZED (user already gone)
        let transport = FakeHTTPTransport { request in
            return TestJSON.http(
                request.url!,
                status: 401,
                data: TestJSON.apiError(status: 401, code: "UNAUTHORIZED", message: "User not found")
            )
        }
        let refresh = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)
        let service = AccountDeletionService(client: client)

        let targetUser = userA
        let controller = DeleteAccountController(
            service: service,
            purger: mockPurger,
            sessionReset: { sessionResetCalled = true },
            signOut: { signOutCalled = true },
            currentUserId: { targetUser }
        )

        controller.showConfirmation()
        controller.currentPassword = "Password"
        controller.confirmDeletion()

        try await Task.sleep(nanoseconds: 50_000_000)

        // P9 invariant: Lost-ACK converges to signed-out and purges local data
        XCTAssertEqual(controller.phase, DeleteAccountPhase.success)
        XCTAssertTrue(sessionResetCalled)
        XCTAssertTrue(signOutCalled)
        let purgedUser = await mockPurger.purgedUser
        XCTAssertEqual(purgedUser, userA)
    }

    // MARK: - 99. SQLITE ACCOUNT PURGE TEST (P10, P16)

    func testSqliteAccountPurgeDiskBackedIsolation() async throws {
        let dbPath = tempDir.appendingPathComponent("purge_test.db").path
        let db = try PersistentDatabase(path: dbPath)
        let clock = TestEpochClock(initialMillis: 1_700_000_000_000)
        let draftRepo = SQLiteVisitDraftRepository(database: db, clock: clock)
        let mutationRepo = SQLiteOfflineMutationRepository(database: db, clock: clock)
        let purger = SQLiteLocalAccountPurger(database: db)

        let placeA = UUID()
        let placeB = UUID()

        // User A draft
        let draftA = DurableVisitDraft(
            userId: userA,
            placeId: placeA,
            overallScore: 9.0,
            publicReview: "User A review",
            privateMemory: "User A private memory",
            visitedAtEpochDay: 19700,
            visibility: VisitVisibility.publicAccess.rawValue,
            dimensionsExpanded: false,
            createdAtEpochMillis: clock.nowMillis(),
            updatedAtEpochMillis: clock.nowMillis()
        )
        try await draftRepo.saveDraft(placeId: placeA, draft: draftA, userId: userA)

        // User B draft
        let draftB = DurableVisitDraft(
            userId: userB,
            placeId: placeB,
            overallScore: 8.0,
            publicReview: "User B review",
            privateMemory: "User B private memory",
            visitedAtEpochDay: 19700,
            visibility: VisitVisibility.publicAccess.rawValue,
            dimensionsExpanded: false,
            createdAtEpochMillis: clock.nowMillis(),
            updatedAtEpochMillis: clock.nowMillis()
        )
        try await draftRepo.saveDraft(placeId: placeB, draft: draftB, userId: userB)

        // User A mutation
        let mutationA = DurablePendingVisitPayload(
            mutationId: UUID(),
            placeId: placeA,
            visitedAtEpochDay: 19700,
            overallRating: 9.0,
            publicReview: "Review A",
            privateMemory: "Memory A",
            visibility: VisitVisibility.publicAccess.rawValue
        )
        _ = try await mutationRepo.commitVisit(payload: mutationA, dimensions: [], photos: [], userId: userA)

        // User B mutation
        let mutationB = DurablePendingVisitPayload(
            mutationId: UUID(),
            placeId: placeB,
            visitedAtEpochDay: 19700,
            overallRating: 8.0,
            publicReview: "Review B",
            privateMemory: "Memory B",
            visibility: VisitVisibility.publicAccess.rawValue
        )
        _ = try await mutationRepo.commitVisit(payload: mutationB, dimensions: [], photos: [], userId: userB)

        // PURGE USER A ONLY
        try await purger.purgeLocalData(userId: userA)

        // P10, P16 Invariants:
        // User A data must be completely gone
        let draftAfterA = try await draftRepo.getDraft(placeId: placeA, userId: userA)
        XCTAssertNil(draftAfterA, "P10: User A draft must be purged")

        let mutationsAfterA = try await mutationRepo.getEligibleMutations(userId: userA, limit: 10)
        XCTAssertTrue(mutationsAfterA.isEmpty, "P10: User A mutations must be purged")

        // User B data must be completely intact!
        let draftAfterB = try await draftRepo.getDraft(placeId: placeB, userId: userB)
        XCTAssertNotNil(draftAfterB, "P16: User B draft must remain intact")
        XCTAssertEqual(draftAfterB?.privateMemory, "User B private memory")

        let mutationsAfterB = try await mutationRepo.getEligibleMutations(userId: userB, limit: 10)
        XCTAssertEqual(mutationsAfterB.count, 1, "P16: User B mutations must remain intact")
    }

    // MARK: - 97. PRIVATE MEMORY DELETE TEST (P10)

    func testPrivateMemoryNotQueryableAfterPurge() async throws {
        let db = try PersistentDatabase(path: nil)
        let clock = TestEpochClock(initialMillis: 1_700_000_000_000)
        let draftRepo = SQLiteVisitDraftRepository(database: db, clock: clock)
        let purger = SQLiteLocalAccountPurger(database: db)

        let placeId = UUID()
        let draft = DurableVisitDraft(
            userId: userA,
            placeId: placeId,
            overallScore: 8.5,
            publicReview: "Review",
            privateMemory: "Extremely sensitive personal notes 12345",
            visitedAtEpochDay: 19700,
            visibility: VisitVisibility.privateAccess.rawValue,
            dimensionsExpanded: false,
            createdAtEpochMillis: clock.nowMillis(),
            updatedAtEpochMillis: clock.nowMillis()
        )
        try await draftRepo.saveDraft(placeId: placeId, draft: draft, userId: userA)

        // Purge
        try await purger.purgeLocalData(userId: userA)

        // Direct SQL query to verify sensitive text is nowhere in the database
        let results = try await db.query(
            "SELECT privateMemory FROM visit_drafts WHERE userId = ?;",
            params: [userA.uuidString]
        ) { stmt in
            String(cString: sqlite3_column_text(stmt, 0))
        }
        XCTAssertTrue(results.isEmpty, "P10: Private memory must be completely deleted from disk")
    }

    // MARK: - 98. LOCAL MEDIA DELETE TEST (P16)

    func testLocalMediaIsolationAfterPurge() async throws {
        let storeDir = tempDir.appendingPathComponent("media")
        let store = DurableMediaStore(customRootDirectory: storeDir)
        let purger = SQLiteLocalAccountPurger(database: try PersistentDatabase(path: nil), mediaStore: store)

        let dummyData = Data("JPEG_DATA_DUMMY".utf8)
        let photoA = try await store.importMedia(ownerUserId: userA, placeId: UUID(), position: 0, data: dummyData, clientMediaId: nil)
        let photoB = try await store.importMedia(ownerUserId: userB, placeId: UUID(), position: 0, data: dummyData, clientMediaId: nil)

        // Both files exist initially
        let urlA = store.resolveOwned(ownerUserId: userA, relativePath: photoA.localRelativePath)!
        let urlB = store.resolveOwned(ownerUserId: userB, relativePath: photoB.localRelativePath)!
        XCTAssertTrue(FileManager.default.fileExists(atPath: urlA.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: urlB.path))

        // Purge User A
        try await purger.purgeLocalData(userId: userA)

        // P16 Invariant: User A files deleted, User B files untouched
        XCTAssertFalse(FileManager.default.fileExists(atPath: urlA.path), "P16: User A media files must be deleted")
        XCTAssertTrue(FileManager.default.fileExists(atPath: urlB.path), "P16: User B media files must remain intact")
    }

    // MARK: - 79. SAME EMAIL NEW UUID TEST (P10)

    func testSameEmailNewUUIDCannotAccessOldAccountData() async throws {
        let db = try PersistentDatabase(path: nil)
        let clock = TestEpochClock(initialMillis: 1_700_000_000_000)
        let draftRepo = SQLiteVisitDraftRepository(database: db, clock: clock)
        let purger = SQLiteLocalAccountPurger(database: db)

        // Old account A deleted
        let placeId = UUID()
        let draftA = DurableVisitDraft(
            userId: userA,
            placeId: placeId,
            overallScore: 7.0,
            publicReview: "Old review",
            privateMemory: "Old memory",
            visitedAtEpochDay: 19700,
            visibility: VisitVisibility.publicAccess.rawValue,
            dimensionsExpanded: false,
            createdAtEpochMillis: clock.nowMillis(),
            updatedAtEpochMillis: clock.nowMillis()
        )
        try await draftRepo.saveDraft(placeId: placeId, draft: draftA, userId: userA)
        try await purger.purgeLocalData(userId: userA)

        // New account registered with new UUID B
        let newDraft = try await draftRepo.getDraft(placeId: placeId, userId: userB)
        XCTAssertNil(newDraft, "P10: New UUID must not see old account's draft")
    }

    // MARK: - 77. LOCAL PURGE FAILURE TEST

    func testLocalPurgeFailureStillSignsOut() async throws {
        struct FailingPurger: LocalAccountPurger {
            func purgeLocalData(userId: UUID) async throws {
                throw PersistenceError.executionFailed("Simulated DB lock error")
            }
        }

        let store = InMemorySessionStore(session: testSession(access: "access-1"))
        let config = try TestConfig.httpsTest()
        let transport = FakeHTTPTransport { request in
            TestJSON.http(request.url!, status: 204)
        }
        let refresh = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)
        let service = AccountDeletionService(client: client)

        var signOutCalled = false

        let targetUser = userA
        let controller = DeleteAccountController(
            service: service,
            purger: FailingPurger(),
            sessionReset: {},
            signOut: { signOutCalled = true },
            currentUserId: { targetUser }
        )

        controller.showConfirmation()
        controller.currentPassword = "Pass"
        controller.confirmDeletion()

        try await Task.sleep(nanoseconds: 50_000_000)

        // Even with local purge error: session cleared and signed-out
        XCTAssertEqual(controller.phase, DeleteAccountPhase.success)
        XCTAssertTrue(signOutCalled, "Must sign out even if local purge fails")
    }

    // MARK: - 110. SENSITIVE DATA NOT LOGGED (P17)

    func testDeleteAccountRequestDTORedactsPassword() {
        let req = DeleteAccountRequestDTO(currentPassword: "SuperSecretPassword123")
        XCTAssertFalse(req.description.contains("SuperSecretPassword123"), "P17: Password must not appear in description")
        XCTAssertTrue(req.description.contains("REDACTED"))
    }
}

// MARK: - Helper

private actor MockAccountPurger: LocalAccountPurger {
    private(set) var purgedUser: UUID?

    func purgeLocalData(userId: UUID) async throws {
        purgedUser = userId
    }
}
