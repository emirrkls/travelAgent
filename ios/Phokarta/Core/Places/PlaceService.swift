import Foundation

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
