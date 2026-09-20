import CoreGraphics
import Foundation
import ImageIO
import SQLite3
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
        let draftPlaceId = UUID()
        let mutationPlaceId = UUID()

        // User A saves draft
        let draftA = DurableVisitDraft(
            userId: userA,
            placeId: draftPlaceId,
            overallScore: 9.0,
            publicReview: "User A review",
            privateMemory: "User A memory",
            visitedAtEpochDay: 19700,
            visibility: VisitVisibility.publicAccess.rawValue,
            dimensionsExpanded: false,
            createdAtEpochMillis: clock.nowMillis(),
            updatedAtEpochMillis: clock.nowMillis()
        )
        try await draftRepo.saveDraft(placeId: draftPlaceId, draft: draftA, userId: userA)

        // User A enqueues a mutation
        let mutationIdA = UUID()
        let payloadA = DurablePendingVisitPayload(
            mutationId: mutationIdA,
            placeId: mutationPlaceId,
            visitedAtEpochDay: 19700,
            overallRating: 9.0,
            publicReview: "User A public",
            privateMemory: "User A private",
            visibility: VisitVisibility.publicAccess.rawValue
        )
        _ = try await mutationRepo.commitVisit(payload: payloadA, dimensions: [], photos: [], userId: userA)

        // Assert User B sees nothing
        let draftForB = try await draftRepo.getDraft(placeId: draftPlaceId, userId: userB)
        XCTAssertNil(draftForB, "User B must not see User A's draft")

        let mutationsForB = try await mutationRepo.getEligibleMutations(userId: userB, limit: 10)
        XCTAssertTrue(mutationsForB.isEmpty, "User B must not see User A's mutations")

        let pendingForB = try await mutationRepo.getPendingVisits(placeId: mutationPlaceId, userId: userB)
        XCTAssertTrue(pendingForB.isEmpty, "User B must not see User A's pending visits")

        // Assert User A sees their own data
        let draftForA = try await draftRepo.getDraft(placeId: draftPlaceId, userId: userA)
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
        let clock = TestEpochClock(initialMillis: 1_700_000_000_000)
        let store = DurableMediaStore(customRootDirectory: storeDir, clock: clock)
        let db = try PersistentDatabase(path: nil)
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

        let url1 = try XCTUnwrap(store.resolveOwned(ownerUserId: userId, relativePath: photo1.localRelativePath))
        let url2 = try XCTUnwrap(store.resolveOwned(ownerUserId: userId, relativePath: photo2.localRelativePath))
        XCTAssertTrue(FileManager.default.fileExists(atPath: url1.path), "photo1 must be retained on disk")
        XCTAssertFalse(FileManager.default.fileExists(atPath: url2.path), "photo2 must be swept from disk")
        XCTAssertEqual(store.getAllFileUrls().count, 1)
    }

    // MARK: - 9. Selected-media durability before publication

    @MainActor
    func testSelectedMediaIsDurableBeforeItAppearsInComposer() async throws {
        let db = try PersistentDatabase(path: nil)
        let repo = SQLiteVisitDraftRepository(database: db)
        let mediaStore = DurableMediaStore(customRootDirectory: tempDir.appendingPathComponent("selected-durable"))
        let userId = UUID()
        let itemId = UUID()
        let store = VisitStore(service: VisitServiceProbe(results: []))
        store.activate(accountID: userId)
        let controller = VisitComposerController(
            place: TestPlaces.detail(),
            store: store,
            mediaService: NullVisitMediaService(),
            draftRepository: repo,
            mediaStore: mediaStore
        )
        await controller.waitForInitialRestore()

        let added = await controller.mediaCoordinator.addSelectedData(makeTestJPEG(), id: itemId)
        XCTAssertTrue(added)

        let photos = try await repo.getPhotos(placeId: TestPlaces.placeID, userId: userId)
        XCTAssertEqual(photos.map(\.clientMediaId), [itemId])
        XCTAssertEqual(controller.mediaCoordinator.items.map(\.id), [itemId])
        let fileURL = try XCTUnwrap(mediaStore.resolveOwned(
            ownerUserId: userId,
            relativePath: try XCTUnwrap(photos.first?.localRelativePath)
        ))
        XCTAssertTrue(FileManager.default.fileExists(atPath: fileURL.path))
    }

    @MainActor
    func testProcessDeathRestoresSelectedMediaInExactOrder() async throws {
        let dbPath = tempDir.appendingPathComponent("composer-relaunch.sqlite3").path
        let mediaRoot = tempDir.appendingPathComponent("composer-relaunch-media")
        let userId = UUID()
        let ids = [UUID(), UUID(), UUID()]

        do {
            let db = try PersistentDatabase(path: dbPath)
            let repo = SQLiteVisitDraftRepository(database: db)
            let mediaStore = DurableMediaStore(customRootDirectory: mediaRoot)
            let store = VisitStore(service: VisitServiceProbe(results: []))
            store.activate(accountID: userId)
            let controller = VisitComposerController(
                place: TestPlaces.detail(),
                store: store,
                mediaService: NullVisitMediaService(),
                draftRepository: repo,
                mediaStore: mediaStore
            )
            await controller.waitForInitialRestore()
            for id in ids {
                let added = await controller.mediaCoordinator.addSelectedData(makeTestJPEG(), id: id)
                XCTAssertTrue(added)
            }
            await controller.mediaCoordinator.flushPersistence()
            await db.close()
        }

        do {
            let db = try PersistentDatabase(path: dbPath)
            let repo = SQLiteVisitDraftRepository(database: db)
            let mediaStore = DurableMediaStore(customRootDirectory: mediaRoot)
            let store = VisitStore(service: VisitServiceProbe(results: []))
            store.activate(accountID: userId)
            let relaunched = VisitComposerController(
                place: TestPlaces.detail(),
                store: store,
                mediaService: NullVisitMediaService(),
                draftRepository: repo,
                mediaStore: mediaStore
            )
            await relaunched.waitForInitialRestore()
            XCTAssertEqual(relaunched.mediaCoordinator.items.map(\.id), ids)
            let restoredIDs = try await repo.getPhotos(placeId: TestPlaces.placeID, userId: userId).map(\.clientMediaId)
            XCTAssertEqual(restoredIDs, ids)
            await db.close()
        }
    }

    @MainActor
    func testSelectedMediaFromAccountAIsInvisibleToAccountB() async throws {
        let db = try PersistentDatabase(path: nil)
        let repo = SQLiteVisitDraftRepository(database: db)
        let mediaStore = DurableMediaStore(customRootDirectory: tempDir.appendingPathComponent("account-isolation"))
        let userA = UUID()
        let userB = UUID()

        let storeA = VisitStore(service: VisitServiceProbe(results: []))
        storeA.activate(accountID: userA)
        let controllerA = VisitComposerController(
            place: TestPlaces.detail(), store: storeA, mediaService: NullVisitMediaService(),
            draftRepository: repo, mediaStore: mediaStore
        )
        await controllerA.waitForInitialRestore()
        let added = await controllerA.mediaCoordinator.addSelectedData(makeTestJPEG())
        XCTAssertTrue(added)

        let storeB = VisitStore(service: VisitServiceProbe(results: []))
        storeB.activate(accountID: userB)
        let controllerB = VisitComposerController(
            place: TestPlaces.detail(), store: storeB, mediaService: NullVisitMediaService(),
            draftRepository: repo, mediaStore: mediaStore
        )
        await controllerB.waitForInitialRestore()

        XCTAssertTrue(controllerB.mediaCoordinator.items.isEmpty)
        let photosForB = try await repo.getPhotos(placeId: TestPlaces.placeID, userId: userB)
        XCTAssertTrue(photosForB.isEmpty)
    }

    @MainActor
    func testDiscardDeletesOnlyTargetDraftMedia() async throws {
        let db = try PersistentDatabase(path: nil)
        let repo = SQLiteVisitDraftRepository(database: db)
        let mediaStore = DurableMediaStore(customRootDirectory: tempDir.appendingPathComponent("target-discard"))
        let userId = UUID()
        let placeA = TestPlaces.placeID
        let placeB = UUID()
        let visitStore = VisitStore(service: VisitServiceProbe(results: []))
        visitStore.activate(accountID: userId)

        let controllerA = VisitComposerController(
            place: TestPlaces.detail(id: placeA), store: visitStore, mediaService: NullVisitMediaService(),
            draftRepository: repo, mediaStore: mediaStore
        )
        let controllerB = VisitComposerController(
            place: TestPlaces.detail(id: placeB), store: visitStore, mediaService: NullVisitMediaService(),
            draftRepository: repo, mediaStore: mediaStore
        )
        await controllerA.waitForInitialRestore()
        await controllerB.waitForInitialRestore()
        let addedA = await controllerA.mediaCoordinator.addSelectedData(makeTestJPEG())
        let addedB = await controllerB.mediaCoordinator.addSelectedData(makeTestJPEG())
        XCTAssertTrue(addedA)
        XCTAssertTrue(addedB)
        let placeBPhotos = try await repo.getPhotos(placeId: placeB, userId: userId)
        let retained = try XCTUnwrap(placeBPhotos.first)

        await controllerA.discard()

        let discardedPhotos = try await repo.getPhotos(placeId: placeA, userId: userId)
        let retainedPhotos = try await repo.getPhotos(placeId: placeB, userId: userId)
        XCTAssertTrue(discardedPhotos.isEmpty)
        XCTAssertEqual(retainedPhotos.count, 1)
        let retainedURL = try XCTUnwrap(mediaStore.resolveOwned(ownerUserId: userId, relativePath: retained.localRelativePath))
        XCTAssertTrue(FileManager.default.fileExists(atPath: retainedURL.path))
    }

    @MainActor
    func testAccountPurgeDeletesOnlyPurgedAccountsMedia() async throws {
        let db = try PersistentDatabase(path: nil)
        let clock = TestEpochClock(initialMillis: 1_700_000_000_000)
        let repo = SQLiteVisitDraftRepository(database: db, clock: clock)
        let mediaStore = DurableMediaStore(customRootDirectory: tempDir.appendingPathComponent("account-purge"))
        let userA = UUID()
        let userB = UUID()

        func addPhoto(for userId: UUID) async throws -> DurableDraftPhoto {
            let draft = DurableVisitDraft(
                userId: userId, placeId: TestPlaces.placeID, overallScore: 5,
                publicReview: "", privateMemory: "", visitedAtEpochDay: 0,
                visibility: VisitVisibility.publicAccess.rawValue, dimensionsExpanded: false,
                createdAtEpochMillis: clock.nowMillis(), updatedAtEpochMillis: clock.nowMillis()
            )
            try await repo.saveDraft(placeId: TestPlaces.placeID, draft: draft, userId: userId)
            let photo = try await mediaStore.importMedia(
                ownerUserId: userId, placeId: TestPlaces.placeID, position: 0,
                data: makeTestJPEG(), clientMediaId: UUID()
            )
            try await repo.replacePhotos(placeId: TestPlaces.placeID, photos: [photo], userId: userId)
            return photo
        }

        let photoA = try await addPhoto(for: userA)
        let photoB = try await addPhoto(for: userB)
        let urlA = try XCTUnwrap(mediaStore.resolveOwned(ownerUserId: userA, relativePath: photoA.localRelativePath))
        let urlB = try XCTUnwrap(mediaStore.resolveOwned(ownerUserId: userB, relativePath: photoB.localRelativePath))
        let purger = SQLiteLocalAccountPurger(database: db, mediaStore: mediaStore, mediaLock: MediaFileMutationLock())

        try await purger.purgeLocalData(userId: userA)

        XCTAssertFalse(FileManager.default.fileExists(atPath: urlA.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: urlB.path))
        let draftA = try await repo.getDraft(placeId: TestPlaces.placeID, userId: userA)
        let draftB = try await repo.getDraft(placeId: TestPlaces.placeID, userId: userB)
        XCTAssertNil(draftA)
        XCTAssertNotNil(draftB)
    }

    func testSchemaOneUpgradePreservesV1RowsAndAddsVersionTwoColumns() async throws {
        let path = tempDir.appendingPathComponent("schema-v1.sqlite3").path
        var handle: OpaquePointer?
        XCTAssertEqual(sqlite3_open(path, &handle), SQLITE_OK)
        defer { sqlite3_close(handle) }
        let user = UUID()
        let place = UUID()
        let sql = """
        CREATE TABLE visit_drafts (
          userId TEXT NOT NULL, placeId TEXT NOT NULL, overallScore REAL NOT NULL,
          publicReview TEXT NOT NULL, privateMemory TEXT NOT NULL, visitedAtEpochDay INTEGER NOT NULL,
          visibility TEXT NOT NULL, dimensionsExpanded INTEGER NOT NULL,
          createdAtEpochMillis INTEGER NOT NULL, updatedAtEpochMillis INTEGER NOT NULL,
          PRIMARY KEY(userId,placeId));
        CREATE TABLE visit_draft_dimension_scores (
          userId TEXT NOT NULL, placeId TEXT NOT NULL, dimensionKey TEXT NOT NULL, score REAL NOT NULL,
          PRIMARY KEY(userId,placeId,dimensionKey));
        CREATE TABLE pending_mutations (
          mutationId TEXT PRIMARY KEY,userId TEXT NOT NULL,type TEXT NOT NULL,resourceKey TEXT NOT NULL,
          state TEXT NOT NULL,generation INTEGER NOT NULL,attemptCount INTEGER NOT NULL,
          createdAtEpochMillis INTEGER NOT NULL,updatedAtEpochMillis INTEGER NOT NULL,lastErrorCategory TEXT);
        INSERT INTO visit_drafts VALUES ('\(user.uuidString)','\(place.uuidString)',8,'legacy','memory',20000,'PUBLIC',0,1,1);
        INSERT INTO pending_mutations VALUES ('legacy-v1','\(user.uuidString)','PUBLISH_VISIT','legacy-v1','PENDING',1,0,1,1,NULL);
        PRAGMA user_version = 1;
        """
        XCTAssertEqual(sqlite3_exec(handle, sql, nil, nil, nil), SQLITE_OK)
        sqlite3_close(handle)
        handle = nil

        let db = try PersistentDatabase(path: path)
        let version = try await db.query("PRAGMA user_version;", mapRow: { Int(sqlite3_column_int($0, 0)) })
        XCTAssertEqual(version.first, 3)
        let draftVersion = try await db.query(
            "SELECT payloadVersion, story, titleSource FROM visit_drafts WHERE userId = ? AND placeId = ?;",
            params: [user.uuidString, place.uuidString]
        ) { (Int(sqlite3_column_int($0, 0)), String(cString: sqlite3_column_text($0, 1)), String(cString: sqlite3_column_text($0, 2))) }
        XCTAssertEqual(draftVersion.first?.0, 1)
        XCTAssertEqual(draftVersion.first?.1, "")
        XCTAssertEqual(draftVersion.first?.2, "GENERATED")
        let mutationVersion = try await db.query(
            "SELECT payloadVersion FROM pending_mutations WHERE mutationId = 'legacy-v1';",
            mapRow: { Int(sqlite3_column_int($0, 0)) }
        )
        XCTAssertEqual(mutationVersion.first, 1)
        let milestoneTables = try await db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('planned_experiences','experience_acknowledgements') ORDER BY name;",
            mapRow: { String(cString: sqlite3_column_text($0, 0)) }
        )
        XCTAssertEqual(milestoneTables, ["experience_acknowledgements", "planned_experiences"])
    }

    func testNativeV2DraftAndPendingPayloadRoundTripEverySemanticField() async throws {
        let db = try PersistentDatabase(path: nil)
        let draftRepo = SQLiteVisitDraftRepository(database: db)
        let mutationRepo = SQLiteOfflineMutationRepository(database: db)
        let user = UUID()
        let place = UUID()
        let mutation = UUID()
        let draft = DurableVisitDraft(
            userId: user, placeId: place, overallScore: 8, publicReview: "story",
            privateMemory: "owner only", visitedAtEpochDay: 20712, visibility: "FRIENDS",
            dimensionsExpanded: true, createdAtEpochMillis: 1, updatedAtEpochMillis: 1,
            dimensions: [DurableDraftDimensionScore(
                userId: user, placeId: place, dimensionKey: "SCENERY", score: 10,
                semanticStateCode: "VERY_GOOD", templateVersion: 1
            )], payloadVersion: 2, primaryExperienceCode: "GUN_BATIMI",
            overallFeelingCode: "BAYILDIM", companionCode: "PARTNER",
            timeOfDayCode: "EVENING", vibeCodes: ["SCENIC", "CALM"],
            practicalSignalCodes: ["FREE", "ARRIVE_EARLY"], title: "Golden hour",
            titleSource: "CUSTOM", story: "story", tip: "arrive early",
            originAcknowledgementId: UUID(uuidString: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        )
        try await draftRepo.saveDraft(placeId: place, draft: draft, userId: user)
        let restoredDraft = try await draftRepo.getDraft(placeId: place, userId: user)
        let restored = try XCTUnwrap(restoredDraft)
        XCTAssertEqual(restored.payloadVersion, 2)
        XCTAssertEqual(restored.primaryExperienceCode, "GUN_BATIMI")
        XCTAssertEqual(restored.overallFeelingCode, "BAYILDIM")
        XCTAssertEqual(restored.vibeCodes, ["CALM", "SCENIC"])
        XCTAssertEqual(restored.dimensions.first?.semanticStateCode, "VERY_GOOD")
        XCTAssertEqual(restored.originAcknowledgementId?.uuidString.lowercased(), "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")

        let payload = DurablePendingExperienceV2Payload(
            mutationId: mutation, placeId: place, visitedAtEpochDay: 20712,
            primaryExperienceCode: "GUN_BATIMI", rawExperienceLabel: nil,
            overallFeelingCode: "BAYILDIM", companionCode: "PARTNER",
            timeOfDayCode: "EVENING", vibeCodes: ["CALM", "SCENIC"],
            practicalSignalCodes: ["ARRIVE_EARLY", "FREE"], title: "Golden hour",
            titleSource: "CUSTOM", story: "story", tip: "arrive early",
            privateMemory: "owner only", visibility: "FRIENDS"
        )
        _ = try await mutationRepo.commitExperienceV2(
            payload: payload,
            dimensions: [DurablePendingExperienceV2Dimension(
                mutationId: mutation, dimensionKey: "SCENERY",
                semanticStateCode: "VERY_GOOD", templateVersion: 1
            )],
            photos: [], userId: user
        )
        let pendingBundle = try await mutationRepo.getExperienceV2Bundle(mutationId: mutation)
        let bundle = try XCTUnwrap(pendingBundle)
        XCTAssertEqual(bundle.mutation.payloadVersion, 2)
        XCTAssertEqual(bundle.payload, payload)
        XCTAssertEqual(bundle.dimensions.first?.semanticStateCode, "VERY_GOOD")
        let clearedDraft = try await draftRepo.getDraft(placeId: place, userId: user)
        XCTAssertNil(clearedDraft)
    }

    func testMilestoneStateIsAccountScopedAndPurgedWithItsOwner() async throws {
        let db = try PersistentDatabase(path: nil)
        let userA = UUID()
        let userB = UUID()
        let experience = UUID()
        for user in [userA, userB] {
            try await db.execute(
                "INSERT INTO planned_experiences (userId,experienceId,plannedAt,snapshotJson,pendingDesiredState) VALUES (?,?,?,?,NULL);",
                params: [user.uuidString, experience.uuidString, "2026-09-17T00:00:00Z", "{}"]
            )
            try await db.execute(
                """
                INSERT INTO experience_acknowledgements
                (userId,acknowledgementId,sourceExperienceId,placeId,acknowledgedAt,convertedExperienceId,snapshotJson,pendingUpload)
                VALUES (?,?,?,?,?,NULL,'{}',0);
                """,
                params: [user.uuidString, UUID().uuidString, experience.uuidString,
                         UUID().uuidString, "2026-09-17T00:00:00Z"]
            )
        }
        let purger = SQLiteLocalAccountPurger(database: db)
        try await purger.purgeLocalData(userId: userA)
        let remainingPlans = try await db.query("SELECT userId FROM planned_experiences;", mapRow: { String(cString: sqlite3_column_text($0, 0)) })
        let remainingAcknowledgements = try await db.query("SELECT userId FROM experience_acknowledgements;", mapRow: { String(cString: sqlite3_column_text($0, 0)) })
        XCTAssertEqual(remainingPlans, [userB.uuidString])
        XCTAssertEqual(remainingAcknowledgements, [userB.uuidString])
    }

    func testMilestoneOfflineMutationsAreDuplicateSafeDurableAndConversionAware() async throws {
        let db = try PersistentDatabase(path: nil)
        let repo = SQLiteOfflineMutationRepository(database: db)
        let user = UUID()
        let experience = try APIJSON.decoder.decode(ExperienceV2.self, from: Data(Self.milestoneExperienceJSON.utf8))

        try await repo.setPlannedExperience(experience, userId: user, desired: true)
        try await repo.setPlannedExperience(experience, userId: user, desired: true)
        let plans = try await db.query(
            "SELECT experienceId,pendingDesiredState FROM planned_experiences WHERE userId = ?;",
            params: [user.uuidString]
        ) { (String(cString: sqlite3_column_text($0, 0)), Int(sqlite3_column_int($0, 1))) }
        XCTAssertEqual(plans.count, 1)
        XCTAssertEqual(plans.first?.0.lowercased(), experience.id.uuidString.lowercased())
        XCTAssertEqual(plans.first?.1, 1)
        let planMutations = try await db.query(
            "SELECT COUNT(*) FROM pending_mutations WHERE userId = ? AND type = 'SET_PLANNED_EXPERIENCE_STATE';",
            params: [user.uuidString], mapRow: { Int(sqlite3_column_int($0, 0)) }
        )
        XCTAssertEqual(planMutations.first, 1)

        let durablePlans = try await repo.localPlannedExperiences(userId: user)
        XCTAssertEqual(durablePlans.map(\.id), [experience.id])
        XCTAssertEqual(durablePlans.first?.title, "Sunset")
        XCTAssertEqual(durablePlans.first?.placeName, "Foça")
        XCTAssertEqual(durablePlans.first?.pendingDesiredState, true)

        let first = try await repo.acknowledgeExperience(experience, userId: user)
        let duplicate = try await repo.acknowledgeExperience(experience, userId: user)
        XCTAssertEqual(first, duplicate)
        let acknowledgements = try await db.query(
            "SELECT acknowledgementId,placeId,pendingUpload FROM experience_acknowledgements WHERE userId = ?;",
            params: [user.uuidString]
        ) {
            (String(cString: sqlite3_column_text($0, 0)),
             String(cString: sqlite3_column_text($0, 1)), Int(sqlite3_column_int($0, 2)))
        }
        XCTAssertEqual(acknowledgements.count, 1)
        XCTAssertEqual(acknowledgements.first?.0.lowercased(), first.uuidString.lowercased())
        XCTAssertEqual(acknowledgements.first?.1.lowercased(), experience.place.id.uuidString.lowercased())
        XCTAssertEqual(acknowledgements.first?.2, 1)
        let acknowledgementMutations = try await db.query(
            "SELECT mutationId FROM pending_mutations WHERE userId = ? AND type = 'ACKNOWLEDGE_EXPERIENCE';",
            params: [user.uuidString], mapRow: { String(cString: sqlite3_column_text($0, 0)) }
        )
        XCTAssertEqual(acknowledgementMutations, [first.uuidString])

        let durableAcknowledgements = try await repo.localAcknowledgements(userId: user)
        XCTAssertEqual(durableAcknowledgements.map(\.id), [first])
        XCTAssertEqual(durableAcknowledgements.first?.place.name, "Foça")
        XCTAssertEqual(durableAcknowledgements.first?.primaryExperienceCode, .gunBatimi)

        let converted = UUID()
        try await repo.markAcknowledgementConverted(id: first, experienceId: converted, userId: user)
        let remainingUnconverted = try await repo.localAcknowledgements(userId: user)
        XCTAssertTrue(remainingUnconverted.isEmpty)
        let convertedIds = try await db.query(
            "SELECT convertedExperienceId FROM experience_acknowledgements WHERE userId = ?;",
            params: [user.uuidString], mapRow: { String(cString: sqlite3_column_text($0, 0)) }
        )
        XCTAssertEqual(convertedIds, [converted.uuidString])

        try await repo.removePlannedExperience(experienceId: experience.id, userId: user)
        let remainingPlans = try await repo.localPlannedExperiences(userId: user)
        XCTAssertTrue(remainingPlans.isEmpty)
        let removalIntent = try await db.query(
            "SELECT payloadVersion FROM pending_mutations WHERE userId = ? AND type = 'SET_PLANNED_EXPERIENCE_STATE';",
            params: [user.uuidString], mapRow: { Int(sqlite3_column_int($0, 0)) }
        )
        XCTAssertEqual(removalIntent, [0])
    }

    private static let milestoneExperienceJSON = """
    {
      "id":"30000000-0000-0000-0000-000000000401",
      "classification":"NATIVE_V2",
      "author":{"id":"11111111-1111-1111-1111-111111111401","username":"author","displayName":"Author","avatarUrl":null,"relationship":null},
      "place":{"id":"20000000-0000-0000-0000-000000000401","name":"Foça","category":"BEACH","city":"İzmir","region":"Aegean","country":"Türkiye","coverImage":"","distanceMeters":null},
      "experiencedAt":"2026-09-17","title":"Sunset","titleSource":"CUSTOM","titlePersisted":true,
      "story":"Story","tip":null,
      "feeling":{"code":"GUZELDI","source":"EXPLICIT","compatibilityNumericRating":8.0},
      "primaryExperience":{"code":"GUN_BATIMI","canonical":true,"family":"SCENERY_AND_MOMENT","rawLabel":null},
      "companion":"PARTNER","timeOfDay":"EVENING","vibes":[],"practicalSignals":[],
      "dimensions":[],"media":[],"visibility":"PUBLIC","taxonomyVersion":1,
      "plannedByViewer":false,"acknowledgedByViewer":false,"acknowledgementCount":0
    }
    """
}
