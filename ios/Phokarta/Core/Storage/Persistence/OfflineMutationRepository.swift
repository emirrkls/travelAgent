import Foundation
import SQLite3

protocol OfflineMutationRepository: Sendable {
    func commitVisit(
        payload: DurablePendingVisitPayload,
        dimensions: [DurablePendingDimensionScore],
        photos: [DurablePendingPhoto],
        userId: UUID
    ) async throws -> UUID

    func getEligibleMutations(userId: UUID, limit: Int) async throws -> [DurablePendingMutation]
    func getVisitBundle(mutationId: UUID) async throws -> PendingVisitMutationBundle?
    func claim(mutationId: UUID, now: Date) async throws -> Bool
    func recoverStaleSyncing(now: Date) async throws
    func markFailure(mutationId: UUID, generation: Int64, state: MutationState, category: String, now: Date) async throws
    func updatePhotoRemoteState(mutationId: UUID, position: Int, remoteMediaId: UUID, uploadState: MediaUploadState) async throws
    func markPhotoFailure(mutationId: UUID, position: Int, category: String) async throws
    func deleteIfGeneration(mutationId: UUID, generation: Int64) async throws -> Bool
    func retry(mutationId: UUID, userId: UUID) async throws
    func recoverFailedVisitForEditing(mutationId: UUID, userId: UUID, replaceExisting: Bool) async throws -> RecoverFailedVisitResult
    func removeFailedVisit(mutationId: UUID, userId: UUID) async throws -> RemoveFailedVisitResult
    func getPendingVisits(userId: UUID) async throws -> [PendingVisit]
    func getPendingVisits(placeId: UUID, userId: UUID) async throws -> [PendingVisit]
    func getAllVisitPhotos() async throws -> [DurablePendingPhoto]
    func getVisitPhotos(mutationId: UUID) async throws -> [DurablePendingPhoto]
}

final class SQLiteOfflineMutationRepository: OfflineMutationRepository, Sendable {
    private let database: PersistentDatabase
    private let clock: any EpochClock

    init(database: PersistentDatabase, clock: any EpochClock = SystemEpochClock()) {
        self.database = database
        self.clock = clock
    }

    public func commitVisit(
        payload: DurablePendingVisitPayload,
        dimensions: [DurablePendingDimensionScore],
        photos: [DurablePendingPhoto],
        userId: UUID
    ) async throws -> UUID {
        let now = clock.nowMillis()
        let mutationId = payload.mutationId

        try await database.withTransaction { db in
            // 1. Insert into pending_mutations
            try db.execute(
                """
                INSERT INTO pending_mutations (
                    mutationId, userId, type, resourceKey, state,
                    generation, attemptCount, createdAtEpochMillis,
                    updatedAtEpochMillis, lastErrorCategory
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                """,
                params: [
                    mutationId.uuidString,
                    userId.uuidString,
                    MutationType.publishVisit.rawValue,
                    mutationId.uuidString,
                    MutationState.pending.rawValue,
                    1,
                    0,
                    now,
                    now,
                    nil
                ]
            )

            // 2. Insert into pending_visit_payloads
            try db.execute(
                """
                INSERT INTO pending_visit_payloads (
                    mutationId, placeId, visitedAtEpochDay, overallRating,
                    publicReview, privateMemory, visibility
                ) VALUES (?, ?, ?, ?, ?, ?, ?);
                """,
                params: [
                    mutationId.uuidString,
                    payload.placeId.uuidString,
                    payload.visitedAtEpochDay,
                    payload.overallRating,
                    payload.publicReview,
                    payload.privateMemory,
                    payload.visibility
                ]
            )

            // 3. Insert dimension scores
            for dim in dimensions {
                try db.execute(
                    """
                    INSERT INTO pending_visit_dimension_scores (
                        mutationId, dimensionKey, score
                    ) VALUES (?, ?, ?);
                    """,
                    params: [mutationId.uuidString, dim.dimensionKey, dim.score]
                )
            }

            // 4. Insert photos
            for photo in photos {
                try db.execute(
                    """
                    INSERT INTO pending_visit_photos (
                        mutationId, position, ownerUserId, clientMediaId,
                        localRelativePath, contentType, byteSize, width,
                        height, remoteMediaId, uploadState, failureCategory
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                    """,
                    params: [
                        mutationId.uuidString,
                        photo.position,
                        userId.uuidString,
                        photo.clientMediaId.uuidString,
                        photo.localRelativePath,
                        photo.contentType,
                        photo.byteSize,
                        photo.width,
                        photo.height,
                        photo.remoteMediaId?.uuidString,
                        photo.uploadState.rawValue,
                        photo.failureCategory
                    ]
                )
            }

            // 5. Delete draft atomically
            try db.execute(
                "DELETE FROM visit_drafts WHERE userId = ? AND placeId = ?;",
                params: [userId.uuidString, payload.placeId.uuidString]
            )
        }

        return mutationId
    }

    public func getEligibleMutations(userId: UUID, limit: Int) async throws -> [DurablePendingMutation] {
        try await database.query(
            """
            SELECT mutationId, userId, type, resourceKey, state,
                   generation, attemptCount, createdAtEpochMillis,
                   updatedAtEpochMillis, lastErrorCategory
            FROM pending_mutations
            WHERE userId = ? AND state IN ('PENDING', 'FAILED_RETRYABLE')
            ORDER BY createdAtEpochMillis ASC
            LIMIT ?;
            """,
            params: [userId.uuidString, limit]
        ) { stmt -> DurablePendingMutation in
            DurablePendingMutation(
                mutationId: UUID(uuidString: String(cString: sqlite3_column_text(stmt, 0))) ?? UUID(),
                userId: UUID(uuidString: String(cString: sqlite3_column_text(stmt, 1))) ?? userId,
                type: MutationType(rawValue: String(cString: sqlite3_column_text(stmt, 2))) ?? .publishVisit,
                resourceKey: String(cString: sqlite3_column_text(stmt, 3)),
                state: MutationState(rawValue: String(cString: sqlite3_column_text(stmt, 4))) ?? .pending,
                generation: sqlite3_column_int64(stmt, 5),
                attemptCount: Int(sqlite3_column_int(stmt, 6)),
                createdAtEpochMillis: sqlite3_column_int64(stmt, 7),
                updatedAtEpochMillis: sqlite3_column_int64(stmt, 8),
                lastErrorCategory: sqlite3_column_text(stmt, 9).flatMap { String(cString: $0) }
            )
        }
    }

    public func getVisitBundle(mutationId: UUID) async throws -> PendingVisitMutationBundle? {
        let mutations = try await database.query(
            """
            SELECT mutationId, userId, type, resourceKey, state,
                   generation, attemptCount, createdAtEpochMillis,
                   updatedAtEpochMillis, lastErrorCategory
            FROM pending_mutations
            WHERE mutationId = ?;
            """,
            params: [mutationId.uuidString]
        ) { stmt -> DurablePendingMutation in
            DurablePendingMutation(
                mutationId: UUID(uuidString: String(cString: sqlite3_column_text(stmt, 0))) ?? mutationId,
                userId: UUID(uuidString: String(cString: sqlite3_column_text(stmt, 1))) ?? UUID(),
                type: MutationType(rawValue: String(cString: sqlite3_column_text(stmt, 2))) ?? .publishVisit,
                resourceKey: String(cString: sqlite3_column_text(stmt, 3)),
                state: MutationState(rawValue: String(cString: sqlite3_column_text(stmt, 4))) ?? .pending,
                generation: sqlite3_column_int64(stmt, 5),
                attemptCount: Int(sqlite3_column_int(stmt, 6)),
                createdAtEpochMillis: sqlite3_column_int64(stmt, 7),
                updatedAtEpochMillis: sqlite3_column_int64(stmt, 8),
                lastErrorCategory: sqlite3_column_text(stmt, 9).flatMap { String(cString: $0) }
            )
        }
        guard let mutation = mutations.first else { return nil }

        let payloads = try await database.query(
            """
            SELECT mutationId, placeId, visitedAtEpochDay, overallRating,
                   publicReview, privateMemory, visibility
            FROM pending_visit_payloads
            WHERE mutationId = ?;
            """,
            params: [mutationId.uuidString]
        ) { stmt -> DurablePendingVisitPayload in
            DurablePendingVisitPayload(
                mutationId: UUID(uuidString: String(cString: sqlite3_column_text(stmt, 0))) ?? mutationId,
                placeId: UUID(uuidString: String(cString: sqlite3_column_text(stmt, 1))) ?? UUID(),
                visitedAtEpochDay: sqlite3_column_int64(stmt, 2),
                overallRating: sqlite3_column_double(stmt, 3),
                publicReview: String(cString: sqlite3_column_text(stmt, 4)),
                privateMemory: String(cString: sqlite3_column_text(stmt, 5)),
                visibility: String(cString: sqlite3_column_text(stmt, 6))
            )
        }
        guard let payload = payloads.first else { return nil }

        let dimensions = try await database.query(
            """
            SELECT mutationId, dimensionKey, score
            FROM pending_visit_dimension_scores
            WHERE mutationId = ?;
            """,
            params: [mutationId.uuidString]
        ) { stmt -> DurablePendingDimensionScore in
            DurablePendingDimensionScore(
                mutationId: UUID(uuidString: String(cString: sqlite3_column_text(stmt, 0))) ?? mutationId,
                dimensionKey: String(cString: sqlite3_column_text(stmt, 1)),
                score: sqlite3_column_double(stmt, 2)
            )
        }

        let photos = try await getVisitPhotos(mutationId: mutationId)

        return PendingVisitMutationBundle(
            mutation: mutation,
            payload: payload,
            dimensions: dimensions,
            photos: photos
        )
    }

    public func claim(mutationId: UUID, now: Date) async throws -> Bool {
        let nowMillis = Int64(now.timeIntervalSince1970 * 1000)
        let changes = try await database.execute(
            """
            UPDATE pending_mutations
            SET state = 'SYNCING', generation = generation + 1, updatedAtEpochMillis = ?
            WHERE mutationId = ? AND state IN ('PENDING', 'FAILED_RETRYABLE');
            """,
            params: [nowMillis, mutationId.uuidString]
        )
        return changes == 1
    }

    public func recoverStaleSyncing(now: Date) async throws {
        let nowMillis = Int64(now.timeIntervalSince1970 * 1000)
        let staleCutoff = nowMillis - (5 * 60 * 1000)
        try await database.execute(
            """
            UPDATE pending_mutations
            SET state = 'PENDING', generation = generation + 1, updatedAtEpochMillis = ?
            WHERE state = 'SYNCING' AND updatedAtEpochMillis <= ?;
            """,
            params: [nowMillis, staleCutoff]
        )
    }

    public func markFailure(
        mutationId: UUID,
        generation: Int64,
        state: MutationState,
        category: String,
        now: Date
    ) async throws {
        let nowMillis = Int64(now.timeIntervalSince1970 * 1000)
        try await database.execute(
            """
            UPDATE pending_mutations
            SET state = ?, lastErrorCategory = ?, attemptCount = attemptCount + 1, updatedAtEpochMillis = ?
            WHERE mutationId = ? AND generation = ?;
            """,
            params: [state.rawValue, category, nowMillis, mutationId.uuidString, generation]
        )
    }

    public func updatePhotoRemoteState(
        mutationId: UUID,
        position: Int,
        remoteMediaId: UUID,
        uploadState: MediaUploadState
    ) async throws {
        try await database.execute(
            """
            UPDATE pending_visit_photos
            SET remoteMediaId = ?, uploadState = ?
            WHERE mutationId = ? AND position = ?;
            """,
            params: [remoteMediaId.uuidString, uploadState.rawValue, mutationId.uuidString, position]
        )
    }

    public func markPhotoFailure(mutationId: UUID, position: Int, category: String) async throws {
        try await database.execute(
            """
            UPDATE pending_visit_photos
            SET failureCategory = ?
            WHERE mutationId = ? AND position = ?;
            """,
            params: [category, mutationId.uuidString, position]
        )
    }

    public func deleteIfGeneration(mutationId: UUID, generation: Int64) async throws -> Bool {
        let changes = try await database.execute(
            "DELETE FROM pending_mutations WHERE mutationId = ? AND generation = ?;",
            params: [mutationId.uuidString, generation]
        )
        return changes == 1
    }

    public func retry(mutationId: UUID, userId: UUID) async throws {
        let now = clock.nowMillis()
        try await database.execute(
            """
            UPDATE pending_mutations
            SET state = 'PENDING', generation = generation + 1, lastErrorCategory = NULL, updatedAtEpochMillis = ?
            WHERE mutationId = ? AND userId = ?;
            """,
            params: [now, mutationId.uuidString, userId.uuidString]
        )
    }

    public func recoverFailedVisitForEditing(
        mutationId: UUID,
        userId: UUID,
        replaceExisting: Bool
    ) async throws -> RecoverFailedVisitResult {
        guard let bundle = try await getVisitBundle(mutationId: mutationId) else {
            return .notFound
        }
        if bundle.mutation.userId != userId {
            return .notOwner
        }
        if bundle.mutation.state != .failedPermanent {
            return .invalidState
        }

        let placeId = bundle.payload.placeId

        // Check if an existing draft exists with meaningful content
        if !replaceExisting {
            let existingDrafts = try await database.query(
                "SELECT publicReview, privateMemory, overallScore FROM visit_drafts WHERE userId = ? AND placeId = ?;",
                params: [userId.uuidString, placeId.uuidString]
            ) { stmt -> (String, String, Double) in
                (
                    String(cString: sqlite3_column_text(stmt, 0)),
                    String(cString: sqlite3_column_text(stmt, 1)),
                    sqlite3_column_double(stmt, 2)
                )
            }
            if let existing = existingDrafts.first {
                let hasContent = !existing.0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    || !existing.1.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    || existing.2 > 0
                if hasContent {
                    return .existingDraftConflict
                }
            }
        }

        let now = clock.nowMillis()

        return try await database.withTransaction { db in
            // Upsert draft
            try db.execute(
                """
                INSERT INTO visit_drafts (
                    userId, placeId, overallScore, publicReview, privateMemory,
                    visitedAtEpochDay, visibility, dimensionsExpanded,
                    createdAtEpochMillis, updatedAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(userId, placeId) DO UPDATE SET
                    overallScore = excluded.overallScore,
                    publicReview = excluded.publicReview,
                    privateMemory = excluded.privateMemory,
                    visitedAtEpochDay = excluded.visitedAtEpochDay,
                    visibility = excluded.visibility,
                    dimensionsExpanded = excluded.dimensionsExpanded,
                    updatedAtEpochMillis = excluded.updatedAtEpochMillis;
                """,
                params: [
                    userId.uuidString,
                    placeId.uuidString,
                    bundle.payload.overallRating,
                    bundle.payload.publicReview,
                    bundle.payload.privateMemory,
                    bundle.payload.visitedAtEpochDay,
                    bundle.payload.visibility,
                    !bundle.dimensions.isEmpty,
                    now,
                    now
                ]
            )

            // Replace draft dimension scores
            try db.execute(
                "DELETE FROM visit_draft_dimension_scores WHERE userId = ? AND placeId = ?;",
                params: [userId.uuidString, placeId.uuidString]
            )
            for dim in bundle.dimensions {
                try db.execute(
                    """
                    INSERT INTO visit_draft_dimension_scores (userId, placeId, dimensionKey, score)
                    VALUES (?, ?, ?, ?);
                    """,
                    params: [userId.uuidString, placeId.uuidString, dim.dimensionKey, dim.score]
                )
            }

            // Replace draft photos
            try db.execute(
                "DELETE FROM visit_draft_photos WHERE ownerUserId = ? AND placeId = ?;",
                params: [userId.uuidString, placeId.uuidString]
            )
            for photo in bundle.photos {
                try db.execute(
                    """
                    INSERT INTO visit_draft_photos (
                        ownerUserId, placeId, position, clientMediaId, localRelativePath,
                        contentType, byteSize, width, height, remoteMediaId,
                        uploadState, failureCategory
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                    """,
                    params: [
                        userId.uuidString,
                        placeId.uuidString,
                        photo.position,
                        photo.clientMediaId.uuidString,
                        photo.localRelativePath ?? "",
                        photo.contentType ?? "image/jpeg",
                        photo.byteSize ?? 0,
                        photo.width,
                        photo.height,
                        photo.remoteMediaId?.uuidString,
                        photo.uploadState.rawValue,
                        photo.failureCategory
                    ]
                )
            }

            // Delete failed mutation
            let deleted = try db.execute(
                "DELETE FROM pending_mutations WHERE mutationId = ? AND userId = ? AND state = 'FAILED_PERMANENT';",
                params: [mutationId.uuidString, userId.uuidString]
            )
            if deleted != 1 {
                throw PersistenceError.executionFailed("Failed mutation state changed during recovery")
            }
            return .success
        }
    }

    public func removeFailedVisit(mutationId: UUID, userId: UUID) async throws -> RemoveFailedVisitResult {
        let rows = try await database.query(
            "SELECT userId, state FROM pending_mutations WHERE mutationId = ?;",
            params: [mutationId.uuidString]
        ) { stmt -> (UUID, MutationState) in
            (
                UUID(uuidString: String(cString: sqlite3_column_text(stmt, 0))) ?? UUID(),
                MutationState(rawValue: String(cString: sqlite3_column_text(stmt, 1))) ?? .pending
            )
        }
        guard let (owner, state) = rows.first else { return .notFound }
        if owner != userId { return .notOwner }
        if state != .failedPermanent { return .invalidState }

        let changes = try await database.execute(
            "DELETE FROM pending_mutations WHERE mutationId = ? AND userId = ? AND state = 'FAILED_PERMANENT';",
            params: [mutationId.uuidString, userId.uuidString]
        )
        return changes == 1 ? .success : .invalidState
    }

    public func getPendingVisits(userId: UUID) async throws -> [PendingVisit] {
        let mutations = try await database.query(
            """
            SELECT m.mutationId, p.placeId, m.userId, p.visitedAtEpochDay,
                   p.overallRating, p.publicReview, p.privateMemory, p.visibility,
                   m.state, m.lastErrorCategory
            FROM pending_mutations m
            INNER JOIN pending_visit_payloads p ON m.mutationId = p.mutationId
            WHERE m.userId = ? AND m.type = 'PUBLISH_VISIT'
            ORDER BY m.createdAtEpochMillis DESC;
            """,
            params: [userId.uuidString]
        ) { stmt -> (UUID, UUID, UUID, Int64, Double, String, String, String, MutationState, String?) in
            (
                UUID(uuidString: String(cString: sqlite3_column_text(stmt, 0))) ?? UUID(),
                UUID(uuidString: String(cString: sqlite3_column_text(stmt, 1))) ?? UUID(),
                UUID(uuidString: String(cString: sqlite3_column_text(stmt, 2))) ?? userId,
                sqlite3_column_int64(stmt, 3),
                sqlite3_column_double(stmt, 4),
                String(cString: sqlite3_column_text(stmt, 5)),
                String(cString: sqlite3_column_text(stmt, 6)),
                String(cString: sqlite3_column_text(stmt, 7)),
                MutationState(rawValue: String(cString: sqlite3_column_text(stmt, 8))) ?? .pending,
                sqlite3_column_text(stmt, 9).flatMap { String(cString: $0) }
            )
        }

        var results: [PendingVisit] = []
        for row in mutations {
            let mutationId = row.0
            let placeId = row.1
            let uId = row.2
            let visitedEpochDay = row.3
            let rating = row.4
            let review = row.5
            let note = row.6
            let visibility = VisitVisibility(rawValue: row.7) ?? .publicAccess
            let state = row.8
            let errorCategory = row.9

            let dims = try await database.query(
                "SELECT dimensionKey, score FROM pending_visit_dimension_scores WHERE mutationId = ?;",
                params: [mutationId.uuidString]
            ) { stmt -> (String, Double) in
                (String(cString: sqlite3_column_text(stmt, 0)), sqlite3_column_double(stmt, 1))
            }
            var dimDict: [String: Double] = [:]
            for d in dims { dimDict[d.0] = d.1 }

            let photos = try await getVisitPhotos(mutationId: mutationId)
            let photoPaths = photos.compactMap { $0.localRelativePath }

            let visitedDate = Date(timeIntervalSince1970: Double(visitedEpochDay) * 86400)

            results.append(
                PendingVisit(
                    mutationId: mutationId,
                    placeId: placeId,
                    userId: uId,
                    visitedAt: visitedDate,
                    overallRating: rating,
                    ratingDimensions: dimDict,
                    review: review,
                    personalNote: note,
                    photos: photoPaths,
                    visibility: visibility,
                    state: state,
                    lastErrorCategory: errorCategory
                )
            )
        }
        return results
    }

    public func getPendingVisits(placeId: UUID, userId: UUID) async throws -> [PendingVisit] {
        let all = try await getPendingVisits(userId: userId)
        return all.filter { $0.placeId == placeId }
    }

    public func getVisitPhotos(mutationId: UUID) async throws -> [DurablePendingPhoto] {
        try await database.query(
            """
            SELECT mutationId, position, ownerUserId, clientMediaId,
                   localRelativePath, contentType, byteSize, width,
                   height, remoteMediaId, uploadState, failureCategory
            FROM pending_visit_photos
            WHERE mutationId = ?
            ORDER BY position ASC;
            """,
            params: [mutationId.uuidString]
        ) { stmt -> DurablePendingPhoto in
            let remoteStr = sqlite3_column_text(stmt, 9).flatMap { String(cString: $0) }
            let remoteUUID = remoteStr.flatMap { UUID(uuidString: $0) }
            let failStr = sqlite3_column_text(stmt, 11).flatMap { String(cString: $0) }
            let localPath = sqlite3_column_text(stmt, 4).flatMap { String(cString: $0) }
            let contentType = sqlite3_column_text(stmt, 5).flatMap { String(cString: $0) }
            let byteSize: Int64? = sqlite3_column_type(stmt, 6) == SQLITE_NULL ? nil : sqlite3_column_int64(stmt, 6)
            let width: Int? = sqlite3_column_type(stmt, 7) == SQLITE_NULL ? nil : Int(sqlite3_column_int(stmt, 7))
            let height: Int? = sqlite3_column_type(stmt, 8) == SQLITE_NULL ? nil : Int(sqlite3_column_int(stmt, 8))

            return DurablePendingPhoto(
                mutationId: UUID(uuidString: String(cString: sqlite3_column_text(stmt, 0))) ?? mutationId,
                position: Int(sqlite3_column_int(stmt, 1)),
                ownerUserId: UUID(uuidString: String(cString: sqlite3_column_text(stmt, 2))) ?? UUID(),
                clientMediaId: UUID(uuidString: String(cString: sqlite3_column_text(stmt, 3))) ?? UUID(),
                localRelativePath: localPath,
                contentType: contentType,
                byteSize: byteSize,
                width: width,
                height: height,
                remoteMediaId: remoteUUID,
                uploadState: MediaUploadState(rawValue: String(cString: sqlite3_column_text(stmt, 10))) ?? .localOnly,
                failureCategory: failStr
            )
        }
    }

    public func getAllVisitPhotos() async throws -> [DurablePendingPhoto] {
        try await database.query(
            """
            SELECT mutationId, position, ownerUserId, clientMediaId,
                   localRelativePath, contentType, byteSize, width,
                   height, remoteMediaId, uploadState, failureCategory
            FROM pending_visit_photos;
            """
        ) { stmt -> DurablePendingPhoto in
            let remoteStr = sqlite3_column_text(stmt, 9).flatMap { String(cString: $0) }
            let remoteUUID = remoteStr.flatMap { UUID(uuidString: $0) }
            let failStr = sqlite3_column_text(stmt, 11).flatMap { String(cString: $0) }
            let localPath = sqlite3_column_text(stmt, 4).flatMap { String(cString: $0) }
            let contentType = sqlite3_column_text(stmt, 5).flatMap { String(cString: $0) }
            let byteSize: Int64? = sqlite3_column_type(stmt, 6) == SQLITE_NULL ? nil : sqlite3_column_int64(stmt, 6)
            let width: Int? = sqlite3_column_type(stmt, 7) == SQLITE_NULL ? nil : Int(sqlite3_column_int(stmt, 7))
            let height: Int? = sqlite3_column_type(stmt, 8) == SQLITE_NULL ? nil : Int(sqlite3_column_int(stmt, 8))

            return DurablePendingPhoto(
                mutationId: UUID(uuidString: String(cString: sqlite3_column_text(stmt, 0))) ?? UUID(),
                position: Int(sqlite3_column_int(stmt, 1)),
                ownerUserId: UUID(uuidString: String(cString: sqlite3_column_text(stmt, 2))) ?? UUID(),
                clientMediaId: UUID(uuidString: String(cString: sqlite3_column_text(stmt, 3))) ?? UUID(),
                localRelativePath: localPath,
                contentType: contentType,
                byteSize: byteSize,
                width: width,
                height: height,
                remoteMediaId: remoteUUID,
                uploadState: MediaUploadState(rawValue: String(cString: sqlite3_column_text(stmt, 10))) ?? .localOnly,
                failureCategory: failStr
            )
        }
    }
}
