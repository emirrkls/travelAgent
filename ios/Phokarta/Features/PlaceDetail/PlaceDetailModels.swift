import Foundation

struct PersonalVisitSummary: Equatable, Sendable {
    let visitCount: Int
    let latestScore: Double
    let latestVisitedAt: String
}

struct PlaceDetailContent: Equatable, Sendable {
    var place: PlaceDetail
    var friends: FriendPlaceSummary?
    var communityReviews: [ReviewSummary]
    var friendReviews: [ReviewSummary]
    var isSaved: Bool
    var personal: PersonalVisitSummary?

    /// Backend Community aggregate. Never derived from `communityReviews`.
    var communityScore: Double? { place.communityScore }
    var communityRatingCount: Int64 { place.ratingCount }
}
