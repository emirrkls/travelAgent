import Foundation
import Observation

enum BlockPhase: Equatable, Sendable {
    case idle
    case confirming(userId: UUID, displayName: String)
    case blocking
    case unblocking
    case error(AppError)
}

@MainActor
@Observable
final class BlockController {
    private(set) var phase: BlockPhase = .idle

    private let service: any BlockServing
    private let socialState: SocialStateStore

    init(service: any BlockServing, socialState: SocialStateStore) {
        self.service = service
        self.socialState = socialState
    }

    func confirmBlock(userId: UUID, displayName: String) {
        phase = .confirming(userId: userId, displayName: displayName)
    }

    func cancelBlock() {
        phase = .idle
    }

    func executeBlock(userId: UUID) {
        guard case .confirming = phase else { return }
        phase = .blocking
        Task { [weak self] in
            guard let self else { return }
            do {
                try await service.block(userId: userId)
                socialState.invalidateUser(userId)
                phase = .idle
            } catch is CancellationError {
                return
            } catch let error as AppError {
                phase = .error(error)
            } catch {
                phase = .error(.server)
            }
        }
    }

    func executeUnblock(userId: UUID) {
        phase = .unblocking
        Task { [weak self] in
            guard let self else { return }
            do {
                try await service.unblock(userId: userId)
                phase = .idle
            } catch is CancellationError {
                return
            } catch let error as AppError {
                phase = .error(error)
            } catch {
                phase = .error(.server)
            }
        }
    }

    func dismissError() {
        phase = .idle
    }
}
