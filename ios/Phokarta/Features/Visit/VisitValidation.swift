import Foundation

enum VisitValidationIssue: Equatable, Sendable {
    case overallOutOfRange
    case futureDate
    case reviewTooLong
    case privateMemoryTooLong
    case invalidDimension

    var localizedMessage: String {
        switch self {
        case .overallOutOfRange: String(localized: "visit.error.overall")
        case .futureDate: String(localized: "visit.error.future_date")
        case .reviewTooLong: String(localized: "visit.error.review_limit")
        case .privateMemoryTooLong: String(localized: "visit.error.memory_limit")
        case .invalidDimension: String(localized: "visit.error.dimension")
        }
    }
}
enum VisitValidation {
    static let textLimit = 4_000
    static let scoreRange = 0.0...10.0

    static func validate(_ state: VisitComposerState, today: Date = Date()) -> VisitValidationIssue? {
        guard state.overallScore.isFinite, scoreRange.contains(state.overallScore) else {
            return .overallOutOfRange
        }
        if Calendar.current.startOfDay(for: state.visitedAt) > Calendar.current.startOfDay(for: today) {
            return .futureDate
        }
        if state.publicReview.count > textLimit { return .reviewTooLong }
        if state.privateMemory.count > textLimit { return .privateMemoryTooLong }
        let allowed = Set(VisitDimensionCatalog.keys(for: state.category))
        if state.dimensionScores.contains(where: {
            !allowed.contains($0.key) || !$0.value.isFinite || !scoreRange.contains($0.value)
        }) { return .invalidDimension }
        return nil
    }

    static func trimmedOptional(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
