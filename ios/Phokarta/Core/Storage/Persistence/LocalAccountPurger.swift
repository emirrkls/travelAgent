import Foundation

public protocol LocalAccountPurger: Sendable {
    func purgeLocalData(userId: UUID) async throws
}

public final class SQLiteLocalAccountPurger: LocalAccountPurger, Sendable {
    private let database: PersistentDatabase
    private let mediaStore: (any DurableMediaStoring)?

    public init(database: PersistentDatabase, mediaStore: (any DurableMediaStoring)? = nil) {
        self.database = database
        self.mediaStore = mediaStore
    }

    public func purgeLocalData(userId: UUID) async throws {
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
