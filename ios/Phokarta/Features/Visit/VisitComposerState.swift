import Foundation

enum VisitPublishState: Equatable, Sendable {
    case idle
    case publishing
    case retryableFailure(AppError)
    case validationFailure(String)
    case policyRequired(requiredVersion: String?)
    case success
}

struct ExperienceComposerDisclosureState: Equatable, Sendable {
    var enrichExpanded = false
    var storyExpanded = false
    var titleExpanded = false
}

enum DraftRestoreFeedback: Equatable, Sendable {
    case restoredUserDraft

    var localizationKey: String { "visit.draft_restored" }
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
    var payloadVersion = 1
    var primaryExperience: PrimaryExperienceCode?
    var rawExperienceLabel = ""
    var overallFeeling: OverallFeelingCode?
    var semanticDimensions: [String: DimensionStateCode] = [:]
    var companion: CompanionCode?
    var timeOfDay: TimeOfDayCode?
    var vibes: Set<VibeCode> = []
    var practicalSignals: Set<PracticalSignalCode> = []
    var title = ""
    var titleSource: ExperienceTitleSource = .generated
    var story = ""
    var tip = ""
    var originAcknowledgementId: UUID?
    var draftRestoreFeedback: DraftRestoreFeedback?

    var isDirty: Bool {
        overallScore != 8.0 || !dimensionScores.isEmpty ||
            !publicReview.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
            !privateMemory.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
            !Calendar.current.isDateInToday(visitedAt) || visibility != .publicAccess ||
            mediaCount > 0 || primaryExperience != nil || overallFeeling != nil ||
            !semanticDimensions.isEmpty || companion != nil || timeOfDay != nil ||
            !vibes.isEmpty || !practicalSignals.isEmpty || !title.isEmpty ||
            !story.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
            !tip.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var canPublish: Bool {
        publishState != .publishing &&
            !mediaHasActiveWork &&
            mediaReadyForPublish &&
            VisitValidation.validate(self) == nil
    }
}
