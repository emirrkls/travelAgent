import Foundation
import Observation

struct CreateVisitEndpoint: APIEndpoint {
    typealias Response = OwnerVisit
    typealias Body = VisitCreateRequest
    let body: VisitCreateRequest?
    var method: HTTPMethod { .post }
    var path: String { "api/v1/visits" }
    var requiresAuthentication: Bool { true }
}

struct CreateExperienceV2Endpoint: APIEndpoint {
    typealias Response = ExperienceV2
    typealias Body = ExperienceV2CreateRequest
    let body: ExperienceV2CreateRequest?
    var method: HTTPMethod { .post }
    var path: String { "api/v2/experiences" }
    var requiresAuthentication: Bool { true }
}

struct MyVisitsEndpoint: APIEndpoint {
    typealias Response = PageDTO<OwnerVisit>
    let page: Int
    let size: Int
    var method: HTTPMethod { .get }
    var path: String { "api/v1/me/visits" }
    var requiresAuthentication: Bool { true }
    var queryItems: [URLQueryItem] {
        [URLQueryItem(name: "page", value: String(page)), URLQueryItem(name: "size", value: String(size))]
    }
}

struct ExperienceV2Endpoint: APIEndpoint {
    typealias Response = ExperienceV2
    let experienceId: UUID
    var method: HTTPMethod { .get }
    var path: String { "api/v2/experiences/\(experienceId.uuidString.uppercased())" }
    var requiresAuthentication: Bool { false }
}

struct SyncPlanExperienceEndpoint: APIEndpoint {
    typealias Response = PlannedExperienceV2
    let experienceId: UUID
    var method: HTTPMethod { .put }
    var path: String { "api/v2/me/planned-experiences/\(experienceId.uuidString.uppercased())" }
    var requiresAuthentication: Bool { true }
}

struct SyncUnplanExperienceEndpoint: APIEndpoint {
    typealias Response = EmptyPayload
    let experienceId: UUID
    var method: HTTPMethod { .delete }
    var path: String { "api/v2/me/planned-experiences/\(experienceId.uuidString.uppercased())" }
    var requiresAuthentication: Bool { true }
}

struct SyncAcknowledgeExperienceEndpoint: APIEndpoint {
    typealias Response = ExperienceAcknowledgementV2
    let experienceId: UUID
    let clientAcknowledgementId: UUID?
    let anchorPlaceId: UUID?
    let anchorPrimaryExperienceCode: String?
    let anchorRawExperienceLabel: String?
    var method: HTTPMethod { .put }
    var path: String { "api/v2/experiences/\(experienceId.uuidString.uppercased())/acknowledgement" }
    var requiresAuthentication: Bool { true }
    var queryItems: [URLQueryItem] {
        var values: [URLQueryItem] = []
        if let clientAcknowledgementId {
            values.append(URLQueryItem(name: "clientAcknowledgementId", value: clientAcknowledgementId.uuidString))
        }
        if let anchorPlaceId { values.append(URLQueryItem(name: "anchorPlaceId", value: anchorPlaceId.uuidString)) }
        if let anchorPrimaryExperienceCode {
            values.append(URLQueryItem(name: "anchorPrimaryExperienceCode", value: anchorPrimaryExperienceCode))
        }
        if let anchorRawExperienceLabel {
            values.append(URLQueryItem(name: "anchorRawExperienceLabel", value: anchorRawExperienceLabel))
        }
        return values
    }
}

protocol ExperienceV2Serving: Sendable {
    func experience(id: UUID) async throws -> ExperienceV2
}

struct ExperienceV2Service: ExperienceV2Serving {
    let client: APIClient

    func experience(id: UUID) async throws -> ExperienceV2 {
        try await client.send(ExperienceV2Endpoint(experienceId: id))
    }
}

protocol VisitServing: Sendable {
    func create(_ request: VisitCreateRequest) async throws -> OwnerVisit
    func createExperience(_ request: ExperienceV2CreateRequest) async throws -> ExperienceV2
    func ownerVisits() async throws -> [OwnerVisit]
    func setPlannedExperience(id: UUID, desired: Bool) async throws -> PlannedExperienceV2?
    func acknowledgeExperience(id: UUID, clientAcknowledgementId: UUID?) async throws -> ExperienceAcknowledgementV2
    func acknowledgeExperience(
        id: UUID, clientAcknowledgementId: UUID?, anchor: DurableAcknowledgementAnchor?
    ) async throws -> ExperienceAcknowledgementV2
}

extension VisitServing {
    func createExperience(_ request: ExperienceV2CreateRequest) async throws -> ExperienceV2 {
        throw AppError.server
    }
    func setPlannedExperience(id: UUID, desired: Bool) async throws -> PlannedExperienceV2? { throw AppError.server }
    func acknowledgeExperience(id: UUID, clientAcknowledgementId: UUID?) async throws -> ExperienceAcknowledgementV2 { throw AppError.server }
    func acknowledgeExperience(
        id: UUID, clientAcknowledgementId: UUID?, anchor: DurableAcknowledgementAnchor?
    ) async throws -> ExperienceAcknowledgementV2 {
        try await acknowledgeExperience(id: id, clientAcknowledgementId: clientAcknowledgementId)
    }
}

struct VisitService: VisitServing {
    let client: APIClient

    func create(_ request: VisitCreateRequest) async throws -> OwnerVisit {
        try await client.send(CreateVisitEndpoint(body: request))
    }

    func createExperience(_ request: ExperienceV2CreateRequest) async throws -> ExperienceV2 {
        try await client.send(CreateExperienceV2Endpoint(body: request))
    }

    func ownerVisits() async throws -> [OwnerVisit] {
        var page = 0
        var rows: [OwnerVisit] = []
        repeat {
            let result = try await client.send(MyVisitsEndpoint(page: page, size: 100))
            rows.append(contentsOf: result.content)
            guard result.hasNext else { break }
            page += 1
        } while true
        return rows
    }

    func setPlannedExperience(id: UUID, desired: Bool) async throws -> PlannedExperienceV2? {
        if desired { return try await client.send(SyncPlanExperienceEndpoint(experienceId: id)) }
        _ = try await client.send(SyncUnplanExperienceEndpoint(experienceId: id))
        return nil
    }

    func acknowledgeExperience(id: UUID, clientAcknowledgementId: UUID?) async throws -> ExperienceAcknowledgementV2 {
        try await acknowledgeExperience(id: id, clientAcknowledgementId: clientAcknowledgementId, anchor: nil)
    }

    func acknowledgeExperience(
        id: UUID, clientAcknowledgementId: UUID?, anchor: DurableAcknowledgementAnchor?
    ) async throws -> ExperienceAcknowledgementV2 {
        try await client.send(SyncAcknowledgeExperienceEndpoint(
            experienceId: id,
            clientAcknowledgementId: clientAcknowledgementId,
            anchorPlaceId: anchor?.placeId,
            anchorPrimaryExperienceCode: anchor?.primaryExperienceCode,
            anchorRawExperienceLabel: anchor?.rawExperienceLabel
        ))
    }
}

@MainActor
@Observable
final class VisitStore {
    private(set) var accountID: UUID?
    private(set) var visits: [OwnerVisit] = []
    var visitedPlaceIDs: Set<UUID> {
        Set(visits.map(\.place.id))
    }

    func isVisited(_ placeID: UUID) -> Bool {
        visitedPlaceIDs.contains(placeID)
    }
    private let service: any VisitServing
    let mediaService: any VisitMediaServing
    private var refreshID: UInt64 = 0

    init(service: any VisitServing, mediaService: (any VisitMediaServing)? = nil) {
        self.service = service
        if let mediaService {
            self.mediaService = mediaService
        } else if let visitService = service as? VisitService {
            self.mediaService = VisitMediaService(client: visitService.client)
        } else {
            self.mediaService = NullVisitMediaService()
        }
    }

    func activate(accountID: UUID) {
        guard self.accountID != accountID else { return }
        clear()
        self.accountID = accountID
    }

    func clear() {
        accountID = nil
        visits.removeAll()
        refreshID &+= 1
    }

    func refresh() async throws {
        guard let account = accountID else { throw AppError.unauthorized }
        refreshID &+= 1
        let request = refreshID
        let incoming = try await service.ownerVisits()
        guard account == accountID, request == refreshID, !Task.isCancelled else { return }
        visits = Self.sortedDeduplicated(incoming)
    }

    func publish(_ request: VisitCreateRequest) async throws -> OwnerVisit {
        guard let account = accountID else { throw AppError.unauthorized }
        let canonical = try await service.create(request)
        guard account == accountID, !Task.isCancelled else { throw CancellationError() }
        visits.removeAll { $0.id == canonical.id }
        visits.insert(canonical, at: 0)
        return canonical
    }

    func reconcileCanonicalVisit(_ canonical: OwnerVisit) {
        visits.removeAll { $0.id == canonical.id }
        visits.insert(canonical, at: 0)
    }

    func visits(for placeID: UUID) -> [OwnerVisit] {
        visits.filter { $0.place.id == placeID }
    }

    func latest(for placeID: UUID) -> OwnerVisit? { visits(for: placeID).first }

    private static func sortedDeduplicated(_ rows: [OwnerVisit]) -> [OwnerVisit] {
        var seen: Set<UUID> = []
        return rows.filter { seen.insert($0.id).inserted }
    }
}
