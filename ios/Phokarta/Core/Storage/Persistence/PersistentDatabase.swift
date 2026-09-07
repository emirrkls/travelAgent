import Foundation
import SQLite3

public enum PersistenceError: Error, LocalizedError {
    case databaseOpenFailed(String)
    case executionFailed(String)
    case transactionFailed(String)
    case recordNotFound
    case conflict(String)

    public var errorDescription: String? {
        switch self {
        case .databaseOpenFailed(let msg): return "Database open failed: \(msg)"
        case .executionFailed(let msg): return "SQLite execution failed: \(msg)"
        case .transactionFailed(let msg): return "SQLite transaction failed: \(msg)"
        case .recordNotFound: return "Record not found"
        case .conflict(let msg): return "Database conflict: \(msg)"
        }
    }
}

public actor PersistentDatabase {
    private var db: OpaquePointer?
    private let path: String?

    public init(path: String? = nil) throws {
        self.path = path
        let openPath = path ?? ":memory:"
        var handle: OpaquePointer?
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        if sqlite3_open_v2(openPath, &handle, flags, nil) != SQLITE_OK {
            let errorMsg = handle.flatMap { String(cString: sqlite3_errmsg($0)) } ?? "Unknown error"
            sqlite3_close(handle)
            throw PersistenceError.databaseOpenFailed(errorMsg)
        }
        self.db = handle

        // Apply PRAGMAs
        try Self.executeRaw(handle, "PRAGMA foreign_keys = ON;")
        if path != nil && path != ":memory:" {
            try Self.executeRaw(handle, "PRAGMA journal_mode = WAL;")
            try Self.executeRaw(handle, "PRAGMA synchronous = NORMAL;")
        }

        try Self.migrateSchema(handle)
    }

    deinit {
        if let handle = db {
            sqlite3_close(handle)
        }
    }

    public func close() {
        if let handle = db {
            sqlite3_close(handle)
            db = nil
        }
    }

    public func isOpen() -> Bool {
        db != nil
    }

    // MARK: - Transaction Management

    public func withTransaction<T>(_ block: (isolated PersistentDatabase) throws -> T) throws -> T {
        guard let handle = db else { throw PersistenceError.executionFailed("Database closed") }
        try Self.executeRaw(handle, "BEGIN IMMEDIATE;")
        do {
            let result = try block(self)
            try Self.executeRaw(handle, "COMMIT;")
            return result
        } catch {
            try? Self.executeRaw(handle, "ROLLBACK;")
            throw error
        }
    }

    // MARK: - Raw Execution & Queries

    @discardableResult
    public func execute(_ sql: String, params: [Any?] = []) throws -> Int {
        guard let handle = db else { throw PersistenceError.executionFailed("Database closed") }
        var statement: OpaquePointer?
        if sqlite3_prepare_v2(handle, sql, -1, &statement, nil) != SQLITE_OK {
            let msg = String(cString: sqlite3_errmsg(handle))
            throw PersistenceError.executionFailed("Prepare failed: \(msg) [SQL: \(sql)]")
        }
        defer { sqlite3_finalize(statement) }

        try Self.bindParams(statement, params: params)

        let stepResult = sqlite3_step(statement)
        if stepResult != SQLITE_DONE && stepResult != SQLITE_ROW {
            let msg = String(cString: sqlite3_errmsg(handle))
            throw PersistenceError.executionFailed("Step failed: \(msg) [SQL: \(sql)]")
        }
        return Int(sqlite3_changes(handle))
    }

    public func query<T>(_ sql: String, params: [Any?] = [], mapRow: (OpaquePointer) throws -> T) throws -> [T] {
        guard let handle = db else { throw PersistenceError.executionFailed("Database closed") }
        var statement: OpaquePointer?
        if sqlite3_prepare_v2(handle, sql, -1, &statement, nil) != SQLITE_OK {
            let msg = String(cString: sqlite3_errmsg(handle))
            throw PersistenceError.executionFailed("Prepare failed: \(msg) [SQL: \(sql)]")
        }
        defer { sqlite3_finalize(statement) }

        try Self.bindParams(statement, params: params)

        var rows: [T] = []
        while sqlite3_step(statement) == SQLITE_ROW {
            if let stmt = statement {
                rows.append(try mapRow(stmt))
            }
        }
        return rows
    }

    // MARK: - Schema Migration

    private static func migrateSchema(_ handle: OpaquePointer?) throws {
        let version = try userVersion(handle)
        if version < 1 {
            try executeRaw(handle, """
            CREATE TABLE IF NOT EXISTS visit_drafts (
                userId TEXT NOT NULL,
                placeId TEXT NOT NULL,
                overallScore REAL NOT NULL,
                publicReview TEXT NOT NULL,
                privateMemory TEXT NOT NULL,
                visitedAtEpochDay INTEGER NOT NULL,
                visibility TEXT NOT NULL,
                dimensionsExpanded INTEGER NOT NULL,
                createdAtEpochMillis INTEGER NOT NULL,
                updatedAtEpochMillis INTEGER NOT NULL,
                PRIMARY KEY (userId, placeId)
            );
            """)

            try executeRaw(handle, """
            CREATE INDEX IF NOT EXISTS idx_visit_drafts_updated
            ON visit_drafts(updatedAtEpochMillis);
            """)

            try executeRaw(handle, """
            CREATE TABLE IF NOT EXISTS visit_draft_dimension_scores (
                userId TEXT NOT NULL,
                placeId TEXT NOT NULL,
                dimensionKey TEXT NOT NULL,
                score REAL NOT NULL,
                PRIMARY KEY (userId, placeId, dimensionKey),
                FOREIGN KEY (userId, placeId) REFERENCES visit_drafts(userId, placeId) ON DELETE CASCADE
            );
            """)

            try executeRaw(handle, """
            CREATE TABLE IF NOT EXISTS visit_draft_photos (
                ownerUserId TEXT NOT NULL,
                placeId TEXT NOT NULL,
                position INTEGER NOT NULL,
                clientMediaId TEXT NOT NULL UNIQUE,
                localRelativePath TEXT NOT NULL,
                contentType TEXT NOT NULL,
                byteSize INTEGER NOT NULL,
                width INTEGER,
                height INTEGER,
                remoteMediaId TEXT,
                uploadState TEXT NOT NULL,
                failureCategory TEXT,
                PRIMARY KEY (ownerUserId, placeId, position),
                FOREIGN KEY (ownerUserId, placeId) REFERENCES visit_drafts(userId, placeId) ON DELETE CASCADE
            );
            """)

            try executeRaw(handle, """
            CREATE INDEX IF NOT EXISTS idx_draft_photos_owner_place
            ON visit_draft_photos(ownerUserId, placeId);
            """)

            try executeRaw(handle, """
            CREATE TABLE IF NOT EXISTS pending_mutations (
                mutationId TEXT PRIMARY KEY,
                userId TEXT NOT NULL,
                type TEXT NOT NULL,
                resourceKey TEXT NOT NULL,
                state TEXT NOT NULL,
                generation INTEGER NOT NULL,
                attemptCount INTEGER NOT NULL,
                createdAtEpochMillis INTEGER NOT NULL,
                updatedAtEpochMillis INTEGER NOT NULL,
                lastErrorCategory TEXT
            );
            """)

            try executeRaw(handle, """
            CREATE INDEX IF NOT EXISTS idx_pending_mutations_user_state
            ON pending_mutations(userId, state, createdAtEpochMillis);
            """)

            try executeRaw(handle, """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_pending_mutations_user_type_resource
            ON pending_mutations(userId, type, resourceKey);
            """)

            try executeRaw(handle, """
            CREATE TABLE IF NOT EXISTS pending_visit_payloads (
                mutationId TEXT PRIMARY KEY,
                placeId TEXT NOT NULL,
                visitedAtEpochDay INTEGER NOT NULL,
                overallRating REAL NOT NULL,
                publicReview TEXT NOT NULL,
                privateMemory TEXT NOT NULL,
                visibility TEXT NOT NULL,
                FOREIGN KEY (mutationId) REFERENCES pending_mutations(mutationId) ON DELETE CASCADE
            );
            """)

            try executeRaw(handle, """
            CREATE TABLE IF NOT EXISTS pending_visit_dimension_scores (
                mutationId TEXT NOT NULL,
                dimensionKey TEXT NOT NULL,
                score REAL NOT NULL,
                PRIMARY KEY (mutationId, dimensionKey),
                FOREIGN KEY (mutationId) REFERENCES pending_mutations(mutationId) ON DELETE CASCADE
            );
            """)

            try executeRaw(handle, """
            CREATE INDEX IF NOT EXISTS idx_pending_scores_mutation
            ON pending_visit_dimension_scores(mutationId);
            """)

            try executeRaw(handle, """
            CREATE TABLE IF NOT EXISTS pending_visit_photos (
                mutationId TEXT NOT NULL,
                position INTEGER NOT NULL,
                ownerUserId TEXT NOT NULL,
                clientMediaId TEXT NOT NULL,
                localRelativePath TEXT,
                contentType TEXT,
                byteSize INTEGER,
                width INTEGER,
                height INTEGER,
                remoteMediaId TEXT,
                uploadState TEXT NOT NULL,
                failureCategory TEXT,
                PRIMARY KEY (mutationId, position),
                FOREIGN KEY (mutationId) REFERENCES pending_mutations(mutationId) ON DELETE CASCADE
            );
            """)

            try executeRaw(handle, """
            CREATE INDEX IF NOT EXISTS idx_pending_photos_mutation
            ON pending_visit_photos(mutationId);
            """)

            try executeRaw(handle, "PRAGMA user_version = 1;")
        }
    }

    private static func userVersion(_ handle: OpaquePointer?) throws -> Int {
        var statement: OpaquePointer?
        if sqlite3_prepare_v2(handle, "PRAGMA user_version;", -1, &statement, nil) != SQLITE_OK {
            let msg = String(cString: sqlite3_errmsg(handle))
            throw PersistenceError.executionFailed("Failed to read user_version: \(msg)")
        }
        defer { sqlite3_finalize(statement) }
        if sqlite3_step(statement) == SQLITE_ROW {
            return Int(sqlite3_column_int(statement, 0))
        }
        return 0
    }

    private static func executeRaw(_ handle: OpaquePointer?, _ sql: String) throws {
        var err: UnsafeMutablePointer<CChar>?
        if sqlite3_exec(handle, sql, nil, nil, &err) != SQLITE_OK {
            let message = err.flatMap { String(cString: $0) } ?? "Unknown error"
            sqlite3_free(err)
            throw PersistenceError.executionFailed("\(message) [SQL: \(sql)]")
        }
    }

    private static func bindParams(_ statement: OpaquePointer?, params: [Any?]) throws {
        for (index, param) in params.enumerated() {
            let colIndex = Int32(index + 1)
            guard let value = param else {
                sqlite3_bind_null(statement, colIndex)
                continue
            }
            if let string = value as? String {
                sqlite3_bind_text(statement, colIndex, (string as NSString).utf8String, -1, nil)
            } else if let int = value as? Int {
                sqlite3_bind_int64(statement, colIndex, Int64(int))
            } else if let int64 = value as? Int64 {
                sqlite3_bind_int64(statement, colIndex, int64)
            } else if let double = value as? Double {
                sqlite3_bind_double(statement, colIndex, double)
            } else if let bool = value as? Bool {
                sqlite3_bind_int(statement, colIndex, bool ? 1 : 0)
            } else if let uuid = value as? UUID {
                sqlite3_bind_text(statement, colIndex, (uuid.uuidString as NSString).utf8String, -1, nil)
            } else {
                let str = String(describing: value)
                sqlite3_bind_text(statement, colIndex, (str as NSString).utf8String, -1, nil)
            }
        }
    }
}
