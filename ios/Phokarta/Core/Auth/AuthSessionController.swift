import Foundation
import Observation

@MainActor
@Observable
final class AuthSessionController {
    private(set) var state: AuthState = .restoring
    private let auth: AuthRepository
    private let sessionReset: @MainActor @Sendable () -> Void

    init(
        auth: AuthRepository,
        initialState: AuthState = .restoring,
        sessionReset: @escaping @MainActor @Sendable () -> Void = {}
    ) {
        self.auth = auth
        self.state = initialState
        self.sessionReset = sessionReset
    }

    func restore() async {
        state = .restoring
        do {
            state = try await auth.restoreSession()
        } catch is CancellationError {
            return
        } catch {
            state = .signedOut
        }
    }

    func login(identifier: String, password: String) async throws {
        let user = try await auth.login(identifier: identifier, password: password)
        sessionReset()
        state = .signedIn(user)
    }

    func register(
        email: String,
        username: String,
        displayName: String,
        password: String
    ) async throws {
        let user = try await auth.register(
            email: email,
            username: username,
            displayName: displayName,
            password: password
        )
        sessionReset()
        state = .signedIn(user)
    }

    func logout() async {
        await auth.logout()
        sessionReset()
        state = .signedOut
    }

    func handleTerminalAuthLoss() {
        sessionReset()
        state = .signedOut
    }
}
