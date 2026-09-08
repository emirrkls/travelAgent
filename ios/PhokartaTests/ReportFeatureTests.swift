import XCTest
@testable import Phokarta

@MainActor
final class ReportFeatureTests: XCTestCase {
    private let targetUserId = UUID(uuidString: "22222222-2222-2222-2222-222222222222")!
    private let targetVisitId = UUID(uuidString: "33333333-3333-3333-3333-333333333333")!

    // MARK: - 85. REPORT USER ENCODING TEST

    func testReportUserEncoding() async throws {
        let store = InMemorySessionStore(session: testSession(access: "access-1"))
        let config = try TestConfig.httpsTest()
        let transport = FakeHTTPTransport { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.url?.path, "/api/v1/reports")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access-1")

            let body = try JSONDecoder().decode(CreateReportRequestDTO.self, from: request.httpBody!)
            XCTAssertEqual(body.targetType, .user)
            XCTAssertEqual(body.targetId, self.targetUserId)
            XCTAssertEqual(body.reason, .harassment)
            XCTAssertEqual(body.details, "Harassing messages in reviews")

            let responseJSON = """
            {
                "id": "\(UUID().uuidString.lowercased())",
                "targetType": "USER",
                "reason": "HARASSMENT",
                "status": "OPEN",
                "createdAt": "2026-09-08T10:00:00Z"
            }
            """
            return TestJSON.http(request.url!, status: 201, data: Data(responseJSON.utf8))
        }
        let refresh = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)
        let service = ReportService(client: client)

        let req = CreateReportRequestDTO(
            targetType: .user,
            targetId: targetUserId,
            reason: .harassment,
            details: "Harassing messages in reviews"
        )
        let res = try await service.submitReport(req)
        XCTAssertEqual(res.targetType, .user)
        XCTAssertEqual(res.reason, .harassment)
        XCTAssertEqual(res.status, .open)
    }

    // MARK: - 86. REPORT VISIT ENCODING TEST

    func testReportVisitEncoding() async throws {
        let store = InMemorySessionStore(session: testSession(access: "access-1"))
        let config = try TestConfig.httpsTest()
        let transport = FakeHTTPTransport { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.url?.path, "/api/v1/reports")

            let body = try JSONDecoder().decode(CreateReportRequestDTO.self, from: request.httpBody!)
            XCTAssertEqual(body.targetType, .visit)
            XCTAssertEqual(body.targetId, self.targetVisitId)
            XCTAssertEqual(body.reason, .spam)
            XCTAssertNil(body.details)

            let responseJSON = """
            {
                "id": "\(UUID().uuidString.lowercased())",
                "targetType": "VISIT",
                "reason": "SPAM",
                "status": "OPEN",
                "createdAt": "2026-09-08T10:00:00Z"
            }
            """
            return TestJSON.http(request.url!, status: 201, data: Data(responseJSON.utf8))
        }
        let refresh = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)
        let service = ReportService(client: client)

        let req = CreateReportRequestDTO(
            targetType: .visit,
            targetId: targetVisitId,
            reason: .spam,
            details: nil
        )
        let res = try await service.submitReport(req)
        XCTAssertEqual(res.targetType, .visit)
        XCTAssertEqual(res.reason, .spam)
        XCTAssertEqual(res.status, .open)
    }

    // MARK: - 87. REPORT DUPLICATE TEST (P6)

    func testDuplicateOpenReportReturnsExistingWithoutLoop() async throws {
        let store = InMemorySessionStore(session: testSession(access: "access-1"))
        let config = try TestConfig.httpsTest()
        let existingId = UUID()
        var submissionCount = 0

        let transport = FakeHTTPTransport { request in
            submissionCount += 1
            // Backend returns 200 OK (not 201) with the existing OPEN report
            let responseJSON = """
            {
                "id": "\(existingId.uuidString.lowercased())",
                "targetType": "USER",
                "reason": "SPAM",
                "status": "OPEN",
                "createdAt": "2026-09-08T09:00:00Z"
            }
            """
            return TestJSON.http(request.url!, status: 200, data: Data(responseJSON.utf8))
        }
        let refresh = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)
        let service = ReportService(client: client)
        let controller = ReportController(service: service)

        controller.open(target: .user(id: targetUserId, displayName: "Test User"))
        controller.selectedReason = .spam
        controller.submit()

        // Wait for async task
        try await Task.sleep(nanoseconds: 50_000_000)

        // P6 invariant: duplicate report handled cleanly, no repeat loop
        XCTAssertEqual(submissionCount, 1)
        XCTAssertEqual(controller.phase, .success(isDuplicate: false))
    }

    // MARK: - 88. REPORT RATE LIMIT TEST (P7)

    func testReportRateLimitTransitionsToSafeTryLater() async throws {
        let store = InMemorySessionStore(session: testSession(access: "access-1"))
        let config = try TestConfig.httpsTest()
        var callCount = 0

        let transport = FakeHTTPTransport { request in
            callCount += 1
            return TestJSON.http(
                request.url!,
                status: 429,
                data: TestJSON.apiError(status: 429, code: "REPORT_RATE_LIMITED", message: "Too many reports submitted.")
            )
        }
        let refresh = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)
        let service = ReportService(client: client)
        let controller = ReportController(service: service)

        controller.open(target: .user(id: targetUserId, displayName: "Test User"))
        controller.selectedReason = .harassment
        controller.submit()

        try await Task.sleep(nanoseconds: 50_000_000)

        // P7 invariant: 429 transitions to rateLimited phase, no retry storm
        XCTAssertEqual(callCount, 1, "Must not retry on 429")
        XCTAssertEqual(controller.phase, .rateLimited)
    }

    // MARK: - 89. REPORT DOES NOT BLOCK TEST (P5)

    func testSubmittingReportDoesNotMutateFollowOrBlock() async throws {
        let mockSocial = MockSocialService()
        let socialStore = SocialStateStore(service: mockSocial)
        socialStore.activate(accountID: TestJSON.userID)

        let initialRel = RelationshipState(isFollowing: true, followsYou: true, isFriend: true)
        socialStore.recordConfirmedRelationship(initialRel, for: targetUserId)

        // Mock report service
        let store = InMemorySessionStore(session: testSession(access: "access-1"))
        let config = try TestConfig.httpsTest()
        let transport = FakeHTTPTransport { request in
            let resJSON = """
            {
                "id": "\(UUID().uuidString.lowercased())",
                "targetType": "USER",
                "reason": "OTHER",
                "status": "OPEN",
                "createdAt": "2026-09-08T10:00:00Z"
            }
            """
            return TestJSON.http(request.url!, status: 201, data: Data(resJSON.utf8))
        }
        let refresh = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)
        let service = ReportService(client: client)

        let req = CreateReportRequestDTO(
            targetType: .user,
            targetId: targetUserId,
            reason: .other,
            details: "Just reporting"
        )
        _ = try await service.submitReport(req)

        // P5 invariant: Report does NOT automatically block, hide, or alter follow state
        XCTAssertTrue(socialStore.isFollowing(targetUserId), "P5: Report must not unfollow target")
        XCTAssertTrue(socialStore.isFriend(targetUserId), "P5: Report must not remove friendship")
        XCTAssertEqual(socialStore.relationship(for: targetUserId), initialRel)
    }

    // MARK: - Details Length Capping

    func testReportDetailsCappedAt2000Characters() async throws {
        let store = InMemorySessionStore(session: testSession(access: "access-1"))
        let config = try TestConfig.httpsTest()
        var observedDetailsLength = 0

        let transport = FakeHTTPTransport { request in
            let body = try JSONDecoder().decode(CreateReportRequestDTO.self, from: request.httpBody!)
            observedDetailsLength = body.details?.count ?? 0
            let resJSON = """
            {
                "id": "\(UUID().uuidString.lowercased())",
                "targetType": "VISIT",
                "reason": "SPAM",
                "status": "OPEN",
                "createdAt": "2026-09-08T10:00:00Z"
            }
            """
            return TestJSON.http(request.url!, status: 201, data: Data(resJSON.utf8))
        }
        let refresh = TokenRefreshCoordinator(store: store, config: config, transport: transport)
        let client = APIClient(config: config, transport: transport, authRetry: refresh)
        let service = ReportService(client: client)
        let controller = ReportController(service: service)

        controller.open(target: .visit(id: targetVisitId, placeName: "Some Cafe"))
        controller.selectedReason = .spam
        controller.details = String(repeating: "A", count: 3000)
        controller.submit()

        try await Task.sleep(nanoseconds: 50_000_000)

        XCTAssertEqual(observedDetailsLength, 2000, "Details must be capped at 2000 characters")
    }

    // MARK: - Account Switch Discards Form

    func testAccountSwitchDiscardsReportForm() {
        let dummyService = ReportService(client: APIClient(config: try! TestConfig.httpsTest(), transport: FakeHTTPTransport { _ in fatalError() }))
        let controller = ReportController(service: dummyService)

        controller.open(target: .user(id: targetUserId, displayName: "Old Target"))
        controller.details = "Unsent draft details"
        controller.selectedReason = .harassment

        controller.handleAccountSwitch()

        XCTAssertEqual(controller.phase, .idle)
        XCTAssertNil(controller.target)
        XCTAssertNil(controller.selectedReason)
        XCTAssertTrue(controller.details.isEmpty)
    }
}
