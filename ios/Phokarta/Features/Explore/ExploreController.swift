import Foundation
import Observation

@MainActor
@Observable
final class ExploreController {
    private(set) var phase: ExplorePhase = .idle
    var query: String = ""
    private(set) var selectedCategory: PlaceCategory?
    private(set) var places: [ExplorePlaceItem] = []
    private(set) var hasNext = false
    private(set) var page = 0
    private(set) var isLoadingMore = false
    private(set) var refreshError: ExploreErrorKind?

    let debounceNanoseconds: UInt64
    private let placesService: any PlaceServing
    private var requestID: UInt64 = 0
    private var loadTask: Task<Void, Never>?
    private var debounceTask: Task<Void, Never>?
    private var didStart = false
    private var visitsByPlace: [UUID: OwnerVisitSummary] = [:]

    init(places: any PlaceServing, debounceNanoseconds: UInt64 = 300_000_000) {
        self.placesService = places
        self.debounceNanoseconds = debounceNanoseconds
    }

    func startIfNeeded() {
        guard !didStart else { return }
        didStart = true
        reload(reset: true)
    }

    func retry() {
        refreshError = nil
        reload(reset: true)
    }

    func refresh() async {
        await fetch(page: 0, replacing: false, isUserRefresh: true)
    }

    func setQuery(_ value: String) {
        query = value
        debounceTask?.cancel()
        debounceTask = Task { [weak self] in
            guard let self else { return }
            if self.debounceNanoseconds > 0 {
                try? await Task.sleep(nanoseconds: self.debounceNanoseconds)
            }
            guard !Task.isCancelled else { return }
            self.reload(reset: true)
        }
    }

    func selectCategory(_ category: PlaceCategory?) {
        if selectedCategory == category {
            selectedCategory = nil
        } else {
            selectedCategory = category
        }
        debounceTask?.cancel()
        reload(reset: true)
    }

    func loadNextPageIfNeeded(current item: ExplorePlaceItem) {
        guard hasNext, !isLoadingMore, phase == .content else { return }
        guard item.id == places.last?.id else { return }
        isLoadingMore = true
        loadTask = Task { [weak self] in
            await self?.fetch(page: (self?.page ?? 0) + 1, replacing: false, isUserRefresh: false)
        }
    }

    func cancel() {
        debounceTask?.cancel()
        loadTask?.cancel()
        debounceTask = nil
        loadTask = nil
    }

    private func reload(reset: Bool) {
        loadTask?.cancel()
        requestID += 1
        let capturedID = requestID
        if reset {
            places = []
            hasNext = false
            page = 0
            isLoadingMore = false
            refreshError = nil
            phase = .loading
        }
        loadTask = Task { [weak self] in
            await self?.fetch(page: 0, replacing: true, isUserRefresh: false, expectedID: capturedID)
        }
    }

    private func fetch(
        page requestedPage: Int,
        replacing _: Bool,
        isUserRefresh: Bool,
        expectedID: UInt64? = nil
    ) async {
        let id = expectedID ?? requestID
        defer { isLoadingMore = false }
        if requestedPage > 0 {
            isLoadingMore = true
        } else if places.isEmpty {
            phase = .loading
        }

        do {
            let search = query.trimmingCharacters(in: .whitespacesAndNewlines)
            let result = try await placesService.listPlaces(
                search: search.isEmpty ? nil : search,
                category: selectedCategory,
                page: requestedPage,
                size: PlaceService.defaultPageSize
            )
            guard id == requestID, !Task.isCancelled else { return }

            let items = result.places.map { ExplorePlaceItem(summary: $0) }
            let merged: [ExplorePlaceItem]
            if requestedPage == 0 {
                merged = Self.deduplicated(items)
            } else {
                merged = Self.deduplicated(places + items)
            }

            places = merged
            page = result.page
            hasNext = result.hasNext
            refreshError = nil
            applyEmptyOrContent()
            if !merged.isEmpty {
                await enrich(items: merged, requestID: id)
            }
        } catch is CancellationError {
            return
        } catch let error as AppError where error == .cancelled {
            return
        } catch let error as AppError {
            guard id == requestID, !Task.isCancelled else { return }
            let kind = ExploreErrorKind.from(error)
            if kind == .unauthorized {
                phase = .error(.unauthorized)
                return
            }
            if places.isEmpty {
                phase = .error(kind)
            } else if isUserRefresh || requestedPage == 0 {
                refreshError = kind
                phase = .content
            } else {
                refreshError = kind
            }
        } catch {
            guard id == requestID, !Task.isCancelled else { return }
            if places.isEmpty {
                phase = .error(.server)
            } else {
                refreshError = .server
            }
        }
    }

    private func enrich(items: [ExplorePlaceItem], requestID: UInt64) async {
        let ids = items.map(\.id)
        let cachedVisits = Array(visitsByPlace.values)
        async let metricsResult: [FriendPlaceMetrics] = {
            do { return try await placesService.friendMetrics(placeIds: ids) }
            catch { return [] }
        }()
        async let visitsResult: [OwnerVisitSummary] = {
            do { return try await placesService.ownerVisits() }
            catch { return cachedVisits }
        }()

        let metrics = await metricsResult
        let visits = await visitsResult
        guard requestID == self.requestID, !Task.isCancelled else { return }

        visitsByPlace = Dictionary(visits.map { ($0.placeId, $0) }, uniquingKeysWith: { first, second in
            first.visitedAt >= second.visitedAt ? first : second
        })
        let metricsByID = Dictionary(uniqueKeysWithValues: metrics.map { ($0.placeId, $0) })

        places = places.map { item in
            var next = item
            if let metric = metricsByID[item.id] {
                next.friendAverageScore = metric.friendsVisitedCount > 0 ? metric.friendAverageScore : nil
                next.friendsVisitedCount = metric.friendsVisitedCount
            }
            if let visit = visitsByPlace[item.id] {
                next.isVisited = true
                next.personalScore = visit.overallRating
            } else {
                next.isVisited = false
                next.personalScore = nil
            }
            return next
        }
        applyEmptyOrContent()
    }

    private func applyEmptyOrContent() {
        if places.isEmpty {
            let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty {
                phase = .empty(.search)
            } else if selectedCategory != nil {
                phase = .empty(.category)
            } else {
                phase = .empty(.catalog)
            }
        } else {
            phase = .content
        }
    }

    private static func deduplicated(_ items: [ExplorePlaceItem]) -> [ExplorePlaceItem] {
        var seen = Set<UUID>()
        var result: [ExplorePlaceItem] = []
        result.reserveCapacity(items.count)
        for item in items where seen.insert(item.id).inserted {
            result.append(item)
        }
        return result
    }
}
