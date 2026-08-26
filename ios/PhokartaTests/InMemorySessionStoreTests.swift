import XCTest
@testable import Phokarta

final class InMemorySessionStoreTests: XCTestCase {
    func testSaveLoadUpdateAndClear() async throws {
        let store = InMemorySessionStore()
        XCTAssertNil(await store.load())

        let original = testSession()
        try await store.save(original)
        XCTAssertEqual(await store.load(), original)

        let rotated = TokenPair(
            accessToken: "access-2",
            refreshToken: "refresh-token-bbbbbbbb",
            tokenType: "Bearer",
            expiresIn: 900,
            accessTokenExpiresAt: "2026-08-26T16:39:00Z"
        )
        try await store.updateTokens(rotated)
        let loaded = try XCTUnwrap(await store.load())
        XCTAssertEqual(loaded.tokens.accessToken, "access-2")
        XCTAssertEqual(loaded.tokens.refreshToken, "refresh-token-bbbbbbbb")
        XCTAssertEqual(loaded.user.id, original.user.id)

        try await store.clear()
        XCTAssertNil(await store.load())
    }

    func testDoesNotPersistPasswordMaterial() async throws {
        let store = InMemorySessionStore()
        try await store.save(testSession())
        let encoded = try APIJSON.encoder.encode(try XCTUnwrap(await store.load()))
        let json = String(decoding: encoded, as: UTF8.self)
        XCTAssertFalse(json.contains("password"))
        XCTAssertTrue(json.contains("accessToken"))
        XCTAssertTrue(json.contains("refreshToken"))
    }
}
