import XCTest
@testable import Phokarta

final class AuthFieldValidatorTests: XCTestCase {
    func testUsernameRulesMatchBackend() {
        XCTAssertTrue(AuthFieldValidator.username("abc"))
        XCTAssertTrue(AuthFieldValidator.username("Emir_1"))
        XCTAssertFalse(AuthFieldValidator.username("ab"))
        XCTAssertFalse(AuthFieldValidator.username("has space"))
        XCTAssertFalse(AuthFieldValidator.username("bad-name"))
    }

    func testPasswordLength() {
        XCTAssertFalse(AuthFieldValidator.password("1234567"))
        XCTAssertTrue(AuthFieldValidator.password("12345678"))
        XCTAssertFalse(AuthFieldValidator.password(String(repeating: "a", count: 73)))
    }

    func testEmailAndDisplayName() {
        XCTAssertTrue(AuthFieldValidator.email("a@b.co"))
        XCTAssertFalse(AuthFieldValidator.email("not-an-email"))
        XCTAssertTrue(AuthFieldValidator.displayName("Traveler"))
        XCTAssertFalse(AuthFieldValidator.displayName("   "))
    }
}
