import Foundation
import Observation

@MainActor
@Observable
final class RegisterController {
    var email = ""
    var username = ""
    var displayName = ""
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
        if !AuthFieldValidator.displayName(displayName) {
            errorMessage = String(localized: "auth.display_name.required")
            return
        }
        if !AuthFieldValidator.username(username) {
            errorMessage = String(localized: "auth.username.invalid")
            return
        }
        if !AuthFieldValidator.email(email) {
            errorMessage = String(localized: "auth.email.invalid")
            return
        }
        if !AuthFieldValidator.password(password) {
            errorMessage = String(localized: "auth.password.min")
            return
        }
        submitTask?.cancel()
        submitTask = Task { await performRegister() }
    }

    func cancel() {
        submitTask?.cancel()
        submitTask = nil
        isLoading = false
    }

    private func performRegister() async {
        let submittedPassword = password
        password = ""
        isLoading = true
        defer { isLoading = false }
        do {
            try await session.register(
                email: email,
                username: username,
                displayName: displayName,
                password: submittedPassword
            )
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
