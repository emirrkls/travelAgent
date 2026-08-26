import Foundation

struct PlaceListEndpoint: APIEndpoint {
    typealias Response = PageDTO<PlaceSummary>

    let category: PlaceCategory?
    let search: String?
    let page: Int
    let size: Int

    var method: HTTPMethod { .get }
    var path: String { "api/v1/places" }
    var requiresAuthentication: Bool { true }

    var queryItems: [URLQueryItem] {
        var items = [
            URLQueryItem(name: "sort", value: "averageScore,desc"),
            URLQueryItem(name: "page", value: String(page)),
            URLQueryItem(name: "size", value: String(size)),
        ]
        if let category, let wire = category.wireValue {
            items.append(URLQueryItem(name: "category", value: wire))
        }
        if let search, !search.isEmpty {
            items.append(URLQueryItem(name: "search", value: search))
        }
        return items
    }
}

struct PlaceDetailEndpoint: APIEndpoint {
    typealias Response = PlaceDetail

    let placeId: UUID

    var method: HTTPMethod { .get }
    var path: String { "api/v1/places/\(placeId.uuidString.lowercased())" }
    var requiresAuthentication: Bool { true }
}

struct PlaceReviewsEndpoint: APIEndpoint {
    typealias Response = PageDTO<ReviewSummary>

    let placeId: UUID
    let scope: ReviewScope
    let page: Int
    let size: Int

    var method: HTTPMethod { .get }
    var path: String { "api/v1/places/\(placeId.uuidString.lowercased())/reviews" }
    var requiresAuthentication: Bool { true }

    var queryItems: [URLQueryItem] {
        [
            URLQueryItem(name: "scope", value: scope.rawValue),
            URLQueryItem(name: "page", value: String(page)),
            URLQueryItem(name: "size", value: String(size)),
        ]
    }
}

struct PlaceFriendsSummaryEndpoint: APIEndpoint {
    typealias Response = FriendPlaceSummary

    let placeId: UUID

    var method: HTTPMethod { .get }
    var path: String { "api/v1/places/\(placeId.uuidString.lowercased())/friends-summary" }
    var requiresAuthentication: Bool { true }
}

struct FriendMetricsEndpoint: APIEndpoint {
    typealias Response = [FriendPlaceMetrics]
    typealias Body = FriendMetricsRequestDTO

    let body: FriendMetricsRequestDTO?

    var method: HTTPMethod { .post }
    var path: String { "api/v1/me/places/friend-metrics" }
    var requiresAuthentication: Bool { true }

    init(placeIds: [UUID]) {
        body = FriendMetricsRequestDTO(placeIds: placeIds)
    }
}

struct SavedPlacesEndpoint: APIEndpoint {
    typealias Response = PageDTO<SavedPlaceDTO>

    let page: Int
    let size: Int

    var method: HTTPMethod { .get }
    var path: String { "api/v1/me/saved-places" }
    var requiresAuthentication: Bool { true }

    var queryItems: [URLQueryItem] {
        [
            URLQueryItem(name: "page", value: String(page)),
            URLQueryItem(name: "size", value: String(size)),
        ]
    }
}

struct OwnerVisitsEndpoint: APIEndpoint {
    typealias Response = PageDTO<OwnerVisitDTO>

    let page: Int
    let size: Int

    var method: HTTPMethod { .get }
    var path: String { "api/v1/me/visits" }
    var requiresAuthentication: Bool { true }

    var queryItems: [URLQueryItem] {
        [
            URLQueryItem(name: "page", value: String(page)),
            URLQueryItem(name: "size", value: String(size)),
        ]
    }
}
