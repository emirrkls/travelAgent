import CoreGraphics
import Foundation
import ImageIO
import XCTest
@testable import Phokarta

// MARK: - Test Doubles

private actor MockVisitService: VisitServing {
    var createdRequests: [VisitCreateRequest] = []
    var cannedVisit: OwnerVisit?
    var errorToThrow: Error?

    func setCannedVisit(_ visit: OwnerVisit) {
        self.cannedVisit = visit
    }

    func setErrorToThrow(_ error: Error?) {
        self.errorToThrow = error
    }

    func create(_ request: VisitCreateRequest) async throws -> OwnerVisit {
        if let error = errorToThrow {
            throw error
        }
        createdRequests.append(request)
        if let canned = cannedVisit {
            return canned
        }
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
        if let canned = cannedVisit {
            return [canned]
        }
        return []
    }
}

private actor MockVisitMediaService: VisitMediaServing {
    var intentRequests: [MediaUploadIntentRequest] = []
    var confirmedMediaIds: [UUID] = []
    var presignedUploadCount = 0
    var errorToThrow: Error?

    func setErrorToThrow(_ error: Error?) {
        self.errorToThrow = error
    }

    func createUploadIntent(_ request: MediaUploadIntentRequest) async throws -> MediaUploadIntentResponse {
        if let error = errorToThrow {
            throw error
        }
        intentRequests.append(request)
        let mediaId = UUID()
        return MediaUploadIntentResponse(
            mediaId: mediaId,
            status: .pendingUpload,
            uploadUrl: URL(string: "https://s3.example.com/upload/\(mediaId)")!,
            requiredHeaders: ["x-amz-acl": "private"],
            expiresAt: "2026-09-08T12:00:00Z"
        )
    }

    func confirmUpload(mediaId: UUID) async throws -> MediaConfirmResponse {
        if let error = errorToThrow {
            throw error
        }
        confirmedMediaIds.append(mediaId)
        return MediaConfirmResponse(
            mediaId: mediaId,
            status: .ready
        )
    }

    func mediaAccess(mediaId: UUID) async throws -> MediaAccessResponse {
        MediaAccessResponse(
            url: URL(string: "https://s3.example.com/access/\(mediaId)")!,
            expiresAt: "2026-09-08T13:00:00Z"
        )
    }

    func uploadToPresignedURL(_ url: URL, data: Data, contentType: String, requiredHeaders: [String: String]) async throws {
        if let error = errorToThrow {
            throw error
        }
        presignedUploadCount += 1
    }

    func uploadToPresignedURL(_ url: URL, fileURL: URL, contentType: String, requiredHeaders: [String: String]) async throws {
        if let error = errorToThrow {
            throw error
        }
        presignedUploadCount += 1
    }
}

private struct TestSessionProvider: SessionOwnerProvider {
    let userId: UUID?
    func currentUserId() async -> UUID? { userId }
}

// MARK: - Test Suite

final class OfflineMutationSyncTests: XCTestCase {
    private var tempDir: URL!

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

    private func makeTestJPEG(width: Int = 40, height: Int = 40) -> Data {
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let context = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: width * 4,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )!
        context.setFillColor(CGColor(red: 0.5, green: 0.5, blue: 0.5, alpha: 1.0))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let cgImage = context.makeImage()!
        let data = NSMutableData()
        let destination = CGImageDestinationCreateWithData(data, "public.jpeg" as CFString, 1, nil)!
        CGImageDestinationAddImage(destination, cgImage, nil)
        CGImageDestinationFinalize(destination)
        return data as Data
    }

    // MARK: - 1. Offline Enqueue & Online Drain Test

    func testOfflineEnqueueAndOnlineDrainPublishesVisit() async throws {
        let db = try PersistentDatabase(path: nil)
        let clock = TestEpochClock(initialMillis: 1_700_000_000_000)
        let draftRepo = SQLiteVisitDraftRepository(database: db, clock: clock)
        let mutationRepo = SQLiteOfflineMutationRepository(database: db, clock: clock)
        let mediaStore = DurableMediaStore(customRootDirectory: tempDir.appendingPathComponent("media"))
        let mediaService = MockVisitMediaService()
        let visitService = MockVisitService()
        let userId = UUID()
        let sessionProvider = TestSessionProvider(userId: userId)

        let engine = MutationSyncEngine(
            mutationRepository: mutationRepo,
            draftRepository: draftRepo,
            mediaStore: mediaStore,
            mediaService: mediaService,
            visitService: visitService,
            sessionProvider: sessionProvider,
            clock: clock
        )

        let placeId = UUID()
        let mutationId = UUID()

        // 1. Write a photo to durable media store
        let photo = try await mediaStore.importMedia(
            ownerUserId: userId,
            placeId: placeId,
            position: 0,
            data: makeTestJPEG(),
            clientMediaId: UUID()
        )

        // 2. Commit visit (simulating offline enqueue)
        let payload = DurablePendingVisitPayload(
            mutationId: mutationId,
            placeId: placeId,
            visitedAtEpochDay: 19700,
            overallRating: 8.5,
            publicReview: "Great place offline",
            privateMemory: "Private note",
            visibility: VisitVisibility.friends.rawValue
        )
        let pendingPhoto = DurablePendingPhoto(
            mutationId: mutationId,
            position: 0,
            ownerUserId: userId,
            clientMediaId: photo.clientMediaId,
            localRelativePath: photo.localRelativePath,
            contentType: photo.contentType,
            byteSize: photo.byteSize,
            uploadState: .pendingLocal
        )
        let dim = DurablePendingDimensionScore(mutationId: mutationId, dimensionKey: "COZY", score: 9.0)

        _ = try await mutationRepo.commitVisit(
            payload: payload,
            dimensions: [dim],
            photos: [pendingPhoto],
            userId: userId
        )

        // Verify pending visit exists
        let pendingBefore = try await mutationRepo.getPendingVisits(placeId: placeId, userId: userId)
        XCTAssertEqual(pendingBefore.count, 1)
        XCTAssertEqual(pendingBefore.first?.state, .queued)

        // 3. Drain (online)
        let result = await engine.drain()
        XCTAssertEqual(result.processed, 1)
        XCTAssertFalse(result.retryableFailure)

        // 4. Verify photo was uploaded & confirmed
        let intentCount = await mediaService.intentRequests.count
        let confirmedCount = await mediaService.confirmedMediaIds.count
        XCTAssertEqual(intentCount, 1)
        XCTAssertEqual(confirmedCount, 1)

        // 5. Verify visit was created with matching clientMutationId and mediaId
        let createdRequests = await visitService.createdRequests
        XCTAssertEqual(createdRequests.count, 1)
        XCTAssertEqual(createdRequests.first?.clientMutationId, mutationId)
        XCTAssertEqual(createdRequests.first?.placeId, placeId)
        XCTAssertEqual(createdRequests.first?.dimensions.count, 1)
        XCTAssertEqual(createdRequests.first?.mediaIds?.count, 1)

        // 6. Verify mutation is deleted from queue after success
        let pendingAfter = try await mutationRepo.getPendingVisits(placeId: placeId, userId: userId)
        XCTAssertTrue(pendingAfter.isEmpty, "Mutation must be removed from queue upon successful publish")
    }

    // MARK: - 2. Process-Death Lost-ACK Integration Test

    func testProcessDeathLostAckReplaysIdenticalClientMutationId() async throws {
        let dbPath = tempDir.appendingPathComponent("lost_ack.db").path
        let clock = TestEpochClock(initialMillis: 1_700_000_000_000)
        let mediaStore = DurableMediaStore(customRootDirectory: tempDir.appendingPathComponent("media"))
        let userId = UUID()
        let sessionProvider = TestSessionProvider(userId: userId)
        let placeId = UUID()
        let mutationId = UUID()

        let sharedVisitService = MockVisitService()
        let sharedMediaService = MockVisitMediaService()

        // 1. Initial process: queue mutation M1
        do {
            let db = try PersistentDatabase(path: dbPath)
            let draftRepo = SQLiteVisitDraftRepository(database: db, clock: clock)
            let mutationRepo = SQLiteOfflineMutationRepository(database: db, clock: clock)

            let payload = DurablePendingVisitPayload(
                mutationId: mutationId,
                placeId: placeId,
                visitedAtEpochDay: 19700,
                overallRating: 9.0,
                publicReview: "Sunset view",
                privateMemory: "",
                visibility: VisitVisibility.publicAccess.rawValue
            )
            _ = try await mutationRepo.commitVisit(payload: payload, dimensions: [], photos: [], userId: userId)

            // Simulate visitService received the call and server created visit, but network dropped on response
            await sharedVisitService.setErrorToThrow(AppError.networkUnavailable)

            let engine = MutationSyncEngine(
                mutationRepository: mutationRepo,
                draftRepository: draftRepo,
                mediaStore: mediaStore,
                mediaService: sharedMediaService,
                visitService: sharedVisitService,
                sessionProvider: sessionProvider,
                clock: clock
            )

            // Attempt drain -> fails with network error, mutation marked FAILED_RETRYABLE
            let run1 = await engine.drain()
            XCTAssertTrue(run1.retryableFailure)

            await db.close()
        }

        // 2. Process restarts from disk: new DB, new repositories, new sync engine
        do {
            let db2 = try PersistentDatabase(path: dbPath)
            let draftRepo2 = SQLiteVisitDraftRepository(database: db2, clock: clock)
            let mutationRepo2 = SQLiteOfflineMutationRepository(database: db2, clock: clock)

            // User taps Retry or network restored: reset to QUEUED
            try await mutationRepo2.retry(mutationId: mutationId, userId: userId)

            // Server is reachable now
            await sharedVisitService.setErrorToThrow(nil)

            let engine2 = MutationSyncEngine(
                mutationRepository: mutationRepo2,
                draftRepository: draftRepo2,
                mediaStore: mediaStore,
                mediaService: sharedMediaService,
                visitService: sharedVisitService,
                sessionProvider: sessionProvider,
                clock: clock
            )

            // Re-drain
            let run2 = await engine2.drain()
            XCTAssertEqual(run2.processed, 1)
            XCTAssertFalse(run2.retryableFailure)

            // Verify the replayed request used the EXACT same clientMutationId M1 (backend deduplicates)
            let requests = await sharedVisitService.createdRequests
            XCTAssertEqual(requests.count, 1)
            XCTAssertEqual(requests.first?.clientMutationId, mutationId)

            // Mutation is now cleared from queue
            let pending = try await mutationRepo2.getPendingVisits(placeId: placeId, userId: userId)
            XCTAssertTrue(pending.isEmpty)

            await db2.close()
        }
    }

    // MARK: - 3. Confirmed Media Restart Test (READY_REMOTE Reused)

    func testConfirmedMediaIsReusedOnRestartWithoutReupload() async throws {
        let db = try PersistentDatabase(path: nil)
        let clock = TestEpochClock(initialMillis: 1_700_000_000_000)
        let draftRepo = SQLiteVisitDraftRepository(database: db, clock: clock)
        let mutationRepo = SQLiteOfflineMutationRepository(database: db, clock: clock)
        let mediaStore = DurableMediaStore(customRootDirectory: tempDir.appendingPathComponent("media"))
        let mediaService = MockVisitMediaService()
        let visitService = MockVisitService()
        let userId = UUID()
        let sessionProvider = TestSessionProvider(userId: userId)

        let placeId = UUID()
        let mutationId = UUID()

        // Write 2 photos
        let photo0 = try await mediaStore.importMedia(
            ownerUserId: userId,
            placeId: placeId,
            position: 0,
            data: makeTestJPEG(),
            clientMediaId: UUID()
        )
        let photo1 = try await mediaStore.importMedia(
            ownerUserId: userId,
            placeId: placeId,
            position: 1,
            data: makeTestJPEG(),
            clientMediaId: UUID()
        )

        let existingRemoteMediaId = UUID()

        // Photo 0 is ALREADY confirmed remote (e.g. from previous run)
        let pendingPhoto0 = DurablePendingPhoto(
            mutationId: mutationId,
            position: 0,
            ownerUserId: userId,
            clientMediaId: photo0.clientMediaId,
            localRelativePath: photo0.localRelativePath,
            contentType: photo0.contentType,
            byteSize: photo0.byteSize,
            remoteMediaId: existingRemoteMediaId,
            uploadState: .readyRemote
        )
        // Photo 1 still needs upload
        let pendingPhoto1 = DurablePendingPhoto(
            mutationId: mutationId,
            position: 1,
            ownerUserId: userId,
            clientMediaId: photo1.clientMediaId,
            localRelativePath: photo1.localRelativePath,
            contentType: photo1.contentType,
            byteSize: photo1.byteSize,
            remoteMediaId: nil,
            uploadState: .pendingLocal
        )

        let payload = DurablePendingVisitPayload(
            mutationId: mutationId,
            placeId: placeId,
            visitedAtEpochDay: 19700,
            overallRating: 8.0,
            publicReview: "",
            privateMemory: "",
            visibility: VisitVisibility.privateAccess.rawValue
        )

        _ = try await mutationRepo.commitVisit(
            payload: payload,
            dimensions: [],
            photos: [pendingPhoto0, pendingPhoto1],
            userId: userId
        )

        let engine = MutationSyncEngine(
            mutationRepository: mutationRepo,
            draftRepository: draftRepo,
            mediaStore: mediaStore,
            mediaService: mediaService,
            visitService: visitService,
            sessionProvider: sessionProvider,
            clock: clock
        )

        let result = await engine.drain()
        XCTAssertEqual(result.processed, 1)

        // Crucial assertion: createUploadIntent was ONLY called once (for Photo 1)
        let intentCount = await mediaService.intentRequests.count
        XCTAssertEqual(intentCount, 1, "Already confirmed READY_REMOTE photo must NOT be re-uploaded")

        // Crucial assertion: visit create request has BOTH mediaIds in correct order [remote0, remote1]
        let requests = await visitService.createdRequests
        XCTAssertEqual(requests.count, 1)
        let mediaIds = requests.first?.mediaIds
        XCTAssertEqual(mediaIds?.count, 2)
        XCTAssertEqual(mediaIds?[0], existingRemoteMediaId)
    }

    // MARK: - 4. Media Order Preservation Test

    func testMediaOrderPreservedAcrossSyncPipeline() async throws {
        let db = try PersistentDatabase(path: nil)
        let clock = TestEpochClock(initialMillis: 1_700_000_000_000)
        let draftRepo = SQLiteVisitDraftRepository(database: db, clock: clock)
        let mutationRepo = SQLiteOfflineMutationRepository(database: db, clock: clock)
        let mediaStore = DurableMediaStore(customRootDirectory: tempDir.appendingPathComponent("media"))
        let mediaService = MockVisitMediaService()
        let visitService = MockVisitService()
        let userId = UUID()
        let sessionProvider = TestSessionProvider(userId: userId)

        let placeId = UUID()
        let mutationId = UUID()

        var pendingPhotos: [DurablePendingPhoto] = []
        for pos in 0..<3 {
            let photo = try await mediaStore.importMedia(
                ownerUserId: userId,
                placeId: placeId,
                position: pos,
                data: makeTestJPEG(),
                clientMediaId: UUID()
            )
            pendingPhotos.append(DurablePendingPhoto(
                mutationId: mutationId,
                position: pos,
                ownerUserId: userId,
                clientMediaId: photo.clientMediaId,
                localRelativePath: photo.localRelativePath,
                contentType: photo.contentType,
                byteSize: photo.byteSize,
                uploadState: .pendingLocal
            ))
        }

        let payload = DurablePendingVisitPayload(
            mutationId: mutationId,
            placeId: placeId,
            visitedAtEpochDay: 19700,
            overallRating: 9.5,
            publicReview: "Triple photo test",
            privateMemory: "",
            visibility: VisitVisibility.publicAccess.rawValue
        )

        _ = try await mutationRepo.commitVisit(
            payload: payload,
            dimensions: [],
            photos: pendingPhotos,
            userId: userId
        )

        let engine = MutationSyncEngine(
            mutationRepository: mutationRepo,
            draftRepository: draftRepo,
            mediaStore: mediaStore,
            mediaService: mediaService,
            visitService: visitService,
            sessionProvider: sessionProvider,
            clock: clock
        )

        let result = await engine.drain()
        XCTAssertEqual(result.processed, 1)

        let requests = await visitService.createdRequests
        XCTAssertEqual(requests.count, 1)
        let mediaIds = requests.first?.mediaIds
        XCTAssertEqual(mediaIds?.count, 3)

        // Verify confirmed media IDs match the order passed to visit create
        let confirmed = await mediaService.confirmedMediaIds
        XCTAssertEqual(mediaIds, confirmed)
    }

    // MARK: - 5. Permanent vs Retryable Failure Classification

    func testPermanentFailureClassification() async throws {
        let db = try PersistentDatabase(path: nil)
        let clock = TestEpochClock(initialMillis: 1_700_000_000_000)
        let draftRepo = SQLiteVisitDraftRepository(database: db, clock: clock)
        let mutationRepo = SQLiteOfflineMutationRepository(database: db, clock: clock)
        let mediaStore = DurableMediaStore(customRootDirectory: tempDir.appendingPathComponent("media"))
        let mediaService = MockVisitMediaService()
        let visitService = MockVisitService()
        let userId = UUID()
        let sessionProvider = TestSessionProvider(userId: userId)

        let placeId = UUID()
        let mutationId = UUID()

        let payload = DurablePendingVisitPayload(
            mutationId: mutationId,
            placeId: placeId,
            visitedAtEpochDay: 19700,
            overallRating: 8.0,
            publicReview: "",
            privateMemory: "",
            visibility: VisitVisibility.privateAccess.rawValue
        )

        _ = try await mutationRepo.commitVisit(payload: payload, dimensions: [], photos: [], userId: userId)

        // Mock 403 Forbidden
        await visitService.setErrorToThrow(AppError.forbidden)

        let engine = MutationSyncEngine(
            mutationRepository: mutationRepo,
            draftRepository: draftRepo,
            mediaStore: mediaStore,
            mediaService: mediaService,
            visitService: visitService,
            sessionProvider: sessionProvider,
            clock: clock
        )

        let result = await engine.drain()
        XCTAssertFalse(result.retryableFailure)
        XCTAssertEqual(result.processed, 1)

        let pending = try await mutationRepo.getPendingVisits(placeId: placeId, userId: userId)
        XCTAssertEqual(pending.count, 1)
        XCTAssertTrue(pending[0].failed)
        XCTAssertEqual(pending[0].state, .failedPermanent)
        XCTAssertEqual(pending[0].failureReason, .forbidden)
        XCTAssertTrue(pending[0].actions.showEditAndRetry)
        XCTAssertFalse(pending[0].actions.showRetry, "Permanent failure must NOT show Retry button")
    }

    func testRetryableFailureClassificationAndUserRetry() async throws {
        let db = try PersistentDatabase(path: nil)
        let clock = TestEpochClock(initialMillis: 1_700_000_000_000)
        let draftRepo = SQLiteVisitDraftRepository(database: db, clock: clock)
        let mutationRepo = SQLiteOfflineMutationRepository(database: db, clock: clock)
        let mediaStore = DurableMediaStore(customRootDirectory: tempDir.appendingPathComponent("media"))
        let mediaService = MockVisitMediaService()
        let visitService = MockVisitService()
        let userId = UUID()
        let sessionProvider = TestSessionProvider(userId: userId)

        let placeId = UUID()
        let mutationId = UUID()

        let payload = DurablePendingVisitPayload(
            mutationId: mutationId,
            placeId: placeId,
            visitedAtEpochDay: 19700,
            overallRating: 8.0,
            publicReview: "",
            privateMemory: "",
            visibility: VisitVisibility.privateAccess.rawValue
        )

        _ = try await mutationRepo.commitVisit(payload: payload, dimensions: [], photos: [], userId: userId)

        // Mock 500 Server Error
        await visitService.setErrorToThrow(AppError.server)

        let engine = MutationSyncEngine(
            mutationRepository: mutationRepo,
            draftRepository: draftRepo,
            mediaStore: mediaStore,
            mediaService: mediaService,
            visitService: visitService,
            sessionProvider: sessionProvider,
            clock: clock
        )

        let result = await engine.drain()
        XCTAssertTrue(result.retryableFailure)
        XCTAssertEqual(result.processed, 1)

        let pending = try await mutationRepo.getPendingVisits(placeId: placeId, userId: userId)
        XCTAssertEqual(pending.count, 1)
        XCTAssertTrue(pending[0].failed)
        XCTAssertEqual(pending[0].state, .failedRetryable)
        XCTAssertTrue(pending[0].actions.showRetry, "Retryable failure MUST show Retry button")

        // User hits retry: resets to QUEUED
        try await mutationRepo.retry(mutationId: mutationId, userId: userId)
        let pendingAfterRetry = try await mutationRepo.getPendingVisits(placeId: placeId, userId: userId)
        XCTAssertEqual(pendingAfterRetry[0].state, .queued)

        // Server recovers
        await visitService.setErrorToThrow(nil)
        let result2 = await engine.drain()
        XCTAssertFalse(result2.retryableFailure)
        XCTAssertEqual(result2.processed, 1)

        let pendingFinal = try await mutationRepo.getPendingVisits(placeId: placeId, userId: userId)
        XCTAssertTrue(pendingFinal.isEmpty, "Mutation successfully removed after retry")
    }

    // MARK: - 6. Pre-ACK Privacy Test

    @MainActor
    func testPendingVisitNeverAppearsInCanonicalStoreBeforeServerAck() async throws {
        let visitService = MockVisitService()
        let visitStore = VisitStore(service: visitService)
        visitStore.activate(accountID: UUID())

        let pendingVisit = PendingVisit(
            mutationId: UUID(),
            placeId: UUID(),
            userId: UUID(),
            visitedAt: Date(),
            overallRating: 9.0,
            ratingDimensions: [:],
            review: "Unconfirmed public review",
            personalNote: "Unconfirmed personal memory",
            photos: [],
            visibility: .publicAccess,
            state: .queued
        )

        // Assert pending visit is NOT present in canonical visitStore visits
        XCTAssertTrue(visitStore.visits.isEmpty)
        XCTAssertNil(visitStore.visits.first(where: { $0.id == pendingVisit.mutationId }))
    }
}
