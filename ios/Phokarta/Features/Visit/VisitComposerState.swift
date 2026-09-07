import Foundation

enum VisitPublishState: Equatable, Sendable {
    case idle
    case publishing
    case retryableFailure(AppError)
    case validationFailure(String)
    case policyRequired(requiredVersion: String?)
    case success
}

struct VisitComposerState: Equatable, Sendable {
    let placeId: UUID
    let placeName: String
    let category: PlaceCategory
    var overallScore: Double = 8.0
    var dimensionScores: [String: Double] = [:]
    var publicReview = ""
    var privateMemory = ""
    var visitedAt = Date()
    var visibility: VisitVisibility = .publicAccess
    var publishState: VisitPublishState = .idle
    var clientMutationId: UUID
    var mediaCount: Int = 0
    var mediaReadyForPublish: Bool = true
    var mediaHasActiveWork: Bool = false

    var isDirty: Bool {
        overallScore != 8.0 || !dimensionScores.isEmpty ||
            !publicReview.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
            !privateMemory.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
            !Calendar.current.isDateInToday(visitedAt) || visibility != .publicAccess ||
            mediaCount > 0
    }

    var canPublish: Bool {
        publishState != .publishing &&
            !mediaHasActiveWork &&
            mediaReadyForPublish &&
            VisitValidation.validate(self) == nil
    }
}
