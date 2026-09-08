import Foundation

/// Serializes local media file creation/deletion with reconciliation so an in-flight
/// import cannot lose its `.part` or just-renamed file before persistence records ownership.
actor MediaFileMutationLock {
    init() {}

    func withLock<T: Sendable>(_ action: @Sendable () async throws -> T) async rethrows -> T {
        try await action()
    }
}
