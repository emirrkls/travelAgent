import Foundation

enum ExplorePhase: Equatable, Sendable {
    case idle
    case loading
    case content
    case empty(ExploreEmptyReason)
    case error(ExploreErrorKind)
}

enum ExploreEmptyReason: Equatable, Sendable {
    case catalog
    case search
    case category
}

enum ExploreErrorKind: Equatable, Sendable {
    case network
    case rateLimited
    case server
    case unauthorized

    var showsRetry: Bool {
        switch self {
        case .unauthorized:
            return false
        case .network, .rateLimited, .server:
            return true
        }
    }

    var localizedMessage: String {
        switch self {
        case .network:
            String(localized: "error.offline")
        case .rateLimited:
            String(localized: "error.rate_limited")
        case .server:
            String(localized: "error.server")
        case .unauthorized:
            String(localized: "error.session_expired")
        }
    }

    static func from(_ error: AppError) -> ExploreErrorKind {
        switch error {
        case .networkUnavailable, .timeout:
            return .network
        case .rateLimited:
            return .rateLimited
        case .unauthorized, .invalidCredentials:
            return .unauthorized
        default:
            return .server
        }
    }
}

struct ExploreState: Equatable, Sendable {
    var phase: ExplorePhase = .idle
    var query: String = ""
    var selectedCategory: PlaceCategory?
    var places: [ExplorePlaceItem] = []
    var hasNext: Bool = false
    var page: Int = 0
    var isLoadingMore: Bool = false
    var refreshError: ExploreErrorKind?
}
