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
    private(set) var experiences: [ExperienceSummaryV2] = []
    private(set) var experienceAggregate: PlaceAggregateV2?
    private(set) var selectedPrimary: PrimaryExperienceCode?
    private(set) var experiencesLoading = false
    private(set) var experiencesHasMore = false
    private(set) var experiencesError: AppError?
    private(set) var relationshipBusy: Set<UUID> = []
    private(set) var planBusy: Set<UUID> = []
    private(set) var acknowledgementBusy: Set<UUID> = []
    private var experiencesCursor: String?
    private var experiencesGeneration: UInt64 = 0

    private let placesService: any PlaceServing
    private let experienceService: (any ExperienceDiscoveryServing)?
    private let privacyService: (any PrivacyV2Serving)?
    private var loadTask: Task<Void, Never>?
    private var didStart = false
    private var requestID: UInt64 = 0

    init(
        placeId: UUID,
        places: any PlaceServing,
        experiences: (any ExperienceDiscoveryServing)? = nil,
        privacy: (any PrivacyV2Serving)? = nil
    ) {
        self.placeId = placeId
        self.placesService = places
        self.experienceService = experiences
        self.privacyService = privacy
    }

    func startIfNeeded() {
        guard !didStart else { return }
        didStart = true
        load()
        Task {
            await loadExperiences(reset: true)
            await loadExperienceAggregate()
        }
    }

    func retry() {
        refreshError = nil
        load()
        Task { await loadExperiences(reset: true) }
    }

    func refresh() async {
        await performLoad(isUserRefresh: true)
        await loadExperiences(reset: true)
        await loadExperienceAggregate()
    }

    func selectPrimary(_ primary: PrimaryExperienceCode?) {
        guard selectedPrimary != primary else { return }
        selectedPrimary = primary
        Task { await loadExperiences(reset: true) }
    }

    func loadMoreExperiences() {
        guard experiencesHasMore, !experiencesLoading else { return }
        Task { await loadExperiences(reset: false) }
    }

    func toggleExperienceRelationship(authorId: UUID) {
        guard let privacyService,
              let relationship = experiences.first(where: { $0.author.id == authorId })?.author.relationship,
              !relationshipBusy.contains(authorId) else { return }
        relationshipBusy.insert(authorId)
        Task {
            defer { relationshipBusy.remove(authorId) }
            do {
                let updated: RelationshipV2
                switch relationship.state {
                case .none: updated = try await privacyService.follow(userId: authorId)
                case .requestPending: updated = try await privacyService.cancelFollowRequest(userId: authorId)
                case .following, .friends: updated = try await privacyService.unfollow(userId: authorId)
                default: return
                }
                experiences = experiences.map { $0.replacingRelationship(for: authorId, with: updated) }
            } catch let appError as AppError {
                experiencesError = appError
            } catch {
                experiencesError = .server
            }
        }
    }

    func togglePlannedExperience(id: UUID) {
        guard let experienceService,
              let value = experiences.first(where: { $0.id == id }),
              !planBusy.contains(id) else { return }
        let desired = !(value.plannedByViewer ?? false)
        planBusy.insert(id)
        Task {
            defer { planBusy.remove(id) }
            do {
                if desired { _ = try await experienceService.plan(experienceId: id) }
                else { try await experienceService.unplan(experienceId: id) }
                experiences = experiences.map { $0.id == id ? $0.replacingMilestone(
                    planned: desired, acknowledged: $0.acknowledgedByViewer ?? false,
                    count: $0.acknowledgementCount ?? 0
                ) : $0 }
            } catch let appError as AppError { experiencesError = appError }
            catch { experiencesError = .server }
        }
    }

    func acknowledgeExperience(id: UUID) {
        guard let experienceService,
              let value = experiences.first(where: { $0.id == id }),
              value.author.relationship != nil,
              !(value.acknowledgedByViewer ?? false),
              !acknowledgementBusy.contains(id) else { return }
        acknowledgementBusy.insert(id)
        Task {
            defer { acknowledgementBusy.remove(id) }
            do {
                _ = try await experienceService.acknowledge(experienceId: id)
                experiences = experiences.map { $0.id == id ? $0.replacingMilestone(
                    planned: $0.plannedByViewer ?? false, acknowledged: true,
                    count: ($0.acknowledgementCount ?? 0) + 1
                ) : $0 }
            } catch let appError as AppError { experiencesError = appError }
            catch { experiencesError = .server }
        }
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
            async let visits: [OwnerVisitSummary] = {
                (try? await placesService.ownerVisits()) ?? []
            }()

            let friendSummary = await friends
            let communityPage = await community
            let friendPage = await friendReviews
            let ownerVisits = await visits
            guard id == requestID, !Task.isCancelled else { return }

            if let communityPage {
                next.communityReviews = communityPage.reviews
            }
            if let friendPage {
                next.friendReviews = friendPage.reviews
            }
            next.friends = friendSummary
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

    private func loadExperienceAggregate() async {
        guard let privacyService else { return }
        experienceAggregate = try? await privacyService.placeAggregate(placeId: placeId)
    }

    private func loadExperiences(reset: Bool) async {
        guard let experienceService else { return }
        experiencesGeneration &+= 1
        let generation = experiencesGeneration
        let primary = selectedPrimary
        experiencesLoading = true
        experiencesError = nil
        do {
            let page = try await experienceService.placeExperiences(
                placeId: placeId,
                cursor: reset ? nil : experiencesCursor,
                primary: primary
            )
            guard generation == experiencesGeneration else { return }
            experiences = reset ? page.items : Self.deduplicated(experiences + page.items)
            experiencesCursor = page.nextCursor
            experiencesHasMore = page.hasMore
        } catch let appError as AppError {
            guard generation == experiencesGeneration else { return }
            experiencesError = appError
        } catch {
            guard generation == experiencesGeneration else { return }
            experiencesError = .server
        }
        if generation == experiencesGeneration { experiencesLoading = false }
    }

    private static func deduplicated(_ values: [ExperienceSummaryV2]) -> [ExperienceSummaryV2] {
        var seen = Set<UUID>()
        return values.filter { seen.insert($0.id).inserted }
    }
}
