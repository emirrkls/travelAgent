import Foundation
import Observation

@MainActor
@Observable
final class LoginController {
    var identifier = ""
    var password = ""
    var isLoading = false
    var errorMessage: String?

    private let session: AuthSessionController
    private var submitTask: Task<Void, Never>?

    init(session: AuthSessionController) {
        self.session = session
    }

    func submit() {
        errorMessage = nil
        guard AuthFieldValidator.identifier(identifier), AuthFieldValidator.password(password) else {
            errorMessage = String(localized: "auth.login.validation")
            return
        }
        submitTask?.cancel()
        submitTask = Task { await performLogin() }
    }

    func cancel() {
        submitTask?.cancel()
        submitTask = nil
        isLoading = false
    }

    private func performLogin() async {
        let submittedPassword = password
        password = ""
        isLoading = true
        defer { isLoading = false }
        do {
            try await session.login(identifier: identifier, password: submittedPassword)
        } catch is CancellationError {
            return
        } catch let error as AppError where error == .cancelled {
            return
        } catch let error as AppError {
            errorMessage = error.localizedMessage
        } catch {
            errorMessage = String(localized: "error.unknown")
        }
    }
}
