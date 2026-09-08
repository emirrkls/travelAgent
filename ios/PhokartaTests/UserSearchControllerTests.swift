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
        await controller.search(query: "li")

        // Alice is the current user; she must be filtered out!
        XCTAssertEqual(controller.results.count, 1)
        XCTAssertEqual(controller.results.first?.username, "charlie")
        XCTAssertEqual(controller.phase, .content)
    }

    func testEmptyQueryClearsResults() async throws {
        let bobSummary = SocialTestFixtures.userSummary(id: bobID, username: "bob", displayName: "Bob")
        await service.setSearchResults([bobSummary])

        let controller = UserSearchController(service: service, store: store)
        await controller.search(query: "bob")
        XCTAssertEqual(controller.results.count, 1)

        // Clear query
        await controller.search(query: "")
        XCTAssertTrue(controller.results.isEmpty)
        XCTAssertEqual(controller.phase, .empty)
    }

    func testQueryRaceDiscardsStaleSearchResponse() async throws {
        let gate = TestGate()
        await service.setGate(gate)

        let bobSummary = SocialTestFixtures.userSummary(id: bobID, username: "bob", displayName: "Bob")
        let charlieSummary = SocialTestFixtures.userSummary(id: charlieID, username: "charlie", displayName: "Charlie")
        await service.setSearchResults([bobSummary, charlieSummary])

        let controller = UserSearchController(service: service, store: store)

        // Query 1: "bob" (will wait at gate)
        let t1 = Task { await controller.search(query: "bob") }

        // Query 2: "charlie" immediately overrides
        let t2 = Task { await controller.search(query: "charlie") }

        // Open gate
        await gate.open()
        await t1.value
        await t2.value

        // Controller final query should be "charlie" and results should contain Charlie
        XCTAssertEqual(controller.query, "charlie")
        XCTAssertEqual(controller.results.first?.username, "charlie")
    }

    func testNoResultsShowsEmptyPhase() async throws {
        await service.setSearchResults([])

        let controller = UserSearchController(service: service, store: store)
        await controller.search(query: "nonexistent")

        XCTAssertTrue(controller.results.isEmpty)
        XCTAssertEqual(controller.phase, .empty)
    }
}

private extension MockSocialService {
    func setSearchResults(_ results: [UserSummary]) {
        self.searchResults = results
    }
    func setGate(_ gate: TestGate) {
        self.gateBeforeResponse = gate
    }
}
