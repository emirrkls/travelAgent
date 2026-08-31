import Foundation

enum APIJSON {
    static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.keyEncodingStrategy = .useDefaultKeys
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }()

    static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .useDefaultKeys
        return decoder
    }()
}

struct APIErrorDTO: Decodable, Equatable, Sendable {
    let timestamp: String?
    let status: Int
    let code: String
    let message: String
    let path: String?
    let requestId: String?
    let fieldErrors: [String: String]
    let requiredVersion: String?

    init(
        timestamp: String? = nil,
        status: Int,
        code: String,
        message: String,
        path: String? = nil,
        requestId: String? = nil,
        fieldErrors: [String: String] = [:],
        requiredVersion: String? = nil
    ) {
        self.timestamp = timestamp
        self.status = status
        self.code = code
        self.message = message
        self.path = path
        self.requestId = requestId
        self.fieldErrors = fieldErrors
        self.requiredVersion = requiredVersion
    }

    private enum CodingKeys: String, CodingKey {
        case timestamp, status, code, message, path, requestId, fieldErrors, requiredVersion
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        timestamp = try container.decodeIfPresent(String.self, forKey: .timestamp)
        status = try container.decode(Int.self, forKey: .status)
        code = try container.decode(String.self, forKey: .code)
        message = try container.decode(String.self, forKey: .message)
        path = try container.decodeIfPresent(String.self, forKey: .path)
        requestId = try container.decodeIfPresent(String.self, forKey: .requestId)
        fieldErrors = try container.decodeIfPresent([String: String].self, forKey: .fieldErrors) ?? [:]
        requiredVersion = try container.decodeIfPresent(String.self, forKey: .requiredVersion)
    }
}

enum APIErrorMapper {
    static func map(status: Int, dto: APIErrorDTO?) -> AppError {
        let code = dto?.code
        switch status {
        case 400, 422:
            return .validation(message: dto?.message ?? "", fields: dto?.fieldErrors ?? [:])
        case 401:
            switch code {
            case "INVALID_CREDENTIALS":
                return .invalidCredentials
            case "INVALID_REFRESH_TOKEN", "TOKEN_EXPIRED", "UNAUTHORIZED", .none:
                return .unauthorized
            default:
                return .unauthorized
            }
        case 403:
            if code == "POLICY_ACCEPTANCE_REQUIRED" {
                return .policyAcceptanceRequired(requiredVersion: dto?.requiredVersion)
            }
            return .forbidden
        case 404:
            return .notFound
        case 409:
            switch code {
            case "EMAIL_ALREADY_EXISTS":
                return .duplicateEmail
            case "USERNAME_ALREADY_EXISTS":
                return .duplicateUsername
            default:
                return .conflict(code: code ?? "CONFLICT")
            }
        case 429:
            return .rateLimited
        case 500...599:
            return .server
        default:
            return .unknown(status: status, code: code)
        }
    }

    static func decodeErrorBody(_ data: Data) -> APIErrorDTO? {
        try? APIJSON.decoder.decode(APIErrorDTO.self, from: data)
    }
}
