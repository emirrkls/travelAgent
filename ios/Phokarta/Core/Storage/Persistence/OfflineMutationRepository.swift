import Foundation
import SQLite3

protocol OfflineMutationRepository: Sendable {
    func commitVisit(
        payload: DurablePendingVisitPayload,
        dimensions: [DurablePendingDimensionScore],
        photos: [DurablePendingPhoto],
        userId: UUID
    ) async throws -> UUID
    func commitExperienceV2(
        payload: DurablePendingExperienceV2Payload,
        dimensions: [DurablePendingExperienceV2Dimension],
        photos: [DurablePendingPhoto],
        userId: UUID
    ) async throws -> UUID

    func getEligibleMutations(userId: UUID, limit: Int) async throws -> [DurablePendingMutation]
    func getVisitBundle(mutationId: UUID) async throws -> PendingVisitMutationBundle?
    func getExperienceV2Bundle(mutationId: UUID) async throws -> PendingExperienceV2MutationBundle?
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
    func setPlannedExperience(_ experience: ExperienceV2, userId: UUID, desired: Bool) async throws
    func removePlannedExperience(experienceId: UUID, userId: UUID) async throws
    func localPlannedExperiences(userId: UUID) async throws -> [DurablePlannedExperienceRow]
    func acknowledgeExperience(_ experience: ExperienceV2, userId: UUID) async throws -> UUID
    func localAcknowledgements(userId: UUID) async throws -> [ExperienceAcknowledgementV2]
    func getAcknowledgementAnchor(sourceExperienceId: UUID, userId: UUID) async throws -> DurableAcknowledgementAnchor?
    func reconcilePlannedExperience(_ value: PlannedExperienceV2?, experienceId: UUID, userId: UUID) async throws
    func reconcileAcknowledgement(_ value: ExperienceAcknowledgementV2, sourceExperienceId: UUID, userId: UUID) async throws
    func markAcknowledgementConverted(id: UUID, experienceId: UUID, userId: UUID) async throws
    func localConversation(experienceId: UUID, userId: UUID) async throws -> [ConversationEntry]
    func replaceConversationSnapshot(_ entries: [ConversationEntry], experienceId: UUID, userId: UUID) async throws
    func createConversationRoot(
        experience: ExperienceV2, type: ConversationEntryType, body: String,
        author: ConversationAuthor, userId: UUID
    ) async throws -> UUID
    func createConversationReply(
        experience: ExperienceV2, root: ConversationEntry, body: String,
        author: ConversationAuthor, userId: UUID
    ) async throws -> UUID
    func editConversationEntry(_ entry: ConversationEntry, body: String, userId: UUID) async throws
    func deleteConversationEntry(_ entry: ConversationEntry, userId: UUID) async throws
    func getConversationBundle(mutationId: UUID) async throws -> PendingConversationMutationBundle?
    func reconcileConversationEntry(_ entry: ConversationEntry, mutationId: UUID, userId: UUID) async throws
    func reconcileConversationDeletion(entryId: UUID, mutationId: UUID, userId: UUID) async throws
}

extension OfflineMutationRepository {
    func setPlannedExperience(_ experience: ExperienceV2, userId: UUID, desired: Bool) async throws {
        throw PersistenceError.executionFailed("Experience planning is unavailable")
    }
    func removePlannedExperience(experienceId: UUID, userId: UUID) async throws {
        throw PersistenceError.executionFailed("Experience planning is unavailable")
    }
    func localPlannedExperiences(userId: UUID) async throws -> [DurablePlannedExperienceRow] { [] }
    func acknowledgeExperience(_ experience: ExperienceV2, userId: UUID) async throws -> UUID {
        throw PersistenceError.executionFailed("Experience acknowledgement is unavailable")
    }
    func localAcknowledgements(userId: UUID) async throws -> [ExperienceAcknowledgementV2] { [] }
    func getAcknowledgementAnchor(sourceExperienceId: UUID, userId: UUID) async throws -> DurableAcknowledgementAnchor? { nil }
    func reconcilePlannedExperience(_ value: PlannedExperienceV2?, experienceId: UUID, userId: UUID) async throws {}
    func reconcileAcknowledgement(_ value: ExperienceAcknowledgementV2, sourceExperienceId: UUID, userId: UUID) async throws {}
    func markAcknowledgementConverted(id: UUID, experienceId: UUID, userId: UUID) async throws {}
    func commitExperienceV2(
        payload: DurablePendingExperienceV2Payload,
        dimensions: [DurablePendingExperienceV2Dimension],
        photos: [DurablePendingPhoto],
        userId: UUID
    ) async throws -> UUID {
        throw PersistenceError.executionFailed("V2 publication is unavailable")
    }

    func getExperienceV2Bundle(mutationId: UUID) async throws -> PendingExperienceV2MutationBundle? {
        nil
    }
    func localConversation(experienceId: UUID, userId: UUID) async throws -> [ConversationEntry] { [] }
    func replaceConversationSnapshot(_ entries: [ConversationEntry], experienceId: UUID, userId: UUID) async throws {}
    func createConversationRoot(
        experience: ExperienceV2, type: ConversationEntryType, body: String,
        author: ConversationAuthor, userId: UUID
    ) async throws -> UUID { throw PersistenceError.executionFailed("Conversation unavailable") }
    func createConversationReply(
        experience: ExperienceV2, root: ConversationEntry, body: String,
        author: ConversationAuthor, userId: UUID
    ) async throws -> UUID { throw PersistenceError.executionFailed("Conversation unavailable") }
    func editConversationEntry(_ entry: ConversationEntry, body: String, userId: UUID) async throws {
        throw PersistenceError.executionFailed("Conversation unavailable")
    }
    func deleteConversationEntry(_ entry: ConversationEntry, userId: UUID) async throws {
        throw PersistenceError.executionFailed("Conversation unavailable")
    }
    func getConversationBundle(mutationId: UUID) async throws -> PendingConversationMutationBundle? { nil }
    func reconcileConversationEntry(_ entry: ConversationEntry, mutationId: UUID, userId: UUID) async throws {}
    func reconcileConversationDeletion(entryId: UUID, mutationId: UUID, userId: UUID) async throws {}
}

final class SQLiteOfflineMutationRepository: OfflineMutationRepository, Sendable {
    private let database: PersistentDatabase
    private let clock: any EpochClock

    init(database: PersistentDatabase, clock: any EpochClock = SystemEpochClock()) {
        self.database = database
        self.clock = clock
    }

    func localConversation(experienceId: UUID, userId: UUID) async throws -> [ConversationEntry] {
        let rows = try await database.query(
            """
            SELECT entryId,parentEntryId,type,body,authorId,authorUsername,authorDisplayName,
                   authorAvatarUrl,createdAt,updatedAt,edited,experienceAuthor,ownedByViewer,
                   reportableByViewer,syncState,clientMutationId
            FROM conversation_entries
            WHERE userId = ? AND experienceId = ?;
            """,
            params: [userId.uuidString, experienceId.uuidString]
        ) { statement -> (ConversationEntry, UUID?) in
            let entryId = UUID(uuidString: String(cString: sqlite3_column_text(statement, 0)))!
            let parentId = sqlite3_column_text(statement, 1).flatMap { UUID(uuidString: String(cString: $0)) }
            let authorId = UUID(uuidString: String(cString: sqlite3_column_text(statement, 4)))!
            let avatar = sqlite3_column_text(statement, 7).map { String(cString: $0) }
            let mutationId = sqlite3_column_text(statement, 15).flatMap { UUID(uuidString: String(cString: $0)) }
            return (ConversationEntry(
                id: entryId,
                experienceId: experienceId,
                type: ConversationEntryType(rawValue: String(cString: sqlite3_column_text(statement, 2))) ?? .unknown,
                body: String(cString: sqlite3_column_text(statement, 3)),
                author: ConversationAuthor(
                    id: authorId,
                    username: String(cString: sqlite3_column_text(statement, 5)),
                    displayName: String(cString: sqlite3_column_text(statement, 6)),
                    avatarUrl: avatar
                ),
                createdAt: String(cString: sqlite3_column_text(statement, 8)),
                updatedAt: String(cString: sqlite3_column_text(statement, 9)),
                edited: sqlite3_column_int(statement, 10) != 0,
                experienceAuthor: sqlite3_column_int(statement, 11) != 0,
                ownedByViewer: sqlite3_column_int(statement, 12) != 0,
                reportableByViewer: sqlite3_column_int(statement, 13) != 0,
                syncState: ConversationSyncState(rawValue: String(cString: sqlite3_column_text(statement, 14))) ?? .failed,
                clientMutationId: mutationId
            ), parentId)
        }
        let replies = Dictionary(grouping: rows.filter { $0.1 != nil }, by: { $0.1! })
        return rows.filter { $0.1 == nil }
            .sorted { ($0.0.createdAt, $0.0.id.uuidString) > ($1.0.createdAt, $1.0.id.uuidString) }
            .map { row in
                var root = row.0
                root.replies = replies[root.id, default: []]
                    .map { $0.0 }
                    .sorted { ($0.createdAt, $0.id.uuidString) < ($1.createdAt, $1.id.uuidString) }
                return root
            }
    }

    func replaceConversationSnapshot(
        _ entries: [ConversationEntry], experienceId: UUID, userId: UUID
    ) async throws {
        try await database.withTransaction { db in
            try db.execute(
                "DELETE FROM conversation_entries WHERE userId = ? AND experienceId = ? AND syncState = 'SYNCED';",
                params: [userId.uuidString, experienceId.uuidString]
            )
            for root in entries {
                try Self.upsertConversation(root, parentId: nil, userId: userId, db: db)
                for reply in root.replies {
                    try Self.upsertConversation(reply, parentId: root.id, userId: userId, db: db)
                }
            }
        }
    }

    func createConversationRoot(
        experience: ExperienceV2, type: ConversationEntryType, body: String,
        author: ConversationAuthor, userId: UUID
    ) async throws -> UUID {
        guard type == .question || type == .comment else { throw PersistenceError.conflict("Invalid root type") }
        return try await queueConversationCreate(
            experience: experience, parent: nil, type: type, body: body, author: author, userId: userId
        )
    }

    func createConversationReply(
        experience: ExperienceV2, root: ConversationEntry, body: String,
        author: ConversationAuthor, userId: UUID
    ) async throws -> UUID {
        guard root.type != .reply, root.syncState == .synced else {
            throw PersistenceError.conflict("Reply is available after the root finishes sending")
        }
        return try await queueConversationCreate(
            experience: experience, parent: root, type: .reply, body: body, author: author, userId: userId
        )
    }

    private func queueConversationCreate(
        experience: ExperienceV2, parent: ConversationEntry?, type: ConversationEntryType,
        body: String, author: ConversationAuthor, userId: UUID
    ) async throws -> UUID {
        let normalized = try Self.conversationBody(body)
        let mutationId = UUID()
        let nowMillis = clock.nowMillis()
        let timestamp = ISO8601DateFormatter().string(from: Date(timeIntervalSince1970: Double(nowMillis) / 1000))
        let mutationType: MutationType = parent == nil ? .createConversationRoot : .createConversationReply
        let local = ConversationEntry(
            id: mutationId, experienceId: experience.id, type: type, body: normalized,
            author: author, createdAt: timestamp, updatedAt: timestamp, edited: false,
            experienceAuthor: author.id == experience.author.id, ownedByViewer: true,
            reportableByViewer: false, syncState: .pending, clientMutationId: mutationId
        )
        try await database.withTransaction { db in
            try db.execute(
                """
                INSERT INTO pending_mutations
                (mutationId,userId,type,resourceKey,state,generation,attemptCount,
                 createdAtEpochMillis,updatedAtEpochMillis,lastErrorCategory,payloadVersion)
                VALUES (?,?,?,?,?,1,0,?,?,NULL,1);
                """,
                params: [mutationId.uuidString, userId.uuidString, mutationType.rawValue,
                         mutationId.uuidString, MutationState.pending.rawValue, nowMillis, nowMillis]
            )
            try db.execute(
                """
                INSERT INTO pending_conversation_payloads
                (mutationId,experienceId,targetEntryId,parentEntryId,entryType,body,localEntryId)
                VALUES (?,?,NULL,?,?,?,?);
                """,
                params: [mutationId.uuidString, experience.id.uuidString,
                         parent?.id.uuidString, type.rawValue, normalized, mutationId.uuidString]
            )
            try Self.upsertConversation(local, parentId: parent?.id, userId: userId, db: db)
        }
        return mutationId
    }

    func editConversationEntry(_ entry: ConversationEntry, body: String, userId: UUID) async throws {
        guard entry.ownedByViewer, entry.syncState == .synced else { throw PersistenceError.conflict("Entry cannot be edited") }
        let normalized = try Self.conversationBody(body)
        let mutationId = UUID()
        let nowMillis = clock.nowMillis()
        let timestamp = ISO8601DateFormatter().string(from: Date(timeIntervalSince1970: Double(nowMillis) / 1000))
        try await database.withTransaction { db in
            let parentIds = try db.query(
                "SELECT parentEntryId FROM conversation_entries WHERE userId=? AND entryId=? LIMIT 1;",
                params: [userId.uuidString, entry.id.uuidString]
            ) { statement in
                sqlite3_column_text(statement, 0).flatMap { UUID(uuidString: String(cString: $0)) }
            }
            let parentId = parentIds.first ?? nil
            try Self.insertConversationMutation(
                id: mutationId, userId: userId, type: .editConversationEntry,
                resourceKey: entry.id.uuidString, now: nowMillis, db: db
            )
            try db.execute(
                """
                INSERT INTO pending_conversation_payloads
                (mutationId,experienceId,targetEntryId,parentEntryId,entryType,body,localEntryId)
                VALUES (?,?,?,?,?,?,?);
                """,
                params: [mutationId.uuidString, entry.experienceId.uuidString, entry.id.uuidString,
                         parentId?.uuidString, entry.type.rawValue, normalized, entry.id.uuidString]
            )
            try db.execute(
                """
                UPDATE conversation_entries SET body=?,updatedAt=?,edited=1,syncState='PENDING',
                clientMutationId=? WHERE userId=? AND entryId=?;
                """,
                params: [normalized, timestamp, mutationId.uuidString, userId.uuidString, entry.id.uuidString]
            )
        }
    }

    func deleteConversationEntry(_ entry: ConversationEntry, userId: UUID) async throws {
        guard entry.ownedByViewer, entry.syncState == .synced else { throw PersistenceError.conflict("Entry cannot be deleted") }
        let mutationId = UUID()
        let nowMillis = clock.nowMillis()
        try await database.withTransaction { db in
            try Self.insertConversationMutation(
                id: mutationId, userId: userId, type: .deleteConversationEntry,
                resourceKey: entry.id.uuidString, now: nowMillis, db: db
            )
            try db.execute(
                """
                INSERT INTO pending_conversation_payloads
                (mutationId,experienceId,targetEntryId,parentEntryId,entryType,body,localEntryId)
                VALUES (?,?,?,NULL,NULL,NULL,?);
                """,
                params: [mutationId.uuidString, entry.experienceId.uuidString,
                         entry.id.uuidString, entry.id.uuidString]
            )
            try db.execute(
                "UPDATE conversation_entries SET syncState='PENDING',clientMutationId=? WHERE userId=? AND entryId=?;",
                params: [mutationId.uuidString, userId.uuidString, entry.id.uuidString]
            )
        }
    }

    func getConversationBundle(mutationId: UUID) async throws -> PendingConversationMutationBundle? {
        let mutations = try await database.query(
            """
            SELECT mutationId,userId,type,resourceKey,state,generation,attemptCount,
                   createdAtEpochMillis,updatedAtEpochMillis,lastErrorCategory,payloadVersion
            FROM pending_mutations WHERE mutationId=?;
            """,
            params: [mutationId.uuidString]
        ) { statement -> DurablePendingMutation? in
            guard let type = MutationType(rawValue: String(cString: sqlite3_column_text(statement, 2))),
                  [.createConversationRoot, .createConversationReply, .editConversationEntry,
                   .deleteConversationEntry].contains(type) else { return nil }
            return DurablePendingMutation(
                mutationId: mutationId,
                userId: UUID(uuidString: String(cString: sqlite3_column_text(statement, 1)))!,
                type: type,
                resourceKey: String(cString: sqlite3_column_text(statement, 3)),
                state: MutationState(rawValue: String(cString: sqlite3_column_text(statement, 4))) ?? .pending,
                generation: sqlite3_column_int64(statement, 5),
                attemptCount: Int(sqlite3_column_int(statement, 6)),
                createdAtEpochMillis: sqlite3_column_int64(statement, 7),
                updatedAtEpochMillis: sqlite3_column_int64(statement, 8),
                lastErrorCategory: sqlite3_column_text(statement, 9).map { String(cString: $0) },
                payloadVersion: Int(sqlite3_column_int(statement, 10))
            )
        }.compactMap { $0 }
        guard let mutation = mutations.first else { return nil }
        let payloads = try await database.query(
            """
            SELECT experienceId,targetEntryId,parentEntryId,entryType,body,localEntryId
            FROM pending_conversation_payloads WHERE mutationId=?;
            """,
            params: [mutationId.uuidString]
        ) { statement in
            DurablePendingConversationPayload(
                mutationId: mutationId,
                experienceId: UUID(uuidString: String(cString: sqlite3_column_text(statement, 0)))!,
                targetEntryId: sqlite3_column_text(statement, 1).flatMap { UUID(uuidString: String(cString: $0)) },
                parentEntryId: sqlite3_column_text(statement, 2).flatMap { UUID(uuidString: String(cString: $0)) },
                entryType: sqlite3_column_text(statement, 3).flatMap { ConversationEntryType(rawValue: String(cString: $0)) },
                body: sqlite3_column_text(statement, 4).map { String(cString: $0) },
                localEntryId: sqlite3_column_text(statement, 5).flatMap { UUID(uuidString: String(cString: $0)) }
            )
        }
        guard let payload = payloads.first else { return nil }
        return PendingConversationMutationBundle(mutation: mutation, payload: payload)
    }

    func reconcileConversationEntry(
        _ entry: ConversationEntry, mutationId: UUID, userId: UUID
    ) async throws {
        let parentId = try await getConversationBundle(mutationId: mutationId)?.payload.parentEntryId
        try await database.withTransaction { db in
            try db.execute(
                "DELETE FROM conversation_entries WHERE userId=? AND clientMutationId=?;",
                params: [userId.uuidString, mutationId.uuidString]
            )
            try Self.upsertConversation(entry, parentId: parentId, userId: userId, db: db)
        }
    }

    func reconcileConversationDeletion(entryId: UUID, mutationId: UUID, userId: UUID) async throws {
        try await database.execute(
            "DELETE FROM conversation_entries WHERE userId=? AND (entryId=? OR parentEntryId=?);",
            params: [userId.uuidString, entryId.uuidString, entryId.uuidString]
        )
    }

    func setPlannedExperience(_ experience: ExperienceV2, userId: UUID, desired: Bool) async throws {
        if !desired {
            try await removePlannedExperience(experienceId: experience.id, userId: userId)
            return
        }
        let now = clock.nowMillis()
        let mutationId = UUID()
        let snapshot = Self.planSnapshot(experience)
        try await database.withTransaction { db in
            try db.execute(
                "DELETE FROM pending_mutations WHERE userId = ? AND type = ? AND resourceKey = ?;",
                params: [userId.uuidString, MutationType.setPlannedExperienceState.rawValue, experience.id.uuidString]
            )
            try db.execute(
                """
                INSERT INTO pending_mutations
                (mutationId,userId,type,resourceKey,state,generation,attemptCount,
                 createdAtEpochMillis,updatedAtEpochMillis,lastErrorCategory,payloadVersion)
                VALUES (?,?,?,?,?,1,0,?,?,NULL,?);
                """,
                params: [mutationId.uuidString, userId.uuidString,
                         MutationType.setPlannedExperienceState.rawValue, experience.id.uuidString,
                         MutationState.pending.rawValue, now, now, desired ? 1 : 0]
            )
            try db.execute(
                """
                INSERT INTO planned_experiences
                (userId,experienceId,plannedAt,snapshotJson,pendingDesiredState)
                VALUES (?,?,?,?,1)
                ON CONFLICT(userId,experienceId) DO UPDATE SET
                  plannedAt=excluded.plannedAt,snapshotJson=excluded.snapshotJson,pendingDesiredState=1;
                """,
                params: [userId.uuidString, experience.id.uuidString,
                         ISO8601DateFormatter().string(from: Date()), snapshot]
            )
        }
    }

    func removePlannedExperience(experienceId: UUID, userId: UUID) async throws {
        let now = clock.nowMillis()
        let mutationId = UUID()
        try await database.withTransaction { db in
            try db.execute(
                "DELETE FROM pending_mutations WHERE userId = ? AND type = ? AND resourceKey = ?;",
                params: [userId.uuidString, MutationType.setPlannedExperienceState.rawValue, experienceId.uuidString]
            )
            try db.execute(
                """
                INSERT INTO pending_mutations
                (mutationId,userId,type,resourceKey,state,generation,attemptCount,
                 createdAtEpochMillis,updatedAtEpochMillis,lastErrorCategory,payloadVersion)
                VALUES (?,?,?,?,?,1,0,?,?,NULL,0);
                """,
                params: [mutationId.uuidString, userId.uuidString,
                         MutationType.setPlannedExperienceState.rawValue, experienceId.uuidString,
                         MutationState.pending.rawValue, now, now]
            )
            try db.execute("DELETE FROM planned_experiences WHERE userId = ? AND experienceId = ?;",
                           params: [userId.uuidString, experienceId.uuidString])
        }
    }

    func localPlannedExperiences(userId: UUID) async throws -> [DurablePlannedExperienceRow] {
        try await database.query(
            """
            SELECT experienceId,plannedAt,snapshotJson,pendingDesiredState
            FROM planned_experiences WHERE userId = ? ORDER BY plannedAt DESC;
            """,
            params: [userId.uuidString]
        ) { statement in
            let id = UUID(uuidString: String(cString: sqlite3_column_text(statement, 0)))!
            let plannedAt = String(cString: sqlite3_column_text(statement, 1))
            let snapshot = String(cString: sqlite3_column_text(statement, 2))
            let json = (try? JSONSerialization.jsonObject(with: Data(snapshot.utf8))) as? [String: String] ?? [:]
            let pending: Bool? = sqlite3_column_type(statement, 3) == SQLITE_NULL
                ? nil : sqlite3_column_int(statement, 3) != 0
            return DurablePlannedExperienceRow(
                id: id, title: json["title"] ?? "", placeName: json["placeName"] ?? "",
                authorName: json["authorName"] ?? "",
                primaryExperienceCode: json["primaryExperienceCode"] ?? "UNKNOWN_LEGACY",
                imageURL: json["imageURL"], plannedAt: plannedAt, pendingDesiredState: pending
            )
        }
    }

    func acknowledgeExperience(_ experience: ExperienceV2, userId: UUID) async throws -> UUID {
        let existing = try await database.query(
            "SELECT acknowledgementId FROM experience_acknowledgements WHERE userId = ? AND sourceExperienceId = ? LIMIT 1;",
            params: [userId.uuidString, experience.id.uuidString]
        ) { UUID(uuidString: String(cString: sqlite3_column_text($0, 0))) }
        if let id = existing.first ?? nil { return id }
        let id = UUID()
        let now = clock.nowMillis()
        let timestamp = ISO8601DateFormatter().string(from: Date())
        try await database.withTransaction { db in
            try db.execute(
                """
                INSERT INTO experience_acknowledgements
                (userId,acknowledgementId,sourceExperienceId,placeId,acknowledgedAt,
                 convertedExperienceId,snapshotJson,pendingUpload)
                VALUES (?,?,?,?,?,NULL,?,1);
                """,
                params: [userId.uuidString, id.uuidString, experience.id.uuidString,
                         experience.place.id.uuidString, timestamp, Self.acknowledgementSnapshot(experience)]
            )
            try db.execute(
                """
                INSERT INTO pending_mutations
                (mutationId,userId,type,resourceKey,state,generation,attemptCount,
                 createdAtEpochMillis,updatedAtEpochMillis,lastErrorCategory,payloadVersion)
                VALUES (?,?,?,?,?,1,0,?,?,NULL,1);
                """,
                params: [id.uuidString, userId.uuidString, MutationType.acknowledgeExperience.rawValue,
                         experience.id.uuidString, MutationState.pending.rawValue, now, now]
            )
        }
        return id
    }

    func localAcknowledgements(userId: UUID) async throws -> [ExperienceAcknowledgementV2] {
        try await database.query(
            """
            SELECT acknowledgementId,sourceExperienceId,placeId,acknowledgedAt,
                   convertedExperienceId,snapshotJson
            FROM experience_acknowledgements
            WHERE userId = ? AND convertedExperienceId IS NULL
            ORDER BY acknowledgedAt DESC;
            """,
            params: [userId.uuidString]
        ) { statement in
            let id = UUID(uuidString: String(cString: sqlite3_column_text(statement, 0)))!
            let source = sqlite3_column_type(statement, 1) == SQLITE_NULL ? nil
                : UUID(uuidString: String(cString: sqlite3_column_text(statement, 1)))
            let placeId = UUID(uuidString: String(cString: sqlite3_column_text(statement, 2)))!
            let acknowledgedAt = String(cString: sqlite3_column_text(statement, 3))
            let converted = sqlite3_column_type(statement, 4) == SQLITE_NULL ? nil
                : UUID(uuidString: String(cString: sqlite3_column_text(statement, 4)))
            let snapshot = String(cString: sqlite3_column_text(statement, 5))
            let json = (try? JSONSerialization.jsonObject(with: Data(snapshot.utf8))) as? [String: String] ?? [:]
            return ExperienceAcknowledgementV2(
                id: id, sourceExperienceId: source,
                sourceAvailable: json["sourceAvailable"] != "false", sourceExperience: nil,
                place: .init(id: placeId, name: json["placeName"] ?? "",
                             city: json["placeCity"] ?? "", region: json["placeRegion"] ?? "",
                             country: json["placeCountry"] ?? ""),
                primaryExperienceCode: PrimaryExperienceCode(rawValue: json["primaryExperienceCode"] ?? "") ?? .unknown,
                rawExperienceLabel: json["rawExperienceLabel"].flatMap { $0.isEmpty ? nil : $0 },
                acknowledgedAt: acknowledgedAt, status: "UNCONVERTED",
                convertedExperienceId: converted
            )
        }
    }

    func getAcknowledgementAnchor(
        sourceExperienceId: UUID,
        userId: UUID
    ) async throws -> DurableAcknowledgementAnchor? {
        let rows = try await database.query(
            "SELECT placeId,snapshotJson FROM experience_acknowledgements WHERE userId = ? AND sourceExperienceId = ? LIMIT 1;",
            params: [userId.uuidString, sourceExperienceId.uuidString]
        ) {
            (String(cString: sqlite3_column_text($0, 0)), String(cString: sqlite3_column_text($0, 1)))
        }
        guard let row = rows.first, let placeId = UUID(uuidString: row.0),
              let data = row.1.data(using: .utf8),
              let json = try JSONSerialization.jsonObject(with: data) as? [String: String],
              let primary = json["primaryExperienceCode"], !primary.isEmpty else { return nil }
        let raw = json["rawExperienceLabel"].flatMap { $0.isEmpty ? nil : $0 }
        return DurableAcknowledgementAnchor(
            placeId: placeId, primaryExperienceCode: primary, rawExperienceLabel: raw
        )
    }

    private static func planSnapshot(_ value: ExperienceV2) -> String {
        jsonString(["title": value.title, "placeName": value.place.name,
                    "authorName": value.author.displayName,
                    "primaryExperienceCode": value.primaryExperience.code.rawValue,
                    "imageURL": value.media.min(by: { $0.position < $1.position })?.url ?? ""])
    }

    private static func acknowledgementSnapshot(_ value: ExperienceV2) -> String {
        jsonString(["placeName": value.place.name, "placeCity": value.place.city,
                    "placeRegion": value.place.region, "placeCountry": value.place.country,
                    "sourceAvailable": "true",
                    "primaryExperienceCode": value.primaryExperience.code.rawValue,
                    "rawExperienceLabel": value.primaryExperience.rawLabel ?? ""])
    }

    private static func acknowledgementSnapshot(_ value: ExperienceAcknowledgementV2) -> String {
        jsonString(["placeName": value.place.name, "placeCity": value.place.city,
                    "placeRegion": value.place.region, "placeCountry": value.place.country,
                    "sourceAvailable": value.sourceAvailable ? "true" : "false",
                    "primaryExperienceCode": value.primaryExperienceCode.rawValue,
                    "rawExperienceLabel": value.rawExperienceLabel ?? ""])
    }

    private static func jsonString(_ value: [String: String]) -> String {
        let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
        return data.flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    }

    func reconcilePlannedExperience(_ value: PlannedExperienceV2?, experienceId: UUID, userId: UUID) async throws {
        if let value {
            try await database.execute(
                "UPDATE planned_experiences SET plannedAt = ?, snapshotJson = ?, pendingDesiredState = NULL WHERE userId = ? AND experienceId = ?;",
                params: [value.plannedAt, Self.planSnapshot(value.experience), userId.uuidString, experienceId.uuidString]
            )
        } else {
            try await database.execute("DELETE FROM planned_experiences WHERE userId = ? AND experienceId = ?;",
                                       params: [userId.uuidString, experienceId.uuidString])
        }
    }

    func reconcileAcknowledgement(_ value: ExperienceAcknowledgementV2, sourceExperienceId: UUID, userId: UUID) async throws {
        try await database.execute(
            """
            UPDATE experience_acknowledgements SET acknowledgementId = ?, acknowledgedAt = ?,
            convertedExperienceId = ?, snapshotJson = ?, pendingUpload = 0
            WHERE userId = ? AND sourceExperienceId = ?;
            """,
            params: [value.id.uuidString, value.acknowledgedAt, value.convertedExperienceId?.uuidString,
                     Self.acknowledgementSnapshot(value), userId.uuidString, sourceExperienceId.uuidString]
        )
    }

    func markAcknowledgementConverted(id: UUID, experienceId: UUID, userId: UUID) async throws {
        let changed = try await database.execute(
            "UPDATE experience_acknowledgements SET convertedExperienceId = ? WHERE userId = ? AND acknowledgementId = ?;",
            params: [experienceId.uuidString, userId.uuidString, id.uuidString]
        )
        guard changed == 1 else { throw PersistenceError.recordNotFound }
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

    public func commitExperienceV2(
        payload: DurablePendingExperienceV2Payload,
        dimensions: [DurablePendingExperienceV2Dimension],
        photos: [DurablePendingPhoto],
        userId: UUID
    ) async throws -> UUID {
        guard photos.count <= 6 else { throw PersistenceError.conflict("V2 media limit exceeded") }
        let now = clock.nowMillis()
        let mutationId = payload.mutationId
        try await database.withTransaction { db in
            try db.execute(
                """
                INSERT INTO pending_mutations (
                    mutationId,userId,type,resourceKey,state,generation,attemptCount,
                    createdAtEpochMillis,updatedAtEpochMillis,lastErrorCategory,payloadVersion
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?);
                """,
                params: [
                    mutationId.uuidString, userId.uuidString, MutationType.publishExperienceV2.rawValue,
                    mutationId.uuidString, MutationState.pending.rawValue, 1, 0, now, now, nil, 2
                ]
            )
            try db.execute(
                """
                INSERT INTO pending_experience_v2_payloads (
                    mutationId,placeId,visitedAtEpochDay,primaryExperienceCode,rawExperienceLabel,
                    overallFeelingCode,companionCode,timeOfDayCode,vibeCodes,practicalSignalCodes,
                    title,titleSource,story,tip,privateMemory,visibility,originAcknowledgementId
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?);
                """,
                params: [
                    mutationId.uuidString, payload.placeId.uuidString, payload.visitedAtEpochDay,
                    payload.primaryExperienceCode, payload.rawExperienceLabel,
                    payload.overallFeelingCode, payload.companionCode, payload.timeOfDayCode,
                    payload.vibeCodes.sorted().joined(separator: ","),
                    payload.practicalSignalCodes.sorted().joined(separator: ","),
                    payload.title, payload.titleSource, payload.story, payload.tip,
                    payload.privateMemory, payload.visibility, payload.originAcknowledgementId?.uuidString
                ]
            )
            for dimension in dimensions.sorted(by: { $0.dimensionKey < $1.dimensionKey }) {
                try db.execute(
                    """
                    INSERT INTO pending_experience_v2_dimensions
                        (mutationId,dimensionKey,semanticStateCode,templateVersion)
                    VALUES (?,?,?,?);
                    """,
                    params: [
                        mutationId.uuidString, dimension.dimensionKey,
                        dimension.semanticStateCode, dimension.templateVersion
                    ]
                )
            }
            for photo in photos.sorted(by: { $0.position < $1.position }) {
                try db.execute(
                    """
                    INSERT INTO pending_visit_photos (
                        mutationId,position,ownerUserId,clientMediaId,localRelativePath,contentType,
                        byteSize,width,height,remoteMediaId,uploadState,failureCategory
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?);
                    """,
                    params: [
                        mutationId.uuidString, photo.position, userId.uuidString,
                        photo.clientMediaId.uuidString, photo.localRelativePath, photo.contentType,
                        photo.byteSize, photo.width, photo.height, photo.remoteMediaId?.uuidString,
                        photo.uploadState.rawValue, photo.failureCategory
                    ]
                )
            }
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
                   updatedAtEpochMillis, lastErrorCategory, payloadVersion
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
                lastErrorCategory: sqlite3_column_text(stmt, 9).flatMap { String(cString: $0) },
                payloadVersion: Int(sqlite3_column_int(stmt, 10))
            )
        }
    }

    public func getVisitBundle(mutationId: UUID) async throws -> PendingVisitMutationBundle? {
        let mutations = try await database.query(
            """
            SELECT mutationId, userId, type, resourceKey, state,
                   generation, attemptCount, createdAtEpochMillis,
                   updatedAtEpochMillis, lastErrorCategory, payloadVersion
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
                lastErrorCategory: sqlite3_column_text(stmt, 9).flatMap { String(cString: $0) },
                payloadVersion: Int(sqlite3_column_int(stmt, 10))
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

    public func getExperienceV2Bundle(mutationId: UUID) async throws -> PendingExperienceV2MutationBundle? {
        let mutations = try await database.query(
            """
            SELECT mutationId,userId,type,resourceKey,state,generation,attemptCount,
                   createdAtEpochMillis,updatedAtEpochMillis,lastErrorCategory,payloadVersion
            FROM pending_mutations WHERE mutationId = ? AND type = 'PUBLISH_EXPERIENCE_V2';
            """,
            params: [mutationId.uuidString]
        ) { stmt in
            DurablePendingMutation(
                mutationId: UUID(uuidString: String(cString: sqlite3_column_text(stmt, 0))) ?? mutationId,
                userId: UUID(uuidString: String(cString: sqlite3_column_text(stmt, 1))) ?? UUID(),
                type: .publishExperienceV2,
                resourceKey: String(cString: sqlite3_column_text(stmt, 3)),
                state: MutationState(rawValue: String(cString: sqlite3_column_text(stmt, 4))) ?? .pending,
                generation: sqlite3_column_int64(stmt, 5),
                attemptCount: Int(sqlite3_column_int(stmt, 6)),
                createdAtEpochMillis: sqlite3_column_int64(stmt, 7),
                updatedAtEpochMillis: sqlite3_column_int64(stmt, 8),
                lastErrorCategory: sqlite3_column_text(stmt, 9).map { String(cString: $0) },
                payloadVersion: Int(sqlite3_column_int(stmt, 10))
            )
        }
        guard let mutation = mutations.first else { return nil }
        let payloads = try await database.query(
            """
            SELECT mutationId,placeId,visitedAtEpochDay,primaryExperienceCode,rawExperienceLabel,
                   overallFeelingCode,companionCode,timeOfDayCode,vibeCodes,practicalSignalCodes,
                   title,titleSource,story,tip,privateMemory,visibility,originAcknowledgementId
            FROM pending_experience_v2_payloads WHERE mutationId = ?;
            """,
            params: [mutationId.uuidString]
        ) { stmt in
            DurablePendingExperienceV2Payload(
                mutationId: mutationId,
                placeId: UUID(uuidString: String(cString: sqlite3_column_text(stmt, 1))) ?? UUID(),
                visitedAtEpochDay: sqlite3_column_int64(stmt, 2),
                primaryExperienceCode: String(cString: sqlite3_column_text(stmt, 3)),
                rawExperienceLabel: sqlite3_column_text(stmt, 4).map { String(cString: $0) },
                overallFeelingCode: String(cString: sqlite3_column_text(stmt, 5)),
                companionCode: sqlite3_column_text(stmt, 6).map { String(cString: $0) },
                timeOfDayCode: sqlite3_column_text(stmt, 7).map { String(cString: $0) },
                vibeCodes: String(cString: sqlite3_column_text(stmt, 8)).split(separator: ",").map(String.init),
                practicalSignalCodes: String(cString: sqlite3_column_text(stmt, 9)).split(separator: ",").map(String.init),
                title: sqlite3_column_text(stmt, 10).map { String(cString: $0) },
                titleSource: String(cString: sqlite3_column_text(stmt, 11)),
                story: String(cString: sqlite3_column_text(stmt, 12)),
                tip: String(cString: sqlite3_column_text(stmt, 13)),
                privateMemory: String(cString: sqlite3_column_text(stmt, 14)),
                visibility: String(cString: sqlite3_column_text(stmt, 15)),
                originAcknowledgementId: sqlite3_column_text(stmt, 16).flatMap { UUID(uuidString: String(cString: $0)) }
            )
        }
        guard let payload = payloads.first else { return nil }
        let dimensions = try await database.query(
            """
            SELECT mutationId,dimensionKey,semanticStateCode,templateVersion
            FROM pending_experience_v2_dimensions WHERE mutationId = ? ORDER BY dimensionKey;
            """,
            params: [mutationId.uuidString]
        ) { stmt in
            DurablePendingExperienceV2Dimension(
                mutationId: mutationId,
                dimensionKey: String(cString: sqlite3_column_text(stmt, 1)),
                semanticStateCode: String(cString: sqlite3_column_text(stmt, 2)),
                templateVersion: Int(sqlite3_column_int(stmt, 3))
            )
        }
        return PendingExperienceV2MutationBundle(
            mutation: mutation,
            payload: payload,
            dimensions: dimensions,
            photos: try await getVisitPhotos(mutationId: mutationId)
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
        try await database.execute(
            "UPDATE conversation_entries SET syncState='FAILED' WHERE clientMutationId=?;",
            params: [mutationId.uuidString]
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
        try await database.execute(
            "UPDATE conversation_entries SET syncState='PENDING' WHERE userId=? AND clientMutationId=?;",
            params: [userId.uuidString, mutationId.uuidString]
        )
    }

    public func recoverFailedVisitForEditing(
        mutationId: UUID,
        userId: UUID,
        replaceExisting: Bool
    ) async throws -> RecoverFailedVisitResult {
        if let native = try await getExperienceV2Bundle(mutationId: mutationId) {
            return try await recoverFailedExperienceV2(
                native, mutationId: mutationId, userId: userId, replaceExisting: replaceExisting
            )
        }
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

    private func recoverFailedExperienceV2(
        _ bundle: PendingExperienceV2MutationBundle,
        mutationId: UUID,
        userId: UUID,
        replaceExisting: Bool
    ) async throws -> RecoverFailedVisitResult {
        guard bundle.mutation.userId == userId else { return .notOwner }
        guard bundle.mutation.state == .failedPermanent else { return .invalidState }
        let placeId = bundle.payload.placeId
        let existingCount = try await database.query(
                """
                SELECT COUNT(*) FROM visit_drafts
                WHERE userId = ? AND placeId = ? AND
                  (primaryExperienceCode IS NOT NULL OR overallFeelingCode IS NOT NULL OR
                   story <> '' OR tip <> '' OR privateMemory <> '' OR title IS NOT NULL);
                """,
                params: [userId.uuidString, placeId.uuidString],
                mapRow: { Int(sqlite3_column_int($0, 0)) }
           ).first ?? 0
        if !replaceExisting && existingCount > 0 {
            return .existingDraftConflict
        }
        let now = clock.nowMillis()
        return try await database.withTransaction { db in
            let p = bundle.payload
            try db.execute(
                """
                INSERT INTO visit_drafts (
                    userId,placeId,overallScore,publicReview,privateMemory,visitedAtEpochDay,
                    visibility,dimensionsExpanded,createdAtEpochMillis,updatedAtEpochMillis,
                    payloadVersion,primaryExperienceCode,rawExperienceLabel,overallFeelingCode,
                    companionCode,timeOfDayCode,vibeCodes,practicalSignalCodes,title,titleSource,story,tip,
                    originAcknowledgementId
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(userId,placeId) DO UPDATE SET
                    privateMemory=excluded.privateMemory,visitedAtEpochDay=excluded.visitedAtEpochDay,
                    visibility=excluded.visibility,dimensionsExpanded=excluded.dimensionsExpanded,
                    updatedAtEpochMillis=excluded.updatedAtEpochMillis,payloadVersion=2,
                    primaryExperienceCode=excluded.primaryExperienceCode,
                    rawExperienceLabel=excluded.rawExperienceLabel,
                    overallFeelingCode=excluded.overallFeelingCode,companionCode=excluded.companionCode,
                    timeOfDayCode=excluded.timeOfDayCode,vibeCodes=excluded.vibeCodes,
                    practicalSignalCodes=excluded.practicalSignalCodes,title=excluded.title,
                    titleSource=excluded.titleSource,story=excluded.story,tip=excluded.tip,
                    originAcknowledgementId=excluded.originAcknowledgementId;
                """,
                params: [
                    userId.uuidString, placeId.uuidString, 8.0, p.story, p.privateMemory,
                    p.visitedAtEpochDay, p.visibility, !bundle.dimensions.isEmpty, now, now, 2,
                    p.primaryExperienceCode, p.rawExperienceLabel, p.overallFeelingCode,
                    p.companionCode, p.timeOfDayCode, p.vibeCodes.sorted().joined(separator: ","),
                    p.practicalSignalCodes.sorted().joined(separator: ","), p.title,
                    p.titleSource, p.story, p.tip, p.originAcknowledgementId?.uuidString
                ]
            )
            try db.execute(
                "DELETE FROM visit_draft_dimension_scores WHERE userId = ? AND placeId = ?;",
                params: [userId.uuidString, placeId.uuidString]
            )
            for dimension in bundle.dimensions {
                let score = DimensionStateCode(rawValue: dimension.semanticStateCode)?.compatibilityScore ?? 0
                try db.execute(
                    """
                    INSERT INTO visit_draft_dimension_scores
                        (userId,placeId,dimensionKey,score,semanticStateCode,templateVersion)
                    VALUES (?,?,?,?,?,?);
                    """,
                    params: [
                        userId.uuidString, placeId.uuidString, dimension.dimensionKey,
                        Double(score), dimension.semanticStateCode, dimension.templateVersion
                    ]
                )
            }
            try db.execute(
                "DELETE FROM visit_draft_photos WHERE ownerUserId = ? AND placeId = ?;",
                params: [userId.uuidString, placeId.uuidString]
            )
            for photo in bundle.photos.sorted(by: { $0.position < $1.position }) {
                try db.execute(
                    """
                    INSERT INTO visit_draft_photos (
                        ownerUserId,placeId,position,clientMediaId,localRelativePath,contentType,
                        byteSize,width,height,remoteMediaId,uploadState,failureCategory
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?);
                    """,
                    params: [
                        userId.uuidString, placeId.uuidString, photo.position,
                        photo.clientMediaId.uuidString, photo.localRelativePath ?? "",
                        photo.contentType ?? "image/jpeg", photo.byteSize ?? 0,
                        photo.width, photo.height, photo.remoteMediaId?.uuidString,
                        photo.uploadState.rawValue, photo.failureCategory
                    ]
                )
            }
            guard try db.execute(
                "DELETE FROM pending_mutations WHERE mutationId = ? AND userId = ? AND state = 'FAILED_PERMANENT';",
                params: [mutationId.uuidString, userId.uuidString]
            ) == 1 else {
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
        let nativeMutationIds = try await database.query(
            "SELECT mutationId FROM pending_mutations WHERE userId = ? AND type = 'PUBLISH_EXPERIENCE_V2' ORDER BY createdAtEpochMillis DESC;",
            params: [userId.uuidString],
            mapRow: { UUID(uuidString: String(cString: sqlite3_column_text($0, 0))) }
        ).compactMap { $0 }
        for mutationId in nativeMutationIds {
            guard let bundle = try await getExperienceV2Bundle(mutationId: mutationId) else { continue }
            let rating: Double
            switch bundle.payload.overallFeelingCode {
            case "BAYILDIM": rating = 10
            case "GUZELDI": rating = 8
            case "EH_ISTE": rating = 6
            case "BEKLENTIMI_KARSILAMADI": rating = 4
            case "BIR_DAHA_TERCIH_ETMEM": rating = 2
            default: rating = 0
            }
            let dimensionScores = Dictionary(uniqueKeysWithValues: bundle.dimensions.map { row in
                (row.dimensionKey, Double(DimensionStateCode(rawValue: row.semanticStateCode)?.compatibilityScore ?? 0))
            })
            results.append(PendingVisit(
                mutationId: mutationId,
                placeId: bundle.payload.placeId,
                userId: bundle.mutation.userId,
                visitedAt: Date(timeIntervalSince1970: Double(bundle.payload.visitedAtEpochDay) * 86400),
                overallRating: rating,
                ratingDimensions: dimensionScores,
                review: bundle.payload.story,
                personalNote: bundle.payload.privateMemory,
                photos: bundle.photos.sorted(by: { $0.position < $1.position }).compactMap(\.localRelativePath),
                visibility: VisitVisibility(rawValue: bundle.payload.visibility) ?? .publicAccess,
                state: bundle.mutation.state,
                lastErrorCategory: bundle.mutation.lastErrorCategory
            ))
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

    private static func conversationBody(_ value: String) throws -> String {
        let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty, normalized.count <= 1_000 else {
            throw PersistenceError.conflict("Conversation body must be between 1 and 1000 characters")
        }
        return normalized
    }

    private static func insertConversationMutation(
        id: UUID, userId: UUID, type: MutationType, resourceKey: String,
        now: Int64, db: isolated PersistentDatabase
    ) throws {
        try db.execute(
            """
            INSERT INTO pending_mutations
            (mutationId,userId,type,resourceKey,state,generation,attemptCount,
             createdAtEpochMillis,updatedAtEpochMillis,lastErrorCategory,payloadVersion)
            VALUES (?,?,?,?,?,1,0,?,?,NULL,1);
            """,
            params: [id.uuidString, userId.uuidString, type.rawValue, resourceKey,
                     MutationState.pending.rawValue, now, now]
        )
    }

    private static func upsertConversation(
        _ entry: ConversationEntry, parentId: UUID?, userId: UUID,
        db: isolated PersistentDatabase
    ) throws {
        try db.execute(
            """
            INSERT INTO conversation_entries
            (userId,entryId,experienceId,parentEntryId,type,body,authorId,authorUsername,
             authorDisplayName,authorAvatarUrl,createdAt,updatedAt,edited,experienceAuthor,
             ownedByViewer,reportableByViewer,syncState,clientMutationId)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(userId,entryId) DO UPDATE SET
              parentEntryId=excluded.parentEntryId,type=excluded.type,body=excluded.body,
              authorId=excluded.authorId,authorUsername=excluded.authorUsername,
              authorDisplayName=excluded.authorDisplayName,authorAvatarUrl=excluded.authorAvatarUrl,
              createdAt=excluded.createdAt,updatedAt=excluded.updatedAt,edited=excluded.edited,
              experienceAuthor=excluded.experienceAuthor,ownedByViewer=excluded.ownedByViewer,
              reportableByViewer=excluded.reportableByViewer,syncState=excluded.syncState,
              clientMutationId=excluded.clientMutationId
            WHERE conversation_entries.syncState='SYNCED';
            """,
            params: [
                userId.uuidString, entry.id.uuidString, entry.experienceId.uuidString,
                parentId?.uuidString, entry.type.rawValue, entry.body, entry.author.id.uuidString,
                entry.author.username, entry.author.displayName, entry.author.avatarUrl,
                entry.createdAt, entry.updatedAt, entry.edited, entry.experienceAuthor,
                entry.ownedByViewer, entry.reportableByViewer, entry.syncState.rawValue,
                entry.clientMutationId?.uuidString
            ]
        )
    }
}
