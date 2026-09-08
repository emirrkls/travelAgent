import Foundation
import Observation

@MainActor
@Observable
public final class SocialListController {
    public let kind: SocialListKind
    public private(set) var items: [UserSummary] = []
    public private(set) var page: Int = 0
    public private(set) var hasNext: Bool = false
    public private(set) var isLoadingInitial: Bool = false
    public private(set) var isLoadingMore: Bool = false
    public private(set) var isRefreshing: Bool = false
    public private(set) var errorMessage: AppError?
    public private(set) var loadMoreErrorMessage: AppError?

    private let service: any SocialServing
    private let store: SocialStateStore
    private var generation: UInt64 = 0
    private var didStart = false

    public init(
        kind: SocialListKind,
        service: any SocialServing,
        store: SocialStateStore
    ) {
        self.kind = kind
        self.service = service
        self.store = store
    }

    public func effectiveRelationship(for user: UserSummary) -> RelationshipState? {
        store.relationship(for: user.id) ?? user.relationship
    }

    public func isMutating(userId: UUID) -> Bool {
        store.isBusy(userId)
    }

    public func startIfNeeded() {
        guard !didStart else { return }
        didStart = true
        Task { await loadInitial() }
    }

    public func refresh() async {
        if isLoadingInitial || isRefreshing { return }
        isRefreshing = true
        errorMessage = nil
        loadMoreErrorMessage = nil

        generation &+= 1
        let currentGen = generation

        do {
            let response = try await fetchPage(0)
            guard currentGen == generation, !Task.isCancelled else { return }
            items = response.content
            page = response.page
            hasNext = response.hasNext
            isRefreshing = false
            for user in response.content {
                store.recordConfirmedRelationship(user.relationship, for: user.id)
            }
        } catch is CancellationError {
            return
        } catch let error as AppError {
            guard currentGen == generation else { return }
            isRefreshing = false
            if items.isEmpty {
                errorMessage = error
            } else {
                loadMoreErrorMessage = error
            }
        } catch {
            guard currentGen == generation else { return }
            isRefreshing = false
            if items.isEmpty {
                errorMessage = .server
            } else {
                loadMoreErrorMessage = .server
            }
        }
    }

    public func loadNextPage() async {
        guard hasNext, !isLoadingMore, !isLoadingInitial, !isRefreshing else { return }
        isLoadingMore = true
        loadMoreErrorMessage = nil

        generation &+= 1
        let currentGen = generation
        let nextPage = page + 1

        do {
            let response = try await fetchPage(nextPage)
            guard currentGen == generation, !Task.isCancelled else { return }
            let existingIDs = Set(items.map(\.id))
            let uniqueNew = response.content.filter { !existingIDs.contains($0.id) }
            items.append(contentsOf: uniqueNew)
            page = response.page
            hasNext = response.hasNext
            isLoadingMore = false
            for user in response.content {
                store.recordConfirmedRelationship(user.relationship, for: user.id)
            }
        } catch is CancellationError {
            return
        } catch let error as AppError {
            guard currentGen == generation else { return }
            isLoadingMore = false
            loadMoreErrorMessage = error
        } catch {
            guard currentGen == generation else { return }
            isLoadingMore = false
            loadMoreErrorMessage = .server
        }
    }

    public func toggleFollow(for user: UserSummary) {
        store.toggleFollow(targetId: user.id, seedRelationship: user.relationship)
    }

    private func loadInitial() async {
        isLoadingInitial = true
        errorMessage = nil
        loadMoreErrorMessage = nil

        generation &+= 1
        let currentGen = generation

        do {
            let response = try await fetchPage(0)
            guard currentGen == generation, !Task.isCancelled else { return }
            items = response.content
            page = response.page
            hasNext = response.hasNext
            isLoadingInitial = false
            for user in response.content {
                store.recordConfirmedRelationship(user.relationship, for: user.id)
            }
        } catch is CancellationError {
            return
        } catch let error as AppError {
            guard currentGen == generation else { return }
            isLoadingInitial = false
            errorMessage = error
        } catch {
            guard currentGen == generation else { return }
            isLoadingInitial = false
            errorMessage = .server
        }
    }

    private func fetchPage(_ pageNumber: Int) async throws -> SocialPageResponse<UserSummary> {
        switch kind {
        case .followers:
            return try await service.getFollowers(page: pageNumber, size: 20)
        case .following:
            return try await service.getFollowing(page: pageNumber, size: 20)
        case .friends:
            return try await service.getFriends(page: pageNumber, size: 20)
        }
    }
}
