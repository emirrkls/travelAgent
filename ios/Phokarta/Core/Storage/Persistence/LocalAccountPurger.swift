import Foundation

protocol LocalAccountPurger: Sendable {
    func purgeLocalData(userId: UUID) async throws
}

final class SQLiteLocalAccountPurger: LocalAccountPurger, Sendable {
    private let database: PersistentDatabase
    private let mediaStore: (any DurableMediaStoring)?
    private let mediaLock: MediaFileMutationLock

    init(
        database: PersistentDatabase,
        mediaStore: (any DurableMediaStoring)? = nil,
        mediaLock: MediaFileMutationLock = .shared
    ) {
        self.database = database
        self.mediaStore = mediaStore
        self.mediaLock = mediaLock
    }

    func purgeLocalData(userId: UUID) async throws {
        try await mediaLock.withLock {
            try await database.withTransaction { db in
                try db.execute(
                    "DELETE FROM visit_drafts WHERE userId = ?;",
                    params: [userId.uuidString]
                )
                try db.execute(
                    "DELETE FROM pending_mutations WHERE userId = ?;",
                    params: [userId.uuidString]
                )
                try db.execute("DELETE FROM conversation_entries WHERE userId = ?;", params: [userId.uuidString])
                try db.execute("DELETE FROM planned_experiences WHERE userId = ?;", params: [userId.uuidString])
                try db.execute("DELETE FROM experience_acknowledgements WHERE userId = ?;", params: [userId.uuidString])
            }
            if let mediaStore {
                await mediaStore.deleteAllOwned(userId: userId)
            }
        }
    }
}
