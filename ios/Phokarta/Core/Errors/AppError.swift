import Foundation

enum AppError: Error, Equatable, Sendable {
    case networkUnavailable
    case timeout
    case cancelled
    case invalidCredentials
    case duplicateEmail
    case duplicateUsername
    case validation(message: String, fields: [String: String])
    case rateLimited
    case unauthorized
    case forbidden
    case policyAcceptanceRequired(requiredVersion: String?)
    case notFound
    case conflict(code: String)
    case server
    case placeholderAPI
    case invalidConfiguration(String)
    case decoding
    case unknown(status: Int?, code: String?)

    var isTransient: Bool {
        switch self {
        case .networkUnavailable, .timeout, .server, .rateLimited:
            return true
        case .unknown(let status, _):
            return status == nil || status == 408 || status == 429
        default:
            return false
        }
    }

    var isTerminalAuth: Bool {
        switch self {
        case .unauthorized, .invalidCredentials, .notFound:
            return true
        default:
            return false
        }
    }
}

extension AppError: LocalizedError {
    var errorDescription: String? {
        localizedMessage
    }

    var localizedMessage: String {
        switch self {
        case .networkUnavailable:
            String(localized: "error.offline")
        case .timeout:
            String(localized: "error.timeout")
        case .cancelled:
            String(localized: "error.unknown")
        case .invalidCredentials:
            String(localized: "error.invalid_credentials")
        case .duplicateEmail:
            String(localized: "error.duplicate_email")
        case .duplicateUsername:
            String(localized: "error.duplicate_username")
        case .validation:
            String(localized: "error.validation")
        case .rateLimited:
            String(localized: "error.rate_limited")
        case .unauthorized:
            String(localized: "error.session_expired")
        case .forbidden:
            String(localized: "error.forbidden")
        case .policyAcceptanceRequired:
            String(localized: "error.policy_acceptance_required")
        case .notFound:
            String(localized: "error.not_found")
        case .conflict:
            String(localized: "error.conflict")
        case .server:
            String(localized: "error.server")
        case .placeholderAPI:
            String(localized: "error.placeholder_api")
        case .invalidConfiguration:
            String(localized: "error.invalid_configuration")
        case .decoding:
            String(localized: "error.unknown")
        case .unknown:
            String(localized: "error.unknown")
        }
    }

    func localizedMutationMessage(fallbackKey: String) -> String {
        switch self {
        case .server, .decoding, .unknown:
            String(localized: String.LocalizationValue(fallbackKey))
        default:
            localizedMessage
        }
    }
}
