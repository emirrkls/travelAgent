import Foundation
import Observation

@MainActor
@Observable
final class PolicyStatusStore {
    private(set) var status: PolicyStatus?
    private(set) var isLoading: Bool = false
    private(set) var error: AppError?
    private var generation: UInt64 = 0
    private var accountId: UUID?

    private let service: any PolicyServing

    init(service: any PolicyServing) {
        self.service = service
    }

    var requiredVersion: String? {
        status?.requiredVersion
    }

    var isAccepted: Bool {
        status?.accepted ?? false
    }

    var needsAcceptance: Bool {
        guard let status else { return false }
        return !status.accepted
    }

    func activate(accountId: UUID) {
        guard self.accountId != accountId else { return }
        clear()
        self.accountId = accountId
    }

    func clear() {
        status = nil
        isLoading = false
        error = nil
        generation &+= 1
        accountId = nil
    }

    func loadStatus() async {
        guard accountId != nil else { return }
        generation &+= 1
        let currentGen = generation
        isLoading = true
        error = nil

        do {
            let fetched = try await service.getPolicyStatus()
            guard currentGen == generation, !Task.isCancelled else { return }
            status = fetched
            isLoading = false
        } catch is CancellationError {
            return
        } catch let err as AppError {
            guard currentGen == generation else { return }
            error = err
            isLoading = false
        } catch {
            guard currentGen == generation else { return }
            self.error = .server
            isLoading = false
        }
    }

    func acceptPolicy(version: String? = nil) async -> Bool {
        guard let effectiveVersion = version ?? requiredVersion else { return false }
        generation &+= 1
        let currentGen = generation
        isLoading = true
        error = nil

        do {
            let result = try await service.acceptPolicy(version: effectiveVersion)
            guard currentGen == generation, !Task.isCancelled else { return false }
            status = result
            isLoading = false
            return result.accepted
        } catch is CancellationError {
            return false
        } catch let err as AppError {
            guard currentGen == generation else { return false }
            error = err
            isLoading = false
            return false
        } catch {
            guard currentGen == generation else { return false }
            self.error = .server
            isLoading = false
            return false
        }
    }

    /// Called when a gated mutation returns POLICY_ACCEPTANCE_REQUIRED
    func markPolicyRequired(requiredVersion: String?) {
        if let version = requiredVersion {
            status = PolicyStatus(requiredVersion: version, acceptedVersion: status?.acceptedVersion, accepted: false)
        } else {
            // Invalidate to force reload
            status = nil
        }
    }
}
