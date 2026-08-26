import Foundation

protocol SessionStore: Sendable {
    func load() async -> PersistedSession?
    func save(_ session: PersistedSession) async throws
    func updateTokens(_ tokens: TokenPair) async throws
    func clear() async throws
}

actor InMemorySessionStore: SessionStore {
    private var session: PersistedSession?

    init(session: PersistedSession? = nil) {
        self.session = session
    }

    func load() async -> PersistedSession? {
        session
    }

    func save(_ session: PersistedSession) async throws {
        self.session = session
    }

    func updateTokens(_ tokens: TokenPair) async throws {
        guard var current = session else { return }
        current.tokens = tokens
        session = current
    }

    func clear() async throws {
        session = nil
    }
}
