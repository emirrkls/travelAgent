import Foundation
import SQLite3

protocol VisitDraftRepository: Sendable {
    func getDraft(placeId: UUID, userId: UUID) async throws -> DurableVisitDraft?
    func hasDraft(placeId: UUID, userId: UUID) async throws -> Bool
    func saveDraft(placeId: UUID, draft: DurableVisitDraft, userId: UUID) async throws
    func deleteDraft(placeId: UUID, userId: UUID) async throws
    func deleteExpiredDrafts() async throws
    func upsertPhotos(placeId: UUID, photos: [DurableDraftPhoto], userId: UUID) async throws
    func removePhoto(placeId: UUID, relativePath: String, userId: UUID) async throws
    func replacePhotos(placeId: UUID, photos: [DurableDraftPhoto], userId: UUID) async throws
    func updatePhotoRemoteState(
        placeId: UUID,
        clientMediaId: UUID,
        remoteMediaId: UUID,
        uploadState: MediaUploadState,
        userId: UUID
    ) async throws
    func getPhotos(placeId: UUID, userId: UUID) async throws -> [DurableDraftPhoto]
    func getAllPhotos() async throws -> [DurableDraftPhoto]
}

final class SQLiteVisitDraftRepository: VisitDraftRepository, Sendable {
    private let database: PersistentDatabase
    private let clock: any EpochClock

    init(database: PersistentDatabase, clock: any EpochClock = SystemEpochClock()) {
        self.database = database
        self.clock = clock
    }

    public func getDraft(placeId: UUID, userId: UUID) async throws -> DurableVisitDraft? {
        let drafts = try await database.query(
            """
            SELECT userId, placeId, overallScore, publicReview, privateMemory,
                   visitedAtEpochDay, visibility, dimensionsExpanded,
                   createdAtEpochMillis, updatedAtEpochMillis, payloadVersion,
                   primaryExperienceCode, rawExperienceLabel, overallFeelingCode,
                   companionCode, timeOfDayCode, vibeCodes, practicalSignalCodes,
                   title, titleSource, story, tip
            FROM visit_drafts
            WHERE userId = ? AND placeId = ?;
            """,
            params: [userId.uuidString, placeId.uuidString]
        ) { stmt -> DurableVisitDraft in
            DurableVisitDraft(
                userId: UUID(uuidString: String(cString: sqlite3_column_text(stmt, 0))) ?? userId,
                placeId: UUID(uuidString: String(cString: sqlite3_column_text(stmt, 1))) ?? placeId,
                overallScore: sqlite3_column_double(stmt, 2),
                publicReview: String(cString: sqlite3_column_text(stmt, 3)),
                privateMemory: String(cString: sqlite3_column_text(stmt, 4)),
                visitedAtEpochDay: sqlite3_column_int64(stmt, 5),
                visibility: String(cString: sqlite3_column_text(stmt, 6)),
                dimensionsExpanded: sqlite3_column_int(stmt, 7) != 0,
                createdAtEpochMillis: sqlite3_column_int64(stmt, 8),
                updatedAtEpochMillis: sqlite3_column_int64(stmt, 9),
                payloadVersion: Int(sqlite3_column_int(stmt, 10)),
                primaryExperienceCode: sqlite3_column_text(stmt, 11).map { String(cString: $0) },
                rawExperienceLabel: sqlite3_column_text(stmt, 12).map { String(cString: $0) },
                overallFeelingCode: sqlite3_column_text(stmt, 13).map { String(cString: $0) },
                companionCode: sqlite3_column_text(stmt, 14).map { String(cString: $0) },
                timeOfDayCode: sqlite3_column_text(stmt, 15).map { String(cString: $0) },
                vibeCodes: String(cString: sqlite3_column_text(stmt, 16)).split(separator: ",").map(String.init),
                practicalSignalCodes: String(cString: sqlite3_column_text(stmt, 17)).split(separator: ",").map(String.init),
                title: sqlite3_column_text(stmt, 18).map { String(cString: $0) },
                titleSource: String(cString: sqlite3_column_text(stmt, 19)),
                story: String(cString: sqlite3_column_text(stmt, 20)),
                tip: String(cString: sqlite3_column_text(stmt, 21))
            )
        }

        guard var draft = drafts.first else { return nil }

        // Check expiry
        let cutoff = clock.nowMillis() - VisitDraftRepositoryConstants.expiryMs
        if draft.updatedAtEpochMillis < cutoff {
            try await deleteDraft(placeId: placeId, userId: userId)
            return nil
        }

        // Fetch dimension scores
        let dimensions = try await database.query(
            """
            SELECT userId, placeId, dimensionKey, score, semanticStateCode, templateVersion
            FROM visit_draft_dimension_scores
            WHERE userId = ? AND placeId = ?;
            """,
            params: [userId.uuidString, placeId.uuidString]
        ) { stmt -> DurableDraftDimensionScore in
            DurableDraftDimensionScore(
                userId: UUID(uuidString: String(cString: sqlite3_column_text(stmt, 0))) ?? userId,
                placeId: UUID(uuidString: String(cString: sqlite3_column_text(stmt, 1))) ?? placeId,
                dimensionKey: String(cString: sqlite3_column_text(stmt, 2)),
                score: sqlite3_column_double(stmt, 3),
                semanticStateCode: sqlite3_column_text(stmt, 4).map { String(cString: $0) },
                templateVersion: sqlite3_column_type(stmt, 5) == SQLITE_NULL
                    ? nil : Int(sqlite3_column_int(stmt, 5))
            )
        }
        draft.dimensions = dimensions

        // Fetch photos
        let photos = try await getPhotos(placeId: placeId, userId: userId)
        draft.photos = photos

        return draft
    }

    public func hasDraft(placeId: UUID, userId: UUID) async throws -> Bool {
        try await getDraft(placeId: placeId, userId: userId) != nil
    }

    public func saveDraft(placeId: UUID, draft: DurableVisitDraft, userId: UUID) async throws {
        let now = clock.nowMillis()
        try await database.withTransaction { db in
            // Upsert visit_drafts
            try db.execute(
                """
                INSERT INTO visit_drafts (
                    userId, placeId, overallScore, publicReview, privateMemory,
                    visitedAtEpochDay, visibility, dimensionsExpanded,
                    createdAtEpochMillis, updatedAtEpochMillis, payloadVersion,
                    primaryExperienceCode, rawExperienceLabel, overallFeelingCode,
                    companionCode, timeOfDayCode, vibeCodes, practicalSignalCodes,
                    title, titleSource, story, tip
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(userId, placeId) DO UPDATE SET
                    overallScore = excluded.overallScore,
                    publicReview = excluded.publicReview,
                    privateMemory = excluded.privateMemory,
                    visitedAtEpochDay = excluded.visitedAtEpochDay,
                    visibility = excluded.visibility,
                    dimensionsExpanded = excluded.dimensionsExpanded,
                    payloadVersion = excluded.payloadVersion,
                    primaryExperienceCode = excluded.primaryExperienceCode,
                    rawExperienceLabel = excluded.rawExperienceLabel,
                    overallFeelingCode = excluded.overallFeelingCode,
                    companionCode = excluded.companionCode,
                    timeOfDayCode = excluded.timeOfDayCode,
                    vibeCodes = excluded.vibeCodes,
                    practicalSignalCodes = excluded.practicalSignalCodes,
                    title = excluded.title,
                    titleSource = excluded.titleSource,
                    story = excluded.story,
                    tip = excluded.tip,
                    updatedAtEpochMillis = excluded.updatedAtEpochMillis;
                """,
                params: [
                    userId.uuidString,
                    placeId.uuidString,
                    draft.overallScore,
                    draft.publicReview,
                    draft.privateMemory,
                    draft.visitedAtEpochDay,
                    draft.visibility,
                    draft.dimensionsExpanded,
                    draft.createdAtEpochMillis > 0 ? draft.createdAtEpochMillis : now,
                    now,
                    draft.payloadVersion,
                    draft.primaryExperienceCode,
                    draft.rawExperienceLabel,
                    draft.overallFeelingCode,
                    draft.companionCode,
                    draft.timeOfDayCode,
                    draft.vibeCodes.sorted().joined(separator: ","),
                    draft.practicalSignalCodes.sorted().joined(separator: ","),
                    draft.title,
                    draft.titleSource,
                    draft.story,
                    draft.tip
                ]
            )

            // Replace dimension scores
            try db.execute(
                "DELETE FROM visit_draft_dimension_scores WHERE userId = ? AND placeId = ?;",
                params: [userId.uuidString, placeId.uuidString]
            )

            for score in draft.dimensions {
                try db.execute(
                    """
                    INSERT INTO visit_draft_dimension_scores
                        (userId, placeId, dimensionKey, score, semanticStateCode, templateVersion)
                    VALUES (?, ?, ?, ?, ?, ?);
                    """,
                    params: [
                        userId.uuidString, placeId.uuidString, score.dimensionKey, score.score,
                        score.semanticStateCode, score.templateVersion
                    ]
                )
            }
        }
    }

    public func deleteDraft(placeId: UUID, userId: UUID) async throws {
        try await database.withTransaction { db in
            try db.execute(
                "DELETE FROM visit_drafts WHERE userId = ? AND placeId = ?;",
                params: [userId.uuidString, placeId.uuidString]
            )
        }
    }

    public func deleteExpiredDrafts() async throws {
        let cutoff = clock.nowMillis() - VisitDraftRepositoryConstants.expiryMs
        try await database.withTransaction { db in
            try db.execute(
                "DELETE FROM visit_drafts WHERE updatedAtEpochMillis < ?;",
                params: [cutoff]
            )
        }
    }

    public func getPhotos(placeId: UUID, userId: UUID) async throws -> [DurableDraftPhoto] {
        try await database.query(
            """
            SELECT ownerUserId, placeId, position, clientMediaId, localRelativePath,
                   contentType, byteSize, width, height, remoteMediaId,
                   uploadState, failureCategory
            FROM visit_draft_photos
            WHERE ownerUserId = ? AND placeId = ?
            ORDER BY position ASC;
            """,
            params: [userId.uuidString, placeId.uuidString]
        ) { stmt -> DurableDraftPhoto in
            let remoteStr = sqlite3_column_text(stmt, 9).flatMap { String(cString: $0) }
            let remoteUUID = remoteStr.flatMap { UUID(uuidString: $0) }
            let failStr = sqlite3_column_text(stmt, 11).flatMap { String(cString: $0) }
            let width: Int? = sqlite3_column_type(stmt, 7) == SQLITE_NULL ? nil : Int(sqlite3_column_int(stmt, 7))
            let height: Int? = sqlite3_column_type(stmt, 8) == SQLITE_NULL ? nil : Int(sqlite3_column_int(stmt, 8))

            return DurableDraftPhoto(
                ownerUserId: UUID(uuidString: String(cString: sqlite3_column_text(stmt, 0))) ?? userId,
                placeId: UUID(uuidString: String(cString: sqlite3_column_text(stmt, 1))) ?? placeId,
                position: Int(sqlite3_column_int(stmt, 2)),
                clientMediaId: UUID(uuidString: String(cString: sqlite3_column_text(stmt, 3))) ?? UUID(),
                localRelativePath: String(cString: sqlite3_column_text(stmt, 4)),
                contentType: String(cString: sqlite3_column_text(stmt, 5)),
                byteSize: sqlite3_column_int64(stmt, 6),
                width: width,
                height: height,
                remoteMediaId: remoteUUID,
                uploadState: MediaUploadState(rawValue: String(cString: sqlite3_column_text(stmt, 10))) ?? .localOnly,
                failureCategory: failStr
            )
        }
    }

    public func upsertPhotos(placeId: UUID, photos: [DurableDraftPhoto], userId: UUID) async throws {
        try await database.withTransaction { db in
            for photo in photos {
                try db.execute(
                    """
                    INSERT INTO visit_draft_photos (
                        ownerUserId, placeId, position, clientMediaId, localRelativePath,
                        contentType, byteSize, width, height, remoteMediaId,
                        uploadState, failureCategory
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(ownerUserId, placeId, position) DO UPDATE SET
                        clientMediaId = excluded.clientMediaId,
                        localRelativePath = excluded.localRelativePath,
                        contentType = excluded.contentType,
                        byteSize = excluded.byteSize,
                        width = excluded.width,
                        height = excluded.height,
                        remoteMediaId = excluded.remoteMediaId,
                        uploadState = excluded.uploadState,
                        failureCategory = excluded.failureCategory;
                    """,
                    params: [
                        userId.uuidString,
                        placeId.uuidString,
                        photo.position,
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
        }
    }

    public func removePhoto(placeId: UUID, relativePath: String, userId: UUID) async throws {
        let existing = try await getPhotos(placeId: placeId, userId: userId)
        let remaining = existing.filter { $0.localRelativePath != relativePath }
        try await replacePhotos(placeId: placeId, photos: remaining, userId: userId)
    }

    public func replacePhotos(placeId: UUID, photos: [DurableDraftPhoto], userId: UUID) async throws {
        try await database.withTransaction { db in
            try db.execute(
                "DELETE FROM visit_draft_photos WHERE ownerUserId = ? AND placeId = ?;",
                params: [userId.uuidString, placeId.uuidString]
            )
            for (index, photo) in photos.enumerated() {
                var reindexed = photo
                reindexed.position = index
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
                        reindexed.position,
                        reindexed.clientMediaId.uuidString,
                        reindexed.localRelativePath,
                        reindexed.contentType,
                        reindexed.byteSize,
                        reindexed.width,
                        reindexed.height,
                        reindexed.remoteMediaId?.uuidString,
                        reindexed.uploadState.rawValue,
                        reindexed.failureCategory
                    ]
                )
            }
        }
    }

    public func updatePhotoRemoteState(
        placeId: UUID,
        clientMediaId: UUID,
        remoteMediaId: UUID,
        uploadState: MediaUploadState,
        userId: UUID
    ) async throws {
        let changed = try await database.execute(
            """
            UPDATE visit_draft_photos
            SET remoteMediaId = ?, uploadState = ?, failureCategory = NULL
            WHERE ownerUserId = ? AND placeId = ? AND clientMediaId = ?;
            """,
            params: [
                remoteMediaId.uuidString,
                uploadState.rawValue,
                userId.uuidString,
                placeId.uuidString,
                clientMediaId.uuidString
            ]
        )
        if changed != 1 {
            throw PersistenceError.recordNotFound
        }
    }

    public func getAllPhotos() async throws -> [DurableDraftPhoto] {
        try await database.query(
            """
            SELECT ownerUserId, placeId, position, clientMediaId, localRelativePath,
                   contentType, byteSize, width, height, remoteMediaId,
                   uploadState, failureCategory
            FROM visit_draft_photos;
            """
        ) { stmt -> DurableDraftPhoto in
            let remoteStr = sqlite3_column_text(stmt, 9).flatMap { String(cString: $0) }
            let remoteUUID = remoteStr.flatMap { UUID(uuidString: $0) }
            let failStr = sqlite3_column_text(stmt, 11).flatMap { String(cString: $0) }
            let width: Int? = sqlite3_column_type(stmt, 7) == SQLITE_NULL ? nil : Int(sqlite3_column_int(stmt, 7))
            let height: Int? = sqlite3_column_type(stmt, 8) == SQLITE_NULL ? nil : Int(sqlite3_column_int(stmt, 8))

            return DurableDraftPhoto(
                ownerUserId: UUID(uuidString: String(cString: sqlite3_column_text(stmt, 0))) ?? UUID(),
                placeId: UUID(uuidString: String(cString: sqlite3_column_text(stmt, 1))) ?? UUID(),
                position: Int(sqlite3_column_int(stmt, 2)),
                clientMediaId: UUID(uuidString: String(cString: sqlite3_column_text(stmt, 3))) ?? UUID(),
                localRelativePath: String(cString: sqlite3_column_text(stmt, 4)),
                contentType: String(cString: sqlite3_column_text(stmt, 5)),
                byteSize: sqlite3_column_int64(stmt, 6),
                width: width,
                height: height,
                remoteMediaId: remoteUUID,
                uploadState: MediaUploadState(rawValue: String(cString: sqlite3_column_text(stmt, 10))) ?? .localOnly,
                failureCategory: failStr
            )
        }
    }
}
