import Foundation

enum AuthState: Equatable, Sendable {
    case restoring
    case signedOut
    case signedIn(CurrentUser)
}
