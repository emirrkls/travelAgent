import Foundation

struct ExplorePlaceItem: Identifiable, Equatable, Sendable {
    var id: UUID { summary.id }
    var summary: PlaceSummary
    var friendAverageScore: Double?
    var friendsVisitedCount: Int64
    var isSaved: Bool
    var isVisited: Bool
    var personalScore: Double?

    init(
        summary: PlaceSummary,
        friendAverageScore: Double? = nil,
        friendsVisitedCount: Int64 = 0,
        isSaved: Bool = false,
        isVisited: Bool = false,
        personalScore: Double? = nil
    ) {
        self.summary = summary
        self.friendAverageScore = friendAverageScore
        self.friendsVisitedCount = friendsVisitedCount
        self.isSaved = isSaved
        self.isVisited = isVisited
        self.personalScore = personalScore
    }
}
