import Foundation
import Observation

@MainActor
@Observable
final class PlaceDetailController {
    let placeId: UUID
    private(set) var phase: PlaceDetailPhase = .idle
    private(set) var content: PlaceDetailContent?
    private(set) var reviewScope: PlaceDetailReviewScope = .community
    private(set) var refreshError: ExploreErrorKind?

    private let placesService: any PlaceServing
    private var loadTask: Task<Void, Never>?
    private var didStart = false
    private var requestID: UInt64 = 0

    init(placeId: UUID, places: any PlaceServing) {
        self.placeId = placeId
        self.placesService = places
    }

    func startIfNeeded() {
        guard !didStart else { return }
        didStart = true
        load()
    }

    func retry() {
        refreshError = nil
        load()
    }

    func refresh() async {
        await performLoad(isUserRefresh: true)
    }

    func selectReviewScope(_ scope: PlaceDetailReviewScope) {
        reviewScope = scope
    }

    func cancel() {
        loadTask?.cancel()
        loadTask = nil
    }

    private func load() {
        loadTask?.cancel()
        requestID += 1
        let id = requestID
        if content == nil {
            phase = .loading
        }
        loadTask = Task { [weak self] in
            await self?.performLoad(isUserRefresh: false, expectedID: id)
        }
    }

    private func performLoad(isUserRefresh: Bool, expectedID: UInt64? = nil) async {
        let id = expectedID ?? requestID
        do {
            let detail = try await placesService.placeDetail(id: placeId)
            guard id == requestID, !Task.isCancelled else { return }

            var next = PlaceDetailContent(
                place: detail,
                friends: nil,
                communityReviews: detail.recentPublicReviews,
                friendReviews: [],
                isSaved: false,
                personal: nil
            )
            content = next
            phase = .content
            refreshError = nil

            async let friends: FriendPlaceSummary? = {
                try? await placesService.friendsSummary(placeId: placeId)
            }()
            async let community: ReviewPage? = {
                try? await placesService.reviews(placeId: placeId, scope: .community, page: 0, size: 20)
            }()
            async let friendReviews: ReviewPage? = {
                try? await placesService.reviews(placeId: placeId, scope: .friends, page: 0, size: 20)
            }()
            async let saved: Set<UUID> = {
                (try? await placesService.savedPlaceIDs()) ?? []
            }()
            async let visits: [OwnerVisitSummary] = {
                (try? await placesService.ownerVisits()) ?? []
            }()

            let friendSummary = await friends
            let communityPage = await community
            let friendPage = await friendReviews
            let savedIDs = await saved
            let ownerVisits = await visits
            guard id == requestID, !Task.isCancelled else { return }

            if let communityPage {
                next.communityReviews = communityPage.reviews
            }
            if let friendPage {
                next.friendReviews = friendPage.reviews
            }
            next.friends = friendSummary
            next.isSaved = savedIDs.contains(placeId)
            let mine = ownerVisits.filter { $0.placeId == placeId }
                .sorted { $0.visitedAt > $1.visitedAt }
            if let latest = mine.first {
                next.personal = PersonalVisitSummary(
                    visitCount: mine.count,
                    latestScore: latest.overallRating,
                    latestVisitedAt: latest.visitedAt
                )
            }
            content = next
            phase = .content
        } catch is CancellationError {
            return
        } catch let error as AppError where error == .cancelled {
            return
        } catch let error as AppError {
            guard id == requestID, !Task.isCancelled else { return }
            if error == .notFound || error == .forbidden {
                phase = .unavailable
                content = nil
                return
            }
            let kind = ExploreErrorKind.from(error)
            if kind == .unauthorized {
                phase = .error(.unauthorized)
                return
            }
            if content == nil {
                phase = .error(kind)
            } else {
                refreshError = kind
                _ = isUserRefresh
            }
        } catch {
            guard id == requestID, !Task.isCancelled else { return }
            if content == nil {
                phase = .error(.server)
            } else {
                refreshError = .server
            }
        }
    }
}
