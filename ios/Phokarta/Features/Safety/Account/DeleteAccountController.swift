import Foundation
import Observation

enum DeleteAccountPhase: Equatable, Sendable {
    case idle
    case confirming
    case deleting
    case credentialFailure(String)
    case retryableFailure(String)
    case success
}

@MainActor
@Observable
final class DeleteAccountController {
    private(set) var phase: DeleteAccountPhase = .idle
    var currentPassword: String = ""

    private let service: any AccountDeletionServing
    private let purger: any LocalAccountPurger
    private let sessionReset: @MainActor @Sendable () -> Void
    private let signOut: @MainActor @Sendable () async -> Void
    private let currentUserId: @MainActor @Sendable () -> UUID?
    private var deletionTask: Task<Void, Never>?

    init(
        service: any AccountDeletionServing,
        purger: any LocalAccountPurger,
        sessionReset: @escaping @MainActor @Sendable () -> Void,
        signOut: @escaping @MainActor @Sendable () async -> Void,
        currentUserId: @escaping @MainActor @Sendable () -> UUID?
    ) {
        self.service = service
        self.purger = purger
        self.sessionReset = sessionReset
        self.signOut = signOut
        self.currentUserId = currentUserId
    }

    func showConfirmation() {
        currentPassword = ""
        phase = .confirming
    }

    func cancelDeletion() {
        phase = .idle
        currentPassword = ""
    }

    func confirmDeletion() {
        guard phase == .confirming else { return }
        // One active deletion task
        guard deletionTask == nil else { return }
        phase = .deleting

        let password = currentPassword
        deletionTask = Task { [weak self] in
            guard let self else { return }
            defer { deletionTask = nil }

            let userId = currentUserId()

            do {
                try await service.deleteAccount(currentPassword: password.isEmpty ? nil : password)
                // Canonical success: perform teardown
                await performLocalTeardown(userId: userId)
                phase = .success
            } catch is CancellationError {
                phase = .confirming
                return
            } catch let error as AppError {
                switch error {
                case .networkUnavailable, .timeout:
                    phase = .retryableFailure(error.localizedMessage)
                case .validation(let message, _):
                    phase = .credentialFailure(message)
                case .unauthorized:
                    // Account may already be deleted (lost-ACK scenario)
                    // Converge to signed-out with purge
                    await performLocalTeardown(userId: userId)
                    phase = .success
                default:
                    if let code = Self.extractCode(error), code == "INVALID_CURRENT_PASSWORD" {
                        phase = .credentialFailure(String(localized: "delete_account.invalid_password"))
                    } else {
                        phase = .retryableFailure(error.localizedMessage)
                    }
                }
            } catch {
                phase = .retryableFailure(String(localized: "error.unknown"))
            }
        }
    }

    func retryDeletion() {
        phase = .confirming
    }

    private func performLocalTeardown(userId: UUID?) {
        // 1. Reset in-memory stores
        sessionReset()

        // 2. Purge durable local data
        if let userId {
            Task {
                try? await purger.purgeLocalData(userId: userId)
            }
        }

        // 3. Clear session / sign out
        Task {
            await signOut()
        }
    }

    private static func extractCode(_ error: AppError) -> String? {
        if case .validation(let msg, _) = error {
            return msg.contains("password") ? "INVALID_CURRENT_PASSWORD" : nil
        }
        return nil
    }
}
