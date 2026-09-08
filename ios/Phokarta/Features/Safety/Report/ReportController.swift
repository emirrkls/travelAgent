import Foundation
import Observation

enum ReportPhase: Equatable, Sendable {
    case idle
    case selectingReason
    case submitting
    case success(isDuplicate: Bool)
    case rateLimited
    case error(AppError)
}

@MainActor
@Observable
final class ReportController {
    private(set) var phase: ReportPhase = .idle
    private(set) var target: ReportTarget?
    var selectedReason: ReportReason?
    var details: String = ""

    private let service: any ReportServing
    private var ownerAccountId: UUID?

    static let maxDetailsLength = 2000

    init(service: any ReportServing) {
        self.service = service
    }

    func activate(accountId: UUID) {
        ownerAccountId = accountId
    }

    func open(target: ReportTarget) {
        // Account switch safety: discard stale form
        self.target = target
        selectedReason = nil
        details = ""
        phase = .selectingReason
    }

    func cancel() {
        phase = .idle
        target = nil
        selectedReason = nil
        details = ""
    }

    func submit() {
        guard let target, let reason = selectedReason else { return }
        guard phase == .selectingReason else { return }
        phase = .submitting

        let trimmedDetails = details.trimmingCharacters(in: .whitespacesAndNewlines)
        let effectiveDetails: String? = trimmedDetails.isEmpty ? nil : String(trimmedDetails.prefix(Self.maxDetailsLength))

        let request = CreateReportRequestDTO(
            targetType: target.targetType,
            targetId: target.targetId,
            reason: reason,
            details: effectiveDetails
        )

        Task { [weak self] in
            guard let self else { return }
            do {
                let response = try await service.submitReport(request)
                // 201 = new, 200 = duplicate OPEN
                // We detect duplicate via response status being returned as-is
                // The API client doesn't distinguish 200 vs 201 in the response object,
                // but the backend returns the existing report for duplicates.
                // We treat both as success.
                phase = .success(isDuplicate: false)
            } catch is CancellationError {
                return
            } catch let error as AppError {
                if error == .rateLimited {
                    phase = .rateLimited
                } else {
                    phase = .error(error)
                }
            } catch {
                phase = .error(.server)
            }
        }
    }

    func retry() {
        phase = .selectingReason
    }

    func dismiss() {
        phase = .idle
        target = nil
        selectedReason = nil
        details = ""
    }

    /// Account switch: discard any in-flight form for the old account
    func handleAccountSwitch() {
        cancel()
    }
}
