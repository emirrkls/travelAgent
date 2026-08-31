import Foundation
import Observation

protocol PlaceServing: Sendable {
    func listPlaces(
        search: String?,
        category: PlaceCategory?,
        page: Int,
        size: Int
    ) async throws -> PlacePage

    func placeDetail(id: UUID) async throws -> PlaceDetail

    func reviews(
        placeId: UUID,
        scope: ReviewScope,
        page: Int,
        size: Int
    ) async throws -> ReviewPage

    func friendsSummary(placeId: UUID) async throws -> FriendPlaceSummary

    func friendMetrics(placeIds: [UUID]) async throws -> [FriendPlaceMetrics]

    func savedPlaceIDs() async throws -> Set<UUID>

    func ownerVisits() async throws -> [OwnerVisitSummary]
}

struct PlaceService: PlaceServing {
    let client: APIClient

    static let defaultPageSize = 20
    static let enrichmentPageSize = 100

    func listPlaces(
        search: String?,
        category: PlaceCategory?,
        page: Int,
        size: Int
    ) async throws -> PlacePage {
        let trimmed = search?.trimmingCharacters(in: .whitespacesAndNewlines)
        let dto = try await client.send(
            PlaceListEndpoint(
                category: category,
                search: (trimmed?.isEmpty == false) ? trimmed : nil,
                page: page,
                size: size
            )
        )
        return PlacePage(
            places: dto.content,
            page: dto.page,
            totalPages: dto.totalPages,
            totalElements: dto.totalElements,
            hasNext: dto.hasNext
        )
    }

    func placeDetail(id: UUID) async throws -> PlaceDetail {
        try await client.send(PlaceDetailEndpoint(placeId: id))
    }

    func reviews(
        placeId: UUID,
        scope: ReviewScope,
        page: Int,
        size: Int
    ) async throws -> ReviewPage {
        let dto = try await client.send(
            PlaceReviewsEndpoint(placeId: placeId, scope: scope, page: page, size: size)
        )
        return ReviewPage(
            reviews: dto.content,
            page: dto.page,
            totalElements: dto.totalElements,
            hasNext: dto.hasNext
        )
    }

    func friendsSummary(placeId: UUID) async throws -> FriendPlaceSummary {
        try await client.send(PlaceFriendsSummaryEndpoint(placeId: placeId))
    }

    func friendMetrics(placeIds: [UUID]) async throws -> [FriendPlaceMetrics] {
        guard !placeIds.isEmpty else { return [] }
        return try await client.send(FriendMetricsEndpoint(placeIds: placeIds))
    }

    func savedPlaceIDs() async throws -> Set<UUID> {
        let dto = try await client.send(
            SavedPlacesEndpoint(page: 0, size: Self.enrichmentPageSize)
        )
        return Set(dto.content.map(\.place.id))
    }

    func ownerVisits() async throws -> [OwnerVisitSummary] {
        let dto = try await client.send(
            OwnerVisitsEndpoint(page: 0, size: Self.enrichmentPageSize)
        )
        return dto.content.map {
            OwnerVisitSummary(
                id: $0.id,
                placeId: $0.place.id,
                visitedAt: $0.visitedAt,
                overallRating: $0.overallRating
            )
        }
    }
}

protocol SavedPlaceServing: Sendable {
    func savedPlaces() async throws -> [SavedPlaceDTO]
    func setSaved(placeId: UUID, desired: Bool) async throws -> SavedPlaceDTO?
}

struct SavedPlaceService: SavedPlaceServing {
    let client: APIClient

    func savedPlaces() async throws -> [SavedPlaceDTO] {
        var page = 0
        var rows: [SavedPlaceDTO] = []
        repeat {
            let response = try await client.send(SavedPlacesEndpoint(page: page, size: 100))
            rows.append(contentsOf: response.content)
            guard response.hasNext else { break }
            page += 1
        } while true
        return rows
    }

    func setSaved(placeId: UUID, desired: Bool) async throws -> SavedPlaceDTO? {
        if desired {
            return try await client.send(SavePlaceEndpoint(placeId: placeId))
        }
        _ = try await client.send(UnsavePlaceEndpoint(placeId: placeId))
        return nil
    }
}

protocol CollectionServing: Sendable {
    func collections() async throws -> [CollectionSummary]
    func create(_ request: CreateCollectionRequestDTO) async throws -> CollectionDetail
    func detail(id: UUID) async throws -> CollectionDetail
    func add(placeId: UUID, to collectionId: UUID) async throws -> CollectionDetail
    func remove(placeId: UUID, from collectionId: UUID) async throws
}

struct CollectionService: CollectionServing {
    let client: APIClient

    func collections() async throws -> [CollectionSummary] {
        var page = 0
        var rows: [CollectionSummary] = []
        repeat {
            let response = try await client.send(CollectionsEndpoint(page: page, size: 100))
            rows.append(contentsOf: response.content)
            guard response.hasNext else { break }
            page += 1
        } while true
        return rows
    }

    func create(_ request: CreateCollectionRequestDTO) async throws -> CollectionDetail {
        try await client.send(CreateCollectionEndpoint(body: request))
    }

    func detail(id: UUID) async throws -> CollectionDetail {
        try await client.send(CollectionDetailEndpoint(collectionId: id))
    }

    func add(placeId: UUID, to collectionId: UUID) async throws -> CollectionDetail {
        try await client.send(AddCollectionPlaceEndpoint(collectionId: collectionId, placeId: placeId))
    }

    func remove(placeId: UUID, from collectionId: UUID) async throws {
        _ = try await client.send(RemoveCollectionPlaceEndpoint(collectionId: collectionId, placeId: placeId))
    }
}

@MainActor
@Observable
final class SavedPlaceStore {
    private(set) var accountID: UUID?
    private(set) var rows: [UUID: SavedPlaceDTO] = [:]
    private(set) var confirmedIDs: Set<UUID> = []
    private(set) var desiredByID: [UUID: Bool] = [:]
    private(set) var busyIDs: Set<UUID> = []
    private(set) var errors: [UUID: AppError] = [:]

    private let service: any SavedPlaceServing
    private var tasks: [UUID: Task<Void, Never>] = [:]
    private var revision: UInt64 = 0
    private var placeRevision: [UUID: UInt64] = [:]
    private var refreshID: UInt64 = 0

    init(service: any SavedPlaceServing) {
        self.service = service
    }

    var savedRows: [SavedPlaceDTO] {
        rows.values.sorted { $0.savedAt > $1.savedAt }
    }

    func activate(accountID: UUID) {
        guard self.accountID != accountID else { return }
        clear()
        self.accountID = accountID
    }

    func clear() {
        tasks.values.forEach { $0.cancel() }
        tasks.removeAll()
        accountID = nil
        rows.removeAll()
        confirmedIDs.removeAll()
        desiredByID.removeAll()
        busyIDs.removeAll()
        errors.removeAll()
        revision = 0
        placeRevision.removeAll()
        refreshID &+= 1
    }

    func isSaved(_ placeID: UUID) -> Bool {
        desiredByID[placeID] ?? confirmedIDs.contains(placeID)
    }

    func isBusy(_ placeID: UUID) -> Bool { busyIDs.contains(placeID) }

    func error(for placeID: UUID) -> AppError? { errors[placeID] }

    func refresh() async throws {
        guard let account = accountID else { throw AppError.unauthorized }
        refreshID &+= 1
        let request = refreshID
        let startingRevision = revision
        let response = try await service.savedPlaces()
        guard account == accountID, request == refreshID, !Task.isCancelled else { return }

        let incoming = Dictionary(uniqueKeysWithValues: response.map { ($0.place.id, $0) })
        let allIDs = Set(incoming.keys).union(confirmedIDs)
        for id in allIDs where (placeRevision[id] ?? 0) <= startingRevision {
            if let row = incoming[id] {
                confirmedIDs.insert(id)
                rows[id] = row
            } else {
                confirmedIDs.remove(id)
                rows[id] = nil
            }
        }
    }

    func toggle(_ placeID: UUID) {
        setDesired(!isSaved(placeID), for: placeID)
    }

    func setDesired(_ desired: Bool, for placeID: UUID) {
        guard accountID != nil else { return }
        desiredByID[placeID] = desired
        errors[placeID] = nil
        busyIDs.insert(placeID)
        guard tasks[placeID] == nil else { return }
        tasks[placeID] = Task { [weak self] in
            await self?.runMutationLoop(placeID: placeID)
        }
    }

    func waitForMutation(of placeID: UUID) async {
        await tasks[placeID]?.value
    }

    private func runMutationLoop(placeID: UUID) async {
        defer { tasks[placeID] = nil }
        while !Task.isCancelled, let account = accountID {
            let target = desiredByID[placeID] ?? confirmedIDs.contains(placeID)
            if target == confirmedIDs.contains(placeID) {
                desiredByID[placeID] = nil
                busyIDs.remove(placeID)
                return
            }
            do {
                let canonical = try await service.setSaved(placeId: placeID, desired: target)
                guard account == accountID, !Task.isCancelled else { return }
                revision &+= 1
                placeRevision[placeID] = revision
                if target {
                    confirmedIDs.insert(placeID)
                    if let canonical { rows[placeID] = canonical }
                } else {
                    confirmedIDs.remove(placeID)
                    rows[placeID] = nil
                }
            } catch is CancellationError {
                return
            } catch let error as AppError {
                guard account == accountID else { return }
                desiredByID[placeID] = nil
                busyIDs.remove(placeID)
                errors[placeID] = error
                return
            } catch {
                guard account == accountID else { return }
                desiredByID[placeID] = nil
                busyIDs.remove(placeID)
                errors[placeID] = .server
                return
            }
        }
    }
}

struct CollectionRelation: Hashable, Sendable {
    let collectionID: UUID
    let placeID: UUID
}

@MainActor
@Observable
final class CollectionStore {
    private(set) var accountID: UUID?
    private(set) var summaries: [CollectionSummary] = []
    private(set) var details: [UUID: CollectionDetail] = [:]
    private(set) var busyRelations: Set<CollectionRelation> = []

    private let service: any CollectionServing
    private var revision: UInt64 = 0
    private var summaryRevision: [UUID: UInt64] = [:]
    private var detailRevision: [UUID: UInt64] = [:]
    private var listRequestID: UInt64 = 0
    private var detailRequestID: [UUID: UInt64] = [:]

    init(service: any CollectionServing) {
        self.service = service
    }

    func activate(accountID: UUID) {
        guard self.accountID != accountID else { return }
        clear()
        self.accountID = accountID
    }

    func clear() {
        accountID = nil
        summaries.removeAll()
        details.removeAll()
        busyRelations.removeAll()
        revision = 0
        summaryRevision.removeAll()
        detailRevision.removeAll()
        listRequestID &+= 1
        detailRequestID.removeAll()
    }

    func refreshList() async throws {
        guard let account = accountID else { throw AppError.unauthorized }
        listRequestID &+= 1
        let request = listRequestID
        let startingRevision = revision
        let fetched = try await service.collections()
        guard account == accountID, request == listRequestID, !Task.isCancelled else { return }

        var merged = fetched
        let fetchedIDs = Set(fetched.map(\.id))
        for existing in summaries where !fetchedIDs.contains(existing.id) &&
            (summaryRevision[existing.id] ?? 0) > startingRevision {
            merged.append(existing)
        }
        for index in merged.indices {
            if let local = summaries.first(where: { $0.id == merged[index].id }),
               (summaryRevision[local.id] ?? 0) > startingRevision {
                merged[index] = local
            }
        }
        summaries = Self.deduplicated(merged)
    }

    func create(_ request: CreateCollectionRequestDTO) async throws -> CollectionDetail {
        guard let account = accountID else { throw AppError.unauthorized }
        let detail = try await service.create(request)
        guard account == accountID else { throw CancellationError() }
        applyCanonical(detail)
        return detail
    }

    func refreshDetail(id: UUID) async throws {
        guard let account = accountID else { throw AppError.unauthorized }
        let next = (detailRequestID[id] ?? 0) &+ 1
        detailRequestID[id] = next
        let startingRevision = detailRevision[id] ?? 0
        let detail = try await service.detail(id: id)
        guard account == accountID, detailRequestID[id] == next, !Task.isCancelled else { return }
        guard (detailRevision[id] ?? 0) <= startingRevision else { return }
        applyCanonical(detail)
    }

    func contains(placeID: UUID, in collectionID: UUID) -> Bool {
        details[collectionID]?.places.contains(where: { $0.place.id == placeID }) == true
    }

    func add(placeID: UUID, to collectionID: UUID) async throws {
        let relation = CollectionRelation(collectionID: collectionID, placeID: placeID)
        guard !busyRelations.contains(relation) else { return }
        guard let account = accountID else { throw AppError.unauthorized }
        busyRelations.insert(relation)
        defer { busyRelations.remove(relation) }
        do {
            let detail = try await service.add(placeId: placeID, to: collectionID)
            guard account == accountID else { throw CancellationError() }
            applyCanonical(detail)
        } catch let error as AppError where error == .conflict(code: "CONFLICT") {
            try await refreshDetail(id: collectionID)
        }
    }

    func remove(placeID: UUID, from collectionID: UUID) async throws {
        let relation = CollectionRelation(collectionID: collectionID, placeID: placeID)
        guard !busyRelations.contains(relation) else { return }
        guard let account = accountID else { throw AppError.unauthorized }
        busyRelations.insert(relation)
        defer { busyRelations.remove(relation) }
        try await service.remove(placeId: placeID, from: collectionID)
        guard account == accountID else { throw CancellationError() }
        revision &+= 1
        detailRevision[collectionID] = revision
        try await refreshDetail(id: collectionID)
    }

    func isBusy(placeID: UUID, collectionID: UUID) -> Bool {
        busyRelations.contains(CollectionRelation(collectionID: collectionID, placeID: placeID))
    }

    private func applyCanonical(_ detail: CollectionDetail) {
        revision &+= 1
        details[detail.id] = detail
        detailRevision[detail.id] = revision
        let summary = CollectionSummary(
            id: detail.id,
            userId: detail.userId,
            title: detail.title,
            description: detail.description,
            visibility: detail.visibility,
            coverImage: detail.coverImage,
            placeCount: Int64(detail.places.count),
            updatedAt: detail.updatedAt
        )
        summaries.removeAll { $0.id == detail.id }
        summaries.insert(summary, at: 0)
        summaryRevision[detail.id] = revision
    }

    private static func deduplicated(_ values: [CollectionSummary]) -> [CollectionSummary] {
        var seen: Set<UUID> = []
        return values.filter { seen.insert($0.id).inserted }
    }
}
