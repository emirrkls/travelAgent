import XCTest
@testable import Phokarta

final class InMemorySessionStoreTests: XCTestCase {
    func testSaveLoadUpdateAndClear() async throws {
        let store = InMemorySessionStore()
        let emptySession = await store.load()
        XCTAssertNil(emptySession)

        let original = testSession()
        try await store.save(original)
        let savedSession = await store.load()
        XCTAssertEqual(savedSession, original)

        let rotated = TokenPair(
            accessToken: "access-2",
            refreshToken: "refresh-token-bbbbbbbb",
            tokenType: "Bearer",
            expiresIn: 900,
            accessTokenExpiresAt: "2026-08-26T16:39:00Z"
        )
        try await store.updateTokens(rotated)
        let updatedSession = await store.load()
        let loaded = try XCTUnwrap(updatedSession)
        XCTAssertEqual(loaded.tokens.accessToken, "access-2")
        XCTAssertEqual(loaded.tokens.refreshToken, "refresh-token-bbbbbbbb")
        XCTAssertEqual(loaded.user.id, original.user.id)

        try await store.clear()
        let clearedSession = await store.load()
        XCTAssertNil(clearedSession)
    }

    func testDoesNotPersistPasswordMaterial() async throws {
        let store = InMemorySessionStore()
        try await store.save(testSession())
        let persistedSession = await store.load()
        let encoded = try APIJSON.encoder.encode(try XCTUnwrap(persistedSession))
        let json = String(decoding: encoded, as: UTF8.self)
        XCTAssertFalse(json.contains("password"))
        XCTAssertTrue(json.contains("accessToken"))
        XCTAssertTrue(json.contains("refreshToken"))
    }
}
