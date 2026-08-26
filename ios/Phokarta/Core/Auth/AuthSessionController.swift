import Foundation
import Observation

@MainActor
@Observable
final class AuthSessionController {
    private(set) var state: AuthState = .restoring
    private let auth: AuthRepository

    init(auth: AuthRepository, initialState: AuthState = .restoring) {
        self.auth = auth
        self.state = initialState
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
        state = .signedIn(user)
    }

    func logout() async {
        await auth.logout()
        state = .signedOut
    }

    func handleTerminalAuthLoss() {
        state = .signedOut
    }
}
