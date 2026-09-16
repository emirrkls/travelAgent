import Foundation

enum VisitValidationIssue: Equatable, Sendable {
    case overallOutOfRange
    case futureDate
    case reviewTooLong
    case privateMemoryTooLong
    case invalidDimension
    case primaryExperienceRequired
    case feelingRequired
    case contentRequired
    case tooManyVibes
    case tooManyMedia
    case invalidTitle

    var localizedMessage: String {
        switch self {
        case .overallOutOfRange: String(localized: "visit.error.overall")
        case .futureDate: String(localized: "visit.error.future_date")
        case .reviewTooLong: String(localized: "visit.error.review_limit")
        case .privateMemoryTooLong: String(localized: "visit.error.memory_limit")
        case .invalidDimension: String(localized: "visit.error.dimension")
        case .primaryExperienceRequired: String(localized: "experience.error.primary")
        case .feelingRequired: String(localized: "experience.error.feeling")
        case .contentRequired: String(localized: "experience.error.content")
        case .tooManyVibes: String(localized: "experience.error.vibes")
        case .tooManyMedia: String(localized: "experience.error.media")
        case .invalidTitle: String(localized: "experience.error.title")
        }
    }
}
enum VisitValidation {
    static let textLimit = 4_000
    static let scoreRange = 0.0...10.0

    static func validate(_ state: VisitComposerState, today: Date = Date()) -> VisitValidationIssue? {
        if state.payloadVersion == 2 {
            guard let primary = state.primaryExperience,
                  primary != .unknown, primary != .unknownLegacy else {
                return .primaryExperienceRequired
            }
            if primary == .other && state.rawExperienceLabel.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return .primaryExperienceRequired
            }
            if primary != .other && !state.rawExperienceLabel.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return .primaryExperienceRequired
            }
            guard let feeling = state.overallFeeling, feeling != .unknown else { return .feelingRequired }
            if state.vibes.count > 2 { return .tooManyVibes }
            if state.vibes.contains(.unknown) || state.practicalSignals.contains(.unknown) ||
                state.companion == .unknown || state.timeOfDay == .unknown {
                return .invalidDimension
            }
            if state.mediaCount > 6 { return .tooManyMedia }
            if state.titleSource == .custom && state.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return .invalidTitle
            }
            if state.titleSource == .generated && !state.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return .invalidTitle
            }
            if state.mediaCount == 0 &&
                state.story.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
                state.tip.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return .contentRequired
            }
        } else {
        guard state.overallScore.isFinite, scoreRange.contains(state.overallScore) else {
            return .overallOutOfRange
        }
        }
        if Calendar.current.startOfDay(for: state.visitedAt) > Calendar.current.startOfDay(for: today) {
            return .futureDate
        }
        if state.publicReview.count > textLimit || state.story.count > textLimit { return .reviewTooLong }
        if state.privateMemory.count > textLimit { return .privateMemoryTooLong }
        let allowed = Set(VisitDimensionCatalog.keys(for: state.category))
        if state.dimensionScores.contains(where: {
            !allowed.contains($0.key) || !$0.value.isFinite || !scoreRange.contains($0.value)
        }) { return .invalidDimension }
        let semanticAllowed = Set(ExperienceDimensionCatalog.keys(for: state.primaryExperience))
        if state.semanticDimensions.contains(where: {
            !semanticAllowed.contains($0.key) || $0.value == .unknown
        }) { return .invalidDimension }
        return nil
    }

    static func trimmedOptional(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
