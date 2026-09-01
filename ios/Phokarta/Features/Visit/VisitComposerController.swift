import Foundation
import Observation

@MainActor
@Observable
final class VisitComposerController {
    var state: VisitComposerState
    private let store: VisitStore
    private let uuid: () -> UUID
    private var lastSentPayload: VisitCreateRequest.LogicalPayload?

    init(place: PlaceDetail, store: VisitStore, uuid: @escaping () -> UUID = UUID.init) {
        self.store = store
        self.uuid = uuid
        state = VisitComposerState(
            placeId: place.id,
            placeName: place.name,
            category: place.category,
            clientMutationId: uuid()
        )
    }

    func setOverall(_ value: Double) { edit { $0.overallScore = rounded(value) } }
    func setReview(_ value: String) { edit { $0.publicReview = String(value.prefix(VisitValidation.textLimit)) } }
    func setPrivateMemory(_ value: String) { edit { $0.privateMemory = String(value.prefix(VisitValidation.textLimit)) } }
    func setDate(_ value: Date) { edit { $0.visitedAt = value } }
    func setVisibility(_ value: VisitVisibility) { edit { $0.visibility = value } }

    func enableDimension(_ key: String) {
        guard VisitDimensionCatalog.keys(for: state.category).contains(key) else { return }
        edit { $0.dimensionScores[key] = rounded($0.overallScore) }
    }

    func setDimension(_ key: String, value: Double) {
        guard state.dimensionScores[key] != nil else { return }
        edit { $0.dimensionScores[key] = rounded(value) }
    }

    func removeDimension(_ key: String) { edit { $0.dimensionScores[key] = nil } }

    @discardableResult
    func publish() async -> OwnerVisit? {
        guard state.publishState != .publishing else { return nil }
        if let issue = VisitValidation.validate(state) {
            state.publishState = .validationFailure(issue.localizedMessage)
            return nil
        }

        var request = makeRequest(mutationID: state.clientMutationId)
        if let lastSentPayload, lastSentPayload != request.logicalPayload {
            state.clientMutationId = uuid()
            request = makeRequest(mutationID: state.clientMutationId)
        }
        lastSentPayload = request.logicalPayload
        state.publishState = .publishing
        do {
            let canonical = try await store.publish(request)
            state.publishState = .success
            return canonical
        } catch is CancellationError {
            state.publishState = .idle
            return nil
        } catch let failure as AppError {
            switch failure {
            case .policyAcceptanceRequired(let requiredVersion):
                state.publishState = .policyRequired(requiredVersion: requiredVersion)
            case .validation:
                state.publishState = .validationFailure(failure.localizedMessage)
            case .conflict:
                state.publishState = .validationFailure(String(localized: "visit.error.changed_payload"))
            default:
                state.publishState = .retryableFailure(failure)
            }
            return nil
        } catch {
            state.publishState = .retryableFailure(.server)
            return nil
        }
    }

    private func edit(_ mutation: (inout VisitComposerState) -> Void) {
        guard state.publishState != .publishing else { return }
        mutation(&state)
        if state.publishState != .idle { state.publishState = .idle }
    }

    private func makeRequest(mutationID: UUID) -> VisitCreateRequest {
        let dateFormatter = DateFormatter()
        dateFormatter.calendar = Calendar(identifier: .iso8601)
        dateFormatter.locale = Locale(identifier: "en_US_POSIX")
        dateFormatter.timeZone = .current
        dateFormatter.dateFormat = "yyyy-MM-dd"
        return VisitCreateRequest(
            clientMutationId: mutationID,
            placeId: state.placeId,
            visitedAt: dateFormatter.string(from: state.visitedAt),
            overallRating: rounded(state.overallScore),
            dimensions: state.dimensionScores.map { VisitDimensionScore(key: $0.key, score: rounded($0.value)) }
                .sorted { $0.key < $1.key },
            publicReview: VisitValidation.trimmedOptional(state.publicReview),
            privateMemory: VisitValidation.trimmedOptional(state.privateMemory),
            visibility: state.visibility
        )
    }

    private func rounded(_ value: Double) -> Double { (value * 10).rounded() / 10 }
}
