import CoreGraphics
import Foundation
import ImageIO
import XCTest
@testable import Phokarta

final class DurablePersistenceTests: XCTestCase {
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

    // MARK: - 1. Disk-backed Reopen Durability Test

    func testDraftAndPhotosSurviveDatabaseReopenOnDisk() async throws {
        let dbPath = tempDir.appendingPathComponent("phokarta_test.db").path
        let clock = TestEpochClock(initialMillis: 1_700_000_000_000)

        let userId = UUID()
        let placeId = UUID()

        // Phase 1: Open DB, save draft with photos, then destroy/close DB instance
        do {
            let db = try PersistentDatabase(path: dbPath)
            let draftRepo = SQLiteVisitDraftRepository(database: db, clock: clock)

            let draft = DurableVisitDraft(
                userId: userId,
                placeId: placeId,
                overallScore: 9.2,
                publicReview: "Sensational view and atmosphere",
                privateMemory: "Celebrated anniversary here",
                visitedAtEpochDay: 19700,
                visibility: VisitVisibility.friends.rawValue,
                dimensionsExpanded: true,
                createdAtEpochMillis: clock.nowMillis(),
                updatedAtEpochMillis: clock.nowMillis()
            )
            try await draftRepo.saveDraft(placeId: placeId, draft: draft, userId: userId)

            let photo1 = DurableDraftPhoto(
                ownerUserId: userId,
                placeId: placeId,
                position: 0,
                clientMediaId: UUID(),
                localRelativePath: "user/photo1.jpg",
                contentType: "image/jpeg",
                byteSize: 1024
            )
            let photo2 = DurableDraftPhoto(
                ownerUserId: userId,
                placeId: placeId,
                position: 1,
                clientMediaId: UUID(),
                localRelativePath: "user/photo2.jpg",
                contentType: "image/jpeg",
                byteSize: 2048
            )
            try await draftRepo.upsertPhotos(placeId: placeId, photos: [photo1, photo2], userId: userId)

            await db.close()
        }

        // Phase 2: Open fresh DB connection from same file on disk and verify exact data
        do {
            let db2 = try PersistentDatabase(path: dbPath)
            let draftRepo2 = SQLiteVisitDraftRepository(database: db2, clock: clock)

            let recoveredDraft = try await draftRepo2.getDraft(placeId: placeId, userId: userId)
            XCTAssertNotNil(recoveredDraft)
            XCTAssertEqual(recoveredDraft?.userId, userId)
            XCTAssertEqual(recoveredDraft?.placeId, placeId)
            XCTAssertEqual(recoveredDraft?.overallScore, 9.2)
            XCTAssertEqual(recoveredDraft?.publicReview, "Sensational view and atmosphere")
            XCTAssertEqual(recoveredDraft?.privateMemory, "Celebrated anniversary here")
            XCTAssertEqual(recoveredDraft?.visitedAtEpochDay, 19700)
            XCTAssertEqual(recoveredDraft?.visibility, VisitVisibility.friends.rawValue)
            XCTAssertEqual(recoveredDraft?.dimensionsExpanded, true)

            let recoveredPhotos = try await draftRepo2.getPhotos(placeId: placeId, userId: userId)
            XCTAssertEqual(recoveredPhotos.count, 2)
            XCTAssertEqual(recoveredPhotos[0].position, 0)
            XCTAssertEqual(recoveredPhotos[0].localRelativePath, "user/photo1.jpg")
            XCTAssertEqual(recoveredPhotos[1].position, 1)
            XCTAssertEqual(recoveredPhotos[1].localRelativePath, "user/photo2.jpg")

            await db2.close()
        }
    }

    // MARK: - 2. 30-Day Draft Expiry Test

    func testDraftExpiresAfter30Days() async throws {
        let db = try PersistentDatabase(path: nil) // in-memory
        let clock = TestEpochClock(initialMillis: 1_700_000_000_000)
        let draftRepo = SQLiteVisitDraftRepository(database: db, clock: clock)

        let userId = UUID()
        let placeId = UUID()

        let draft = DurableVisitDraft(
            userId: userId,
            placeId: placeId,
            overallScore: 8.0,
            publicReview: "Good spot",
            privateMemory: "",
            visitedAtEpochDay: 19700,
            visibility: VisitVisibility.privateAccess.rawValue,
            dimensionsExpanded: false,
            createdAtEpochMillis: clock.nowMillis(),
            updatedAtEpochMillis: clock.nowMillis()
        )
        try await draftRepo.saveDraft(placeId: placeId, draft: draft, userId: userId)

        // Verify draft exists at T0
        let draftAtT0 = try await draftRepo.getDraft(placeId: placeId, userId: userId)
        XCTAssertNotNil(draftAtT0)

        // Advance 29 days - still valid
        clock.advanceDays(29)
        let draftAt29 = try await draftRepo.getDraft(placeId: placeId, userId: userId)
        XCTAssertNotNil(draftAt29)

        // Advance 2 more days (31 days total) - expired
        clock.advanceDays(2)
        let draftAt31 = try await draftRepo.getDraft(placeId: placeId, userId: userId)
        XCTAssertNil(draftAt31, "Draft must expire and return nil after 30 days")
    }

    // MARK: - 3. Account A/B Disk Isolation Test

    func testUserIsolationForDraftsAndMutations() async throws {
        let db = try PersistentDatabase(path: nil)
        let clock = TestEpochClock(initialMillis: 1_700_000_000_000)
        let draftRepo = SQLiteVisitDraftRepository(database: db, clock: clock)
        let mutationRepo = SQLiteOfflineMutationRepository(database: db, clock: clock)

        let userA = UUID()
        let userB = UUID()
        let placeId = UUID()

        // User A saves draft
        let draftA = DurableVisitDraft(
            userId: userA,
            placeId: placeId,
            overallScore: 9.0,
            publicReview: "User A review",
            privateMemory: "User A memory",
            visitedAtEpochDay: 19700,
            visibility: VisitVisibility.publicAccess.rawValue,
            dimensionsExpanded: false,
            createdAtEpochMillis: clock.nowMillis(),
            updatedAtEpochMillis: clock.nowMillis()
        )
        try await draftRepo.saveDraft(placeId: placeId, draft: draftA, userId: userA)

        // User A enqueues a mutation
        let mutationIdA = UUID()
        let payloadA = DurablePendingVisitPayload(
            mutationId: mutationIdA,
            placeId: placeId,
            visitedAtEpochDay: 19700,
            overallRating: 9.0,
            publicReview: "User A public",
            privateMemory: "User A private",
            visibility: VisitVisibility.publicAccess.rawValue
        )
        _ = try await mutationRepo.commitVisit(payload: payloadA, dimensions: [], photos: [], userId: userA)

        // Assert User B sees nothing
        let draftForB = try await draftRepo.getDraft(placeId: placeId, userId: userB)
        XCTAssertNil(draftForB, "User B must not see User A's draft")

        let mutationsForB = try await mutationRepo.getEligibleMutations(userId: userB, limit: 10)
        XCTAssertTrue(mutationsForB.isEmpty, "User B must not see User A's mutations")

        let pendingForB = try await mutationRepo.getPendingVisits(placeId: placeId, userId: userB)
        XCTAssertTrue(pendingForB.isEmpty, "User B must not see User A's pending visits")

        // Assert User A sees their own data
        let draftForA = try await draftRepo.getDraft(placeId: placeId, userId: userA)
        XCTAssertNotNil(draftForA)
        let mutationsForA = try await mutationRepo.getEligibleMutations(userId: userA, limit: 10)
        XCTAssertEqual(mutationsForA.count, 1)
    }

    // MARK: - 4. Commit Visit Atomic Transaction & Cascading Draft Deletion

    func testCommitVisitDeletesDraftAndEnqueuesMutationAtomically() async throws {
        let db = try PersistentDatabase(path: nil)
        let clock = TestEpochClock(initialMillis: 1_700_000_000_000)
        let draftRepo = SQLiteVisitDraftRepository(database: db, clock: clock)
        let mutationRepo = SQLiteOfflineMutationRepository(database: db, clock: clock)

        let userId = UUID()
        let placeId = UUID()

        // 1. Save draft with 1 photo
        let draft = DurableVisitDraft(
            userId: userId,
            placeId: placeId,
            overallScore: 8.5,
            publicReview: "Great place",
            privateMemory: "Secret notes",
            visitedAtEpochDay: 19700,
            visibility: VisitVisibility.friends.rawValue,
            dimensionsExpanded: true,
            createdAtEpochMillis: clock.nowMillis(),
            updatedAtEpochMillis: clock.nowMillis()
        )
        try await draftRepo.saveDraft(placeId: placeId, draft: draft, userId: userId)

        let draftPhoto = DurableDraftPhoto(
            ownerUserId: userId,
            placeId: placeId,
            position: 0,
            clientMediaId: UUID(),
            localRelativePath: "photos/1.jpg",
            contentType: "image/jpeg",
            byteSize: 1024
        )
        try await draftRepo.upsertPhotos(placeId: placeId, photos: [draftPhoto], userId: userId)

        let hasDraft = try await draftRepo.hasDraft(placeId: placeId, userId: userId)
        XCTAssertTrue(hasDraft)

        // 2. Commit visit
        let mutationId = UUID()
        let payload = DurablePendingVisitPayload(
            mutationId: mutationId,
            placeId: placeId,
            visitedAtEpochDay: 19700,
            overallRating: 8.5,
            publicReview: "Great place",
            privateMemory: "Secret notes",
            visibility: VisitVisibility.friends.rawValue
        )
        let pendingPhoto = DurablePendingPhoto(
            mutationId: mutationId,
            position: 0,
            ownerUserId: userId,
            clientMediaId: draftPhoto.clientMediaId,
            localRelativePath: "photos/1.jpg",
            contentType: "image/jpeg",
            byteSize: 1024,
            uploadState: .pendingLocal
        )
        let dim = DurablePendingDimensionScore(mutationId: mutationId, dimensionKey: "VIBE", score: 9.0)

        _ = try await mutationRepo.commitVisit(
            payload: payload,
            dimensions: [dim],
            photos: [pendingPhoto],
            userId: userId
        )

        // 3. Verify draft was deleted
        let draftAfterCommit = try await draftRepo.getDraft(placeId: placeId, userId: userId)
        XCTAssertNil(draftAfterCommit, "Draft must be deleted atomically upon commitVisit")

        let draftPhotosAfterCommit = try await draftRepo.getPhotos(placeId: placeId, userId: userId)
        XCTAssertTrue(draftPhotosAfterCommit.isEmpty, "Draft photos must be deleted atomically upon commitVisit")

        // 4. Verify mutation is queued
        let bundle = try await mutationRepo.getVisitBundle(mutationId: mutationId)
        XCTAssertNotNil(bundle)
        XCTAssertEqual(bundle?.mutation.state, .queued)
        XCTAssertEqual(bundle?.dimensions.count, 1)
        XCTAssertEqual(bundle?.photos.count, 1)
        XCTAssertEqual(bundle?.photos[0].localRelativePath, "photos/1.jpg")
    }

    // MARK: - 5. Claim, Generation CAS, and Stale Syncing Recovery

    func testClaimAndStaleSyncingRecovery() async throws {
        let db = try PersistentDatabase(path: nil)
        let clock = TestEpochClock(initialMillis: 1_700_000_000_000)
        let mutationRepo = SQLiteOfflineMutationRepository(database: db, clock: clock)

        let userId = UUID()
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

        // First claim succeeds
        let claim1 = try await mutationRepo.claim(mutationId: mutationId, now: Date(timeIntervalSince1970: 1700000000))
        XCTAssertTrue(claim1)

        // Second claim immediately fails (already SYNCING)
        let claim2 = try await mutationRepo.claim(mutationId: mutationId, now: Date(timeIntervalSince1970: 1700000000))
        XCTAssertFalse(claim2, "Concurrent claim on SYNCING mutation must fail")

        // Verify state is SYNCING
        var bundle = try await mutationRepo.getVisitBundle(mutationId: mutationId)
        XCTAssertEqual(bundle?.mutation.state, .syncing)
        XCTAssertEqual(bundle?.mutation.generation, 2)

        // Advance time past stale threshold (5 minutes) and run recoverStaleSyncing
        let staleDate = Date(timeIntervalSince1970: 1700000000 + 301)
        try await mutationRepo.recoverStaleSyncing(now: staleDate)

        // Verify state recovered to QUEUED and generation bumped
        bundle = try await mutationRepo.getVisitBundle(mutationId: mutationId)
        XCTAssertEqual(bundle?.mutation.state, .queued)
        XCTAssertEqual(bundle?.mutation.generation, 3)

        // Now claim succeeds again
        let claim3 = try await mutationRepo.claim(mutationId: mutationId, now: Date(timeIntervalSince1970: 1700000302))
        XCTAssertTrue(claim3)
    }

    // MARK: - 6. Failed Visit Recovery for Editing (M1 -> Draft -> M2)

    func testFailedVisitRecoveryForEditingRecreatesDraft() async throws {
        let db = try PersistentDatabase(path: nil)
        let clock = TestEpochClock(initialMillis: 1_700_000_000_000)
        let draftRepo = SQLiteVisitDraftRepository(database: db, clock: clock)
        let mutationRepo = SQLiteOfflineMutationRepository(database: db, clock: clock)

        let userId = UUID()
        let placeId = UUID()
        let mutationIdM1 = UUID()

        let payload = DurablePendingVisitPayload(
            mutationId: mutationIdM1,
            placeId: placeId,
            visitedAtEpochDay: 19700,
            overallRating: 7.5,
            publicReview: "Initial review",
            privateMemory: "Initial memory",
            visibility: VisitVisibility.publicAccess.rawValue
        )
        let photo = DurablePendingPhoto(
            mutationId: mutationIdM1,
            position: 0,
            ownerUserId: userId,
            clientMediaId: UUID(),
            localRelativePath: "photos/failed.jpg",
            contentType: "image/jpeg",
            byteSize: 1024,
            uploadState: .pendingLocal
        )
        _ = try await mutationRepo.commitVisit(payload: payload, dimensions: [], photos: [photo], userId: userId)

        // Mark as FAILED_PERMANENT
        try await mutationRepo.markFailure(
            mutationId: mutationIdM1,
            generation: 1,
            state: .failedPermanent,
            category: "VALIDATION",
            now: Date(timeIntervalSince1970: 1700000000)
        )

        // Recover for editing
        let result = try await mutationRepo.recoverFailedVisitForEditing(
            mutationId: mutationIdM1,
            userId: userId,
            replaceExisting: false
        )
        XCTAssertEqual(result, .success)

        // Assert M1 is gone
        let m1Bundle = try await mutationRepo.getVisitBundle(mutationId: mutationIdM1)
        XCTAssertNil(m1Bundle, "Failed mutation M1 must be removed after recovery into draft")

        // Assert draft exists with M1 data
        let recoveredDraft = try await draftRepo.getDraft(placeId: placeId, userId: userId)
        XCTAssertNotNil(recoveredDraft)
        XCTAssertEqual(recoveredDraft?.overallScore, 7.5)
        XCTAssertEqual(recoveredDraft?.publicReview, "Initial review")

        let recoveredPhotos = try await draftRepo.getPhotos(placeId: placeId, userId: userId)
        XCTAssertEqual(recoveredPhotos.count, 1)
        XCTAssertEqual(recoveredPhotos[0].localRelativePath, "photos/failed.jpg")
    }

    // MARK: - 7. DurableMediaStore Atomic Write and Read

    func testDurableMediaStoreWriteReadAndIsolation() async throws {
        let storeDir = tempDir.appendingPathComponent("media")
        let store = DurableMediaStore(customRootDirectory: storeDir)

        let userA = UUID()
        let userB = UUID()
        let placeId = UUID()
        let testJPEG = makeTestJPEG()

        let importedPhoto = try await store.importMedia(
            ownerUserId: userA,
            placeId: placeId,
            position: 0,
            data: testJPEG,
            clientMediaId: nil
        )

        XCTAssertFalse(importedPhoto.localRelativePath.isEmpty)

        // Read back file from resolved URL
        let resolvedA = store.resolveOwned(ownerUserId: userA, relativePath: importedPhoto.localRelativePath)
        XCTAssertNotNil(resolvedA)
        let fileURL = try XCTUnwrap(resolvedA)
        XCTAssertTrue(FileManager.default.fileExists(atPath: fileURL.path))

        // User B cannot resolve User A's file
        let resolvedB = store.resolveOwned(ownerUserId: userB, relativePath: importedPhoto.localRelativePath)
        XCTAssertNil(resolvedB, "User B must not be able to resolve User A's media path")
    }

    // MARK: - 8. MediaFileReconciler Orphan Sweep

    func testMediaFileReconcilerSweepsOrphansAfterGracePeriod() async throws {
        let storeDir = tempDir.appendingPathComponent("media_reconcile")
        let store = DurableMediaStore(customRootDirectory: storeDir)
        let db = try PersistentDatabase(path: nil)
        let clock = TestEpochClock(initialMillis: 1_700_000_000_000)
        let draftRepo = SQLiteVisitDraftRepository(database: db, clock: clock)
        let mutationRepo = SQLiteOfflineMutationRepository(database: db, clock: clock)
        let lock = MediaFileMutationLock()

        let reconciler = MediaFileReconciler(
            mediaStore: store,
            draftRepository: draftRepo,
            mutationRepository: mutationRepo,
            lock: lock,
            clock: clock
        )

        let userId = UUID()
        let placeId = UUID()

        // Write 2 photos
        let photo1 = try await store.importMedia(
            ownerUserId: userId,
            placeId: placeId,
            position: 0,
            data: makeTestJPEG(),
            clientMediaId: nil
        )
        let photo2 = try await store.importMedia(
            ownerUserId: userId,
            placeId: placeId,
            position: 1,
            data: makeTestJPEG(),
            clientMediaId: nil
        )

        // Only reference photo1 in a draft
        let draft = DurableVisitDraft(
            userId: userId,
            placeId: placeId,
            overallScore: 8.0,
            publicReview: "",
            privateMemory: "",
            visitedAtEpochDay: 19700,
            visibility: VisitVisibility.privateAccess.rawValue,
            dimensionsExpanded: false,
            createdAtEpochMillis: clock.nowMillis(),
            updatedAtEpochMillis: clock.nowMillis()
        )
        try await draftRepo.saveDraft(placeId: placeId, draft: draft, userId: userId)
        try await draftRepo.upsertPhotos(placeId: placeId, photos: [photo1], userId: userId)

        // Case A: Within grace period (0 minutes elapsed) - no files removed
        let immediateResult = try await reconciler.reconcile()
        XCTAssertEqual(immediateResult.removedFiles, 0)
        XCTAssertEqual(store.getAllFileUrls().count, 2)

        // Case B: Advance time past 5-minute grace period
        clock.advance(byMillis: 6 * 60 * 1000)

        // Reconcile again: photo2 should be swept, photo1 retained
        let sweepResult = try await reconciler.reconcile()
        XCTAssertEqual(sweepResult.removedFiles, 1)
        XCTAssertEqual(sweepResult.retainedFiles, 1)

        XCTAssertNotNil(store.resolveOwned(ownerUserId: userId, relativePath: photo1.localRelativePath))
        XCTAssertNil(store.resolveOwned(ownerUserId: userId, relativePath: photo2.localRelativePath))
    }
}
