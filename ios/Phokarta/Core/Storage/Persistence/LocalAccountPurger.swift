import Foundation

protocol LocalAccountPurger: Sendable {
    func purgeLocalData(userId: UUID) async throws
}

final class SQLiteLocalAccountPurger: LocalAccountPurger, Sendable {
    private let database: PersistentDatabase
    private let mediaStore: (any DurableMediaStoring)?

    init(database: PersistentDatabase, mediaStore: (any DurableMediaStoring)? = nil) {
        self.database = database
        self.mediaStore = mediaStore
    }

    func purgeLocalData(userId: UUID) async throws {
        try await database.withTransaction { db in
            try db.execute(
                "DELETE FROM visit_drafts WHERE userId = ?;",
                params: [userId.uuidString]
            )
            try db.execute(
                "DELETE FROM pending_mutations WHERE userId = ?;",
                params: [userId.uuidString]
            )
        }
        if let mediaStore {
            await mediaStore.deleteAllOwned(userId: userId)
        }
    }
}
