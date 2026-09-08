import XCTest
@testable import Phokarta

@MainActor
final class UserSearchControllerTests: XCTestCase {
    var service: MockSocialService!
    var store: SocialStateStore!
    let currentUserID = SocialTestFixtures.aliceID
    let bobID = SocialTestFixtures.bobID
    let charlieID = SocialTestFixtures.charlieID

    override func setUp() async throws {
        service = MockSocialService()
        store = SocialStateStore(service: service)
        store.activate(accountID: currentUserID)
    }

    func testSearchReturnsResultsExcludingSelf() async throws {
        let aliceSummary = SocialTestFixtures.userSummary(id: currentUserID, username: "alice", displayName: "Alice")
        let bobSummary = SocialTestFixtures.userSummary(id: bobID, username: "bob", displayName: "Bob")
        let charlieSummary = SocialTestFixtures.userSummary(id: charlieID, username: "charlie", displayName: "Charlie")

        await service.setSearchResults([aliceSummary, bobSummary, charlieSummary])

        let controller = UserSearchController(service: service, store: store)
        controller.searchImmediate("li")

        // Wait a tick for the search task
        try? await Task.sleep(nanoseconds: 50_000_000)

        // Alice is the current user; she must be filtered out!
        XCTAssertEqual(controller.items.count, 1)
        XCTAssertEqual(controller.items.first?.username, "charlie")
    }

    func testEmptyQueryClearsResults() async throws {
        let bobSummary = SocialTestFixtures.userSummary(id: bobID, username: "bob", displayName: "Bob")
        await service.setSearchResults([bobSummary])

        let controller = UserSearchController(service: service, store: store)
        controller.searchImmediate("bob")
        try? await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertEqual(controller.items.count, 1)

        // Clear query
        controller.setQuery("")
        XCTAssertTrue(controller.items.isEmpty)
    }
}

private extension MockSocialService {
    func setSearchResults(_ results: [UserSummary]) {
        self.searchResults = results
    }
}
