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

protocol VisitServing: Sendable {
    func create(_ request: VisitCreateRequest) async throws -> OwnerVisit
    func ownerVisits() async throws -> [OwnerVisit]
}

struct VisitService: VisitServing {
    let client: APIClient

    func create(_ request: VisitCreateRequest) async throws -> OwnerVisit {
        try await client.send(CreateVisitEndpoint(body: request))
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
}

@MainActor
@Observable
final class VisitStore {
    private(set) var accountID: UUID?
    private(set) var visits: [OwnerVisit] = []
    private let service: any VisitServing
    private var refreshID: UInt64 = 0

    init(service: any VisitServing) { self.service = service }

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

    func visits(for placeID: UUID) -> [OwnerVisit] {
        visits.filter { $0.place.id == placeID }
    }

    func latest(for placeID: UUID) -> OwnerVisit? { visits(for: placeID).first }

    private static func sortedDeduplicated(_ rows: [OwnerVisit]) -> [OwnerVisit] {
        var seen: Set<UUID> = []
        return rows.filter { seen.insert($0.id).inserted }
    }
}
