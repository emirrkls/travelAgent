import Foundation

/// Pure value type representing visible camera viewport bounds and center.
struct MapViewport: Equatable, Sendable, Codable {
    let north: Double
    let east: Double
    let south: Double
    let west: Double
    let centerLatitude: Double
    let centerLongitude: Double
    let zoom: Double

    func contains(latitude: Double, longitude: Double) -> Bool {
        guard latitude >= south && latitude <= north else { return false }
        if west <= east {
            return longitude >= west && longitude <= east
        } else {
            return longitude >= west || longitude <= east
        }
    }

    func contains(place: PlaceSummary) -> Bool {
        contains(latitude: place.latitude, longitude: place.longitude)
    }

    var isValidForBackend: Bool {
        south < north && west < east &&
        south >= -90.0 && north <= 90.0 &&
        west >= -180.0 && east <= 180.0
    }
}

/// Computes whether the candidate viewport has moved materially from the applied viewport,
/// matching Android's normalized span threshold formula to prevent camera jitter.
func viewportMovedEnough(applied: MapViewport, candidate: MapViewport) -> Bool {
    if abs(applied.zoom - candidate.zoom) >= 0.55 { return true }
    let latitudeSpan = max(applied.north - applied.south, 0.0001)
    let longitudeSpan: Double
    if applied.west <= applied.east {
        longitudeSpan = max(applied.east - applied.west, 0.0001)
    } else {
        longitudeSpan = max(360.0 - applied.west + applied.east, 0.0001)
    }
    let dLat = (candidate.centerLatitude - applied.centerLatitude) / latitudeSpan
    let dLon = (candidate.centerLongitude - applied.centerLongitude) / longitudeSpan
    let normalizedDistance = (dLat * dLat + dLon * dLon).squareRoot()
    return normalizedDistance >= 0.18
}

/// Pure value type representing the discovery filter configuration.
struct MapFilters: Equatable, Sendable, Codable {
    var category: PlaceCategory? = nil
    var highlyRatedOnly: Bool = false
    var friendsVisitedOnly: Bool = false
    var visitedOnly: Bool = false
    var wantToGoOnly: Bool = false

    var activeCount: Int {
        (category != nil ? 1 : 0) +
        (highlyRatedOnly ? 1 : 0) +
        (friendsVisitedOnly ? 1 : 0) +
        (visitedOnly ? 1 : 0) +
        (wantToGoOnly ? 1 : 0)
    }
}

struct MapFriendSignal: Equatable, Sendable {
    let friendsVisitedCount: Int64
    let friendAverageScore: Double?

    var hasSignal: Bool { friendsVisitedCount > 0 }
    var showScore: Bool { hasSignal && friendAverageScore != nil }
}

func mapFriendSignal(metrics: FriendPlaceMetrics?) -> MapFriendSignal {
    let count = metrics?.friendsVisitedCount ?? 0
    return MapFriendSignal(
        friendsVisitedCount: count,
        friendAverageScore: count == 0 ? nil : metrics?.friendAverageScore
    )
}

func isValidCoordinate(latitude: Double, longitude: Double) -> Bool {
    latitude >= -90.0 && latitude <= 90.0 && longitude >= -180.0 && longitude <= 180.0
}

func filterMapPlaces(
    places: [PlaceSummary],
    filters: MapFilters,
    visitedPlaceIds: Set<UUID>,
    savedPlaceIds: Set<UUID>,
    viewport: MapViewport?,
    friendMetrics: [UUID: FriendPlaceMetrics] = [:]
) -> [PlaceSummary] {
    places.filter { place in
        guard isValidCoordinate(latitude: place.latitude, longitude: place.longitude) else {
            return false
        }
        if let category = filters.category, place.category != category { return false }
        if filters.highlyRatedOnly, (place.communityScore ?? -1.0) < 9.0 { return false }
        if filters.friendsVisitedOnly, (friendMetrics[place.id]?.friendsVisitedCount ?? 0) <= 0 { return false }
        if filters.visitedOnly, !visitedPlaceIds.contains(place.id) { return false }
        if filters.wantToGoOnly, !savedPlaceIds.contains(place.id) { return false }
        if let viewport, !viewport.contains(place: place) { return false }
        return true
    }
}

enum MapDefaults {
    static let centerLatitude = 37.085
    static let centerLongitude = 27.53
    static let zoom = 11.2

    static func defaultViewport() -> MapViewport {
        MapViewport(
            north: centerLatitude + 0.35,
            east: centerLongitude + 0.45,
            south: centerLatitude - 0.35,
            west: centerLongitude - 0.45,
            centerLatitude: centerLatitude,
            centerLongitude: centerLongitude,
            zoom: zoom
        )
    }
}
