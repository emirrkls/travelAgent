import XCTest
@testable import Phokarta

final class AppConfigTests: XCTestCase {
    func testParsesAbsoluteHTTPSAndNormalizesTrailingSlash() throws {
        let config = try AppConfig.parse(
            baseURLString: "https://api.example.test",
            allowsInsecureHTTP: false
        )
        XCTAssertEqual(config.apiBaseURL.absoluteString, "https://api.example.test/")
        XCTAssertFalse(config.allowsInsecureHTTP)
        XCTAssertFalse(config.isPlaceholderHost)
    }

    func testReleaseRejectsHTTP() {
        XCTAssertThrowsError(
            try AppConfig.parse(baseURLString: "http://127.0.0.1:8080/", allowsInsecureHTTP: false)
        ) { error in
            XCTAssertEqual(error as? AppConfigError, .insecureURLNotAllowed("http://127.0.0.1:8080/"))
        }
    }

    func testDebugAllowsHTTP() throws {
        let config = try AppConfig.parse(
            baseURLString: "http://127.0.0.1:8080",
            allowsInsecureHTTP: true
        )
        XCTAssertEqual(config.apiBaseURL.absoluteString, "http://127.0.0.1:8080/")
        XCTAssertEqual(config.apiBaseURL.scheme, "http")
    }

    func testRejectsEmptyAndInvalidURLs() {
        XCTAssertThrowsError(try AppConfig.parse(baseURLString: "", allowsInsecureHTTP: true)) { error in
            XCTAssertEqual(error as? AppConfigError, .missingBaseURL)
        }
        XCTAssertThrowsError(try AppConfig.parse(baseURLString: "not-a-url", allowsInsecureHTTP: true)) { error in
            guard case AppConfigError.invalidURL = error as! AppConfigError else {
                return XCTFail("expected invalidURL")
            }
        }
        XCTAssertThrowsError(try AppConfig.parse(baseURLString: "https://", allowsInsecureHTTP: false))
    }

    func testPlaceholderInvalidTLDIsDetectedAndStillParses() throws {
        let config = try AppConfig.parse(
            baseURLString: "https://api.phokarta.invalid/",
            allowsInsecureHTTP: false
        )
        XCTAssertTrue(config.isPlaceholderHost)
        XCTAssertEqual(config.apiBaseURL.scheme, "https")
    }

    func testDoesNotFallBackToLocalhost() {
        XCTAssertThrowsError(
            try AppConfig.parse(baseURLString: "   ", allowsInsecureHTTP: false)
        )
    }
}
