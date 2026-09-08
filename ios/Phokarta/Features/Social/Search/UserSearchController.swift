import Foundation
import Observation

@MainActor
@Observable
final class UserSearchController {
    private(set) var query: String = ""
    private(set) var items: [UserSummary] = []
    private(set) var page: Int = 0
    private(set) var hasNext: Bool = false
    private(set) var isLoading: Bool = false
    private(set) var isLoadingMore: Bool = false
    private(set) var errorMessage: AppError?
    private(set) var loadMoreErrorMessage: AppError?

    private let service: any SocialServing
    private let store: SocialStateStore
    private var generation: UInt64 = 0
    private var debounceTask: Task<Void, Never>?

    init(
        service: any SocialServing,
        store: SocialStateStore
    ) {
        self.service = service
        self.store = store
    }

    func effectiveRelationship(for user: UserSummary) -> RelationshipState? {
        store.relationship(for: user.id) ?? user.relationship
    }

    func isMutating(userId: UUID) -> Bool {
        store.isBusy(userId)
    }

    func setQuery(_ text: String) {
        query = text
        debounceTask?.cancel()
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            generation &+= 1
            items = []
            page = 0
            hasNext = false
            isLoading = false
            errorMessage = nil
            return
        }

        debounceTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 300_000_000)
            guard !Task.isCancelled else { return }
            await self?.performSearch(query: trimmed, page: 0)
        }
    }

    func searchImmediate(_ text: String) {
        query = text
        debounceTask?.cancel()
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            generation &+= 1
            items = []
            page = 0
            hasNext = false
            isLoading = false
            errorMessage = nil
            return
        }
        Task { [weak self] in
            await self?.performSearch(query: trimmed, page: 0)
        }
    }

    func retry() {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        Task { [weak self] in
            await self?.performSearch(query: trimmed, page: 0)
        }
    }

    func loadNextPage() async {
        guard hasNext, !isLoadingMore, !isLoading else { return }
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        isLoadingMore = true
        loadMoreErrorMessage = nil

        generation &+= 1
        let currentGen = generation
        let nextPage = page + 1

        do {
            let response = try await service.searchUsers(query: trimmed, page: nextPage, size: 20)
            guard currentGen == generation, !Task.isCancelled else { return }

            let filtered = response.content.filter { $0.id != store.accountID }
            let existingIDs = Set(items.map(\.id))
            let uniqueNew = filtered.filter { !existingIDs.contains($0.id) }
            items.append(contentsOf: uniqueNew)
            page = response.page
            hasNext = response.hasNext
            isLoadingMore = false
            for user in filtered {
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

    func toggleFollow(for user: UserSummary) {
        store.toggleFollow(targetId: user.id, seedRelationship: user.relationship)
    }

    private func performSearch(query: String, page targetPage: Int) async {
        isLoading = true
        errorMessage = nil
        loadMoreErrorMessage = nil

        generation &+= 1
        let currentGen = generation

        do {
            let response = try await service.searchUsers(query: query, page: targetPage, size: 20)
            guard currentGen == generation, !Task.isCancelled else { return }

            let filtered = response.content.filter { $0.id != store.accountID }
            items = filtered
            page = response.page
            hasNext = response.hasNext
            isLoading = false
            for user in filtered {
                store.recordConfirmedRelationship(user.relationship, for: user.id)
            }
        } catch is CancellationError {
            return
        } catch let error as AppError {
            guard currentGen == generation else { return }
            isLoading = false
            errorMessage = error
        } catch {
            guard currentGen == generation else { return }
            isLoading = false
            errorMessage = .server
        }
    }
}
