import Foundation
import Observation

enum FriendsEmptyReason: Equatable, Sendable {
    case none
    case noFriends
    case noActivity
}

struct ScopeFeedState: Equatable, Sendable {
    var items: [ActivityEvent] = []
    var page: Int = 0
    var hasNext: Bool = false
    var isLoadingInitial: Bool = false
    var isLoadingMore: Bool = false
    var isRefreshing: Bool = false
    var errorMessage: AppError?
    var loadMoreErrorMessage: AppError?
    var friendsEmptyReason: FriendsEmptyReason = .none
    var hasLoaded: Bool = false

    init(isLoadingInitial: Bool = false) {
        self.isLoadingInitial = isLoadingInitial
    }
}

@MainActor
@Observable
final class ActivityFeedController {
    private(set) var activeScope: ActivityScope = .community
    private(set) var community = ScopeFeedState(isLoadingInitial: true)
    private(set) var friends = ScopeFeedState()
    private(set) var expandedReviewIds: Set<UUID> = []

    private let activityService: any ActivityServing
    private let socialService: any SocialServing
    private let store: SocialStateStore
    private var scopeGeneration: [ActivityScope: UInt64] = [:]
    private var didStart = false

    init(
        activityService: any ActivityServing,
        socialService: any SocialServing,
        store: SocialStateStore
    ) {
        self.activityService = activityService
        self.socialService = socialService
        self.store = store

        self.store.onFriendshipChanged = { [weak self] _, _ in
            Task { @MainActor [weak self] in
                self?.invalidateFriendsFeed()
            }
        }
    }

    var activeFeed: ScopeFeedState {
        activeScope == .friends ? friends : community
    }

    func startIfNeeded() {
        guard !didStart else { return }
        didStart = true
        Task { await loadInitial(scope: .community) }
    }

    func selectScope(_ scope: ActivityScope) {
        guard activeScope != scope else { return }
        activeScope = scope
        let current = scope == .friends ? friends : community
        if !current.hasLoaded && !current.isLoadingInitial {
            Task { await loadInitial(scope: scope) }
        }
    }

    func refresh() async {
        await refresh(scope: activeScope)
    }

    func retry() async {
        await loadInitial(scope: activeScope)
    }

    func loadNextPage() async {
        let scope = activeScope
        var current = scope == .friends ? friends : community
        guard current.hasNext, !current.isLoadingMore, !current.isLoadingInitial, !current.isRefreshing else {
            return
        }

        let nextPage = current.page + 1
        current.isLoadingMore = true
        current.loadMoreErrorMessage = nil
        setScopeState(current, for: scope)

        scopeGeneration[scope, default: 0] &+= 1
        let currentGen = scopeGeneration[scope]!

        do {
            let response = try await activityService.getActivity(scope: scope, page: nextPage, size: 20)
            guard currentGen == scopeGeneration[scope], !Task.isCancelled else { return }

            var state = scope == .friends ? friends : community
            let existingIDs = Set(state.items.map(\.visitId))
            let uniqueNewItems = response.content.filter { !existingIDs.contains($0.visitId) }
            state.items.append(contentsOf: uniqueNewItems)
            state.page = response.page
            state.hasNext = response.hasNext
            state.isLoadingMore = false
            state.loadMoreErrorMessage = nil
            setScopeState(state, for: scope)
        } catch is CancellationError {
            return
        } catch let error as AppError {
            guard currentGen == scopeGeneration[scope] else { return }
            var state = scope == .friends ? friends : community
            state.isLoadingMore = false
            state.loadMoreErrorMessage = error
            setScopeState(state, for: scope)
        } catch {
            guard currentGen == scopeGeneration[scope] else { return }
            var state = scope == .friends ? friends : community
            state.isLoadingMore = false
            state.loadMoreErrorMessage = .server
            setScopeState(state, for: scope)
        }
    }

    func retryLoadMore() async {
        await loadNextPage()
    }

    func toggleReviewExpanded(visitId: UUID) {
        if expandedReviewIds.contains(visitId) {
            expandedReviewIds.remove(visitId)
        } else {
            expandedReviewIds.insert(visitId)
        }
    }

    func invalidateFriendsFeed() {
        friends = ScopeFeedState()
        if activeScope == .friends {
            Task { await loadInitial(scope: .friends) }
        }
    }

    func loadInitial(scope: ActivityScope) async {
        var state = scope == .friends ? friends : community
        state.isLoadingInitial: true
        state.errorMessage = nil
        state.loadMoreErrorMessage = nil
        setScopeState(state, for: scope)

        scopeGeneration[scope, default: 0] &+= 1
        let currentGen = scopeGeneration[scope]!

        do {
            let response = try await activityService.getActivity(scope: scope, page: 0, size: 20)
            guard currentGen == scopeGeneration[scope], !Task.isCancelled else { return }

            let emptyReason = await resolveFriendsEmptyReason(scope: scope, isEmpty: response.content.isEmpty)
            var updated = ScopeFeedState()
            updated.items = response.content
            updated.page = response.page
            updated.hasNext = response.hasNext
            updated.isLoadingInitial = false
            updated.hasLoaded = true
            updated.friendsEmptyReason = emptyReason
            setScopeState(updated, for: scope)
        } catch is CancellationError {
            return
        } catch let error as AppError {
            guard currentGen == scopeGeneration[scope] else { return }
            var updated = ScopeFeedState()
            updated.isLoadingInitial = false
            updated.hasLoaded = true
            updated.errorMessage = error
            setScopeState(updated, for: scope)
        } catch {
            guard currentGen == scopeGeneration[scope] else { return }
            var updated = ScopeFeedState()
            updated.isLoadingInitial = false
            updated.hasLoaded = true
            updated.errorMessage = .server
            setScopeState(updated, for: scope)
        }
    }

    private func refresh(scope: ActivityScope) async {
        var state = scope == .friends ? friends : community
        if state.isLoadingInitial || state.isRefreshing { return }
        state.isRefreshing = true
        state.errorMessage = nil
        state.loadMoreErrorMessage = nil
        setScopeState(state, for: scope)

        scopeGeneration[scope, default: 0] &+= 1
        let currentGen = scopeGeneration[scope]!

        do {
            let response = try await activityService.getActivity(scope: scope, page: 0, size: 20)
            guard currentGen == scopeGeneration[scope], !Task.isCancelled else { return }

            let emptyReason = await resolveFriendsEmptyReason(scope: scope, isEmpty: response.content.isEmpty)
            var updated = ScopeFeedState()
            updated.items = response.content
            updated.page = response.page
            updated.hasNext = response.hasNext
            updated.isRefreshing = false
            updated.hasLoaded = true
            updated.friendsEmptyReason = emptyReason
            setScopeState(updated, for: scope)
        } catch is CancellationError {
            return
        } catch let error as AppError {
            guard currentGen == scopeGeneration[scope] else { return }
            var current = scope == .friends ? friends : community
            current.isRefreshing = false
            if current.items.isEmpty {
                current.errorMessage = error
            } else {
                current.loadMoreErrorMessage = error
            }
            setScopeState(current, for: scope)
        } catch {
            guard currentGen == scopeGeneration[scope] else { return }
            var current = scope == .friends ? friends : community
            current.isRefreshing = false
            if current.items.isEmpty {
                current.errorMessage = .server
            } else {
                current.loadMoreErrorMessage = .server
            }
            setScopeState(current, for: scope)
        }
    }

    private func resolveFriendsEmptyReason(scope: ActivityScope, isEmpty: Bool) async -> FriendsEmptyReason {
        guard scope == .friends, isEmpty else { return .none }
        if let owner = store.ownerProfile {
            return owner.friendCount <= 0 ? .noFriends : .noActivity
        }
        do {
            let me = try await socialService.getMeProfile()
            return me.friendCount <= 0 ? .noFriends : .noActivity
        } catch {
            return .noActivity
        }
    }

    private func setScopeState(_ state: ScopeFeedState, for scope: ActivityScope) {
        if scope == .friends {
            friends = state
        } else {
            community = state
        }
    }
}
