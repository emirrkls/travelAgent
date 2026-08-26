import Foundation

enum PlaceDetailPhase: Equatable, Sendable {
    case idle
    case loading
    case content
    case unavailable
    case error(ExploreErrorKind)
}

enum PlaceDetailReviewScope: Equatable, Hashable, Sendable {
    case community
    case friends
}
