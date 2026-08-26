import XCTest
@testable import Phokarta

final class APIErrorDecodingTests: XCTestCase {
    func testDecodesBackendErrorContract() throws {
        let data = TestJSON.apiError(
            status: 401,
            code: "INVALID_CREDENTIALS",
            message: "Invalid email/username or password",
            fieldErrors: "{\"password\":\"must not be blank\"}"
        )
        let dto = try APIJSON.decoder.decode(APIErrorDTO.self, from: data)
        XCTAssertEqual(dto.status, 401)
        XCTAssertEqual(dto.code, "INVALID_CREDENTIALS")
        XCTAssertEqual(dto.requestId, "11111111-1111-1111-1111-111111111111")
        XCTAssertEqual(dto.fieldErrors["password"], "must not be blank")
    }

    func testMapsStableAuthCodes() {
        XCTAssertEqual(
            APIErrorMapper.map(status: 401, dto: dto(code: "INVALID_CREDENTIALS", status: 401)),
            .invalidCredentials
        )
        XCTAssertEqual(
            APIErrorMapper.map(status: 401, dto: dto(code: "INVALID_REFRESH_TOKEN", status: 401)),
            .unauthorized
        )
        XCTAssertEqual(
            APIErrorMapper.map(status: 401, dto: dto(code: "TOKEN_EXPIRED", status: 401)),
            .unauthorized
        )
        XCTAssertEqual(
            APIErrorMapper.map(status: 409, dto: dto(code: "EMAIL_ALREADY_EXISTS", status: 409)),
            .duplicateEmail
        )
        XCTAssertEqual(
            APIErrorMapper.map(status: 409, dto: dto(code: "USERNAME_ALREADY_EXISTS", status: 409)),
            .duplicateUsername
        )
        XCTAssertEqual(
            APIErrorMapper.map(status: 429, dto: dto(code: "RATE_LIMITED", status: 429)),
            .rateLimited
        )
        XCTAssertEqual(
            APIErrorMapper.map(status: 400, dto: dto(code: "VALIDATION_ERROR", status: 400)),
            .validation(message: "error", fields: [:])
        )
        XCTAssertEqual(APIErrorMapper.map(status: 503, dto: dto(code: "INTERNAL_ERROR", status: 503)), .server)
    }

    func testTransientClassification() {
        XCTAssertTrue(AppError.networkUnavailable.isTransient)
        XCTAssertTrue(AppError.timeout.isTransient)
        XCTAssertTrue(AppError.server.isTransient)
        XCTAssertTrue(AppError.rateLimited.isTransient)
        XCTAssertFalse(AppError.unauthorized.isTransient)
        XCTAssertTrue(AppError.unauthorized.isTerminalAuth)
        XCTAssertFalse(AppError.networkUnavailable.isTerminalAuth)
    }

    private func dto(code: String, status: Int) -> APIErrorDTO {
        APIErrorDTO(status: status, code: code, message: "error")
    }
}
