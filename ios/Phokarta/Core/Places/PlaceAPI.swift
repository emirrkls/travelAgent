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

struct PlaceBoundsEndpoint: APIEndpoint {
    typealias Response = [PlaceSummary]

    let west: Double
    let south: Double
    let east: Double
    let north: Double
    let category: PlaceCategory?
    let minRating: Double?
    let limit: Int

    var method: HTTPMethod { .get }
    var path: String { "api/v1/places/bounds" }
    var requiresAuthentication: Bool { true }

    var queryItems: [URLQueryItem] {
        var items = [
            URLQueryItem(name: "west", value: String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), west)),
            URLQueryItem(name: "south", value: String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), south)),
            URLQueryItem(name: "east", value: String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), east)),
            URLQueryItem(name: "north", value: String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), north)),
            URLQueryItem(name: "limit", value: String(limit)),
        ]
        if let category, let wire = category.wireValue {
            items.append(URLQueryItem(name: "category", value: wire))
        }
        if let minRating {
            items.append(URLQueryItem(name: "minRating", value: String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), minRating)))
        }
        return items
    }
}

struct PlaceNearbyEndpoint: APIEndpoint {
    typealias Response = [NearbyPlaceDTO]

    let latitude: Double
    let longitude: Double
    let radiusMeters: Double
    let category: PlaceCategory?
    let minRating: Double?
    let limit: Int

    var method: HTTPMethod { .get }
    var path: String { "api/v1/places/nearby" }
    var requiresAuthentication: Bool { true }

    var queryItems: [URLQueryItem] {
        var items = [
            URLQueryItem(name: "lat", value: String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), latitude)),
            URLQueryItem(name: "lon", value: String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), longitude)),
            URLQueryItem(name: "radiusMeters", value: String(format: "%.0f", locale: Locale(identifier: "en_US_POSIX"), radiusMeters)),
            URLQueryItem(name: "limit", value: String(limit)),
        ]
        if let category, let wire = category.wireValue {
            items.append(URLQueryItem(name: "category", value: wire))
        }
        if let minRating {
            items.append(URLQueryItem(name: "minRating", value: String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), minRating)))
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

struct SavePlaceEndpoint: APIEndpoint {
    typealias Response = SavedPlaceDTO
    let placeId: UUID
    var method: HTTPMethod { .post }
    var path: String { "api/v1/me/saved-places/\(placeId.uuidString.lowercased())" }
    var requiresAuthentication: Bool { true }
}

struct UnsavePlaceEndpoint: APIEndpoint {
    typealias Response = EmptyPayload
    let placeId: UUID
    var method: HTTPMethod { .delete }
    var path: String { "api/v1/me/saved-places/\(placeId.uuidString.lowercased())" }
    var requiresAuthentication: Bool { true }
}

struct CollectionsEndpoint: APIEndpoint {
    typealias Response = PageDTO<CollectionSummary>
    let page: Int
    let size: Int
    var method: HTTPMethod { .get }
    var path: String { "api/v1/me/collections" }
    var requiresAuthentication: Bool { true }
    var queryItems: [URLQueryItem] {
        [URLQueryItem(name: "page", value: String(page)), URLQueryItem(name: "size", value: String(size))]
    }
}

struct CreateCollectionEndpoint: APIEndpoint {
    typealias Response = CollectionDetail
    typealias Body = CreateCollectionRequestDTO
    let body: CreateCollectionRequestDTO?
    var method: HTTPMethod { .post }
    var path: String { "api/v1/me/collections" }
    var requiresAuthentication: Bool { true }
}

struct CollectionDetailEndpoint: APIEndpoint {
    typealias Response = CollectionDetail
    let collectionId: UUID
    var method: HTTPMethod { .get }
    var path: String { "api/v1/collections/\(collectionId.uuidString.lowercased())" }
    var requiresAuthentication: Bool { true }
}

struct AddCollectionPlaceEndpoint: APIEndpoint {
    typealias Response = CollectionDetail
    let collectionId: UUID
    let placeId: UUID
    var method: HTTPMethod { .post }
    var path: String { "api/v1/collections/\(collectionId.uuidString.lowercased())/places/\(placeId.uuidString.lowercased())" }
    var requiresAuthentication: Bool { true }
}

struct RemoveCollectionPlaceEndpoint: APIEndpoint {
    typealias Response = EmptyPayload
    let collectionId: UUID
    let placeId: UUID
    var method: HTTPMethod { .delete }
    var path: String { "api/v1/collections/\(collectionId.uuidString.lowercased())/places/\(placeId.uuidString.lowercased())" }
    var requiresAuthentication: Bool { true }
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
