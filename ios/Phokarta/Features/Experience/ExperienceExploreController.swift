import Foundation
import Observation

@MainActor
@Observable
final class ExperienceExploreController {
    private(set) var items: [ExperienceSummaryV2] = []
    private(set) var isLoading = false
    private(set) var isLoadingMore = false
    private(set) var hasMore = false
    private(set) var nextCursor: String?
    private(set) var error: AppError?
    private(set) var coordinate: (latitude: Double, longitude: Double)?
    private(set) var relationshipBusy: Set<UUID> = []
    private(set) var planBusy: Set<UUID> = []
    private(set) var acknowledgementBusy: Set<UUID> = []
    var lens: ExperienceFeedLens = .forYou
    var discoveryFilter: ExperienceDiscoveryFilter?
    var query = ""

    var needsNearbyLocation: Bool { lens == .nearby && coordinate == nil }

    private let service: any ExperienceDiscoveryServing
    private let privacy: any PrivacyV2Serving
    private let location: any LocationProviding
    private var generation = 0
    private var loadTask: Task<Void, Never>?
    private var searchTask: Task<Void, Never>?

    init(
        service: any ExperienceDiscoveryServing,
        privacy: any PrivacyV2Serving,
        location: any LocationProviding = CoreLocationService()
    ) {
        self.service = service
        self.privacy = privacy
        self.location = location
    }

    func start() {
        guard items.isEmpty, !isLoading else { return }
        reload()
    }

    func selectLens(_ value: ExperienceFeedLens) {
        guard lens != value else { return }
        lens = value
        reload()
    }

    func setQuery(_ value: String) {
        query = value
        searchTask?.cancel()
        searchTask = Task {
            try? await Task.sleep(for: .milliseconds(350))
            guard !Task.isCancelled else { return }
            reload()
        }
    }

    func selectDiscoveryFilter(_ value: ExperienceDiscoveryFilter) {
        discoveryFilter = discoveryFilter == value ? nil : value
        reload()
    }

    func reload() {
        load(reset: true)
    }

    func loadMoreIfNeeded(current item: ExperienceSummaryV2) {
        guard item.id == items.suffix(3).first?.id || item.id == items.last?.id else { return }
        load(reset: false)
    }

    func requestNearbyLocation() {
        Task {
            switch await location.requestLocation() {
            case .success(let value):
                coordinate = (value.latitude, value.longitude)
                reload()
            case .failure:
                coordinate = nil
            }
        }
    }

    func toggleRelationship(authorId: UUID) {
        guard let relationship = items.first(where: { $0.author.id == authorId })?.author.relationship,
              !relationshipBusy.contains(authorId) else { return }
        relationshipBusy.insert(authorId)
        Task {
            defer { relationshipBusy.remove(authorId) }
            do {
                let updated: RelationshipV2
                switch relationship.state {
                case .none: updated = try await privacy.follow(userId: authorId)
                case .requestPending: updated = try await privacy.cancelFollowRequest(userId: authorId)
                case .following, .friends: updated = try await privacy.unfollow(userId: authorId)
                default: return
                }
                items = items.map { $0.replacingRelationship(for: authorId, with: updated) }
            } catch let appError as AppError {
                error = appError
            } catch {
                self.error = .server
            }
        }
    }

    func togglePlan(experienceId: UUID) {
        guard let value = items.first(where: { $0.id == experienceId }),
              !planBusy.contains(experienceId) else { return }
        let desired = !(value.plannedByViewer ?? false)
        planBusy.insert(experienceId)
        Task {
            defer { planBusy.remove(experienceId) }
            do {
                _ = try await service.setPlanned(experienceId: experienceId, desired: desired)
                items = items.map { $0.id == experienceId ? $0.replacingMilestone(
                    planned: desired,
                    acknowledged: $0.acknowledgedByViewer ?? false,
                    count: $0.acknowledgementCount ?? 0
                ) : $0 }
            } catch let appError as AppError { error = appError }
            catch { self.error = .server }
        }
    }

    func acknowledge(experienceId: UUID) {
        guard let value = items.first(where: { $0.id == experienceId }),
              value.author.relationship != nil,
              !(value.acknowledgedByViewer ?? false),
              !acknowledgementBusy.contains(experienceId) else { return }
        acknowledgementBusy.insert(experienceId)
        Task {
            defer { acknowledgementBusy.remove(experienceId) }
            do {
                _ = try await service.acknowledge(experienceId: experienceId)
                items = items.map { $0.id == experienceId ? $0.replacingMilestone(
                    planned: $0.plannedByViewer ?? false,
                    acknowledged: true,
                    count: ($0.acknowledgementCount ?? 0) + 1
                ) : $0 }
            } catch let appError as AppError { error = appError }
            catch { self.error = .server }
        }
    }

    private func load(reset: Bool) {
        if needsNearbyLocation {
            generation += 1
            loadTask?.cancel()
            items = []
            isLoading = false
            return
        }
        if !reset && (!hasMore || isLoading || isLoadingMore) { return }
        generation += 1
        let requestID = generation
        let cursor = reset ? nil : nextCursor
        loadTask?.cancel()
        if reset { isLoading = true } else { isLoadingMore = true }
        error = nil
        let snapshotLens = lens
        let filter = discoveryFilter
        let search = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let location = coordinate
        loadTask = Task {
            do {
                let page = try await service.feed(
                    lens: snapshotLens,
                    cursor: cursor,
                    query: search.isEmpty ? nil : search,
                    primary: filter?.primary,
                    vibe: filter?.vibe,
                    latitude: location?.latitude,
                    longitude: location?.longitude,
                    radiusMeters: snapshotLens == .nearby ? 25_000 : nil
                )
                guard requestID == generation, !Task.isCancelled else { return }
                items = reset ? page.items : Self.deduplicated(items + page.items)
                nextCursor = page.nextCursor
                hasMore = page.hasMore
            } catch is CancellationError {
                return
            } catch let appError as AppError {
                guard requestID == generation else { return }
                error = appError
            } catch {
                guard requestID == generation else { return }
                self.error = .server
            }
            if requestID == generation {
                isLoading = false
                isLoadingMore = false
            }
        }
    }

    private static func deduplicated(_ values: [ExperienceSummaryV2]) -> [ExperienceSummaryV2] {
        var seen = Set<UUID>()
        return values.filter { seen.insert($0.id).inserted }
    }
}

extension ExperienceSummaryV2 {
    func replacingRelationship(for authorId: UUID, with relationship: RelationshipV2) -> Self {
        guard author.id == authorId else { return self }
        return ExperienceSummaryV2(
            id: id,
            classification: classification,
            author: ExperienceV2.Author(
                id: author.id,
                username: author.username,
                displayName: author.displayName,
                avatarUrl: author.avatarUrl,
                relationship: relationship
            ),
            place: place,
            experiencedAt: experiencedAt,
            title: title,
            titleSource: titleSource,
            primaryExperience: primaryExperience,
            feeling: feeling,
            storyPreview: storyPreview,
            tipPreview: tipPreview,
            companion: companion,
            timeOfDay: timeOfDay,
            vibes: vibes,
            practicalSignals: practicalSignals,
            mediaPreview: mediaPreview,
            mediaCount: mediaCount,
            visibility: visibility,
            plannedByViewer: plannedByViewer,
            acknowledgedByViewer: acknowledgedByViewer,
            acknowledgementCount: acknowledgementCount
        )
    }

    func replacingMilestone(planned: Bool, acknowledged: Bool, count: Int) -> Self {
        ExperienceSummaryV2(
            id: id, classification: classification, author: author, place: place,
            experiencedAt: experiencedAt, title: title, titleSource: titleSource,
            primaryExperience: primaryExperience, feeling: feeling,
            storyPreview: storyPreview, tipPreview: tipPreview, companion: companion,
            timeOfDay: timeOfDay, vibes: vibes, practicalSignals: practicalSignals,
            mediaPreview: mediaPreview, mediaCount: mediaCount, visibility: visibility,
            plannedByViewer: planned, acknowledgedByViewer: acknowledged,
            acknowledgementCount: count
        )
    }
}
