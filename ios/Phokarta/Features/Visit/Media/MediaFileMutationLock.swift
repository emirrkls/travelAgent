import Foundation

/// Serializes local media file creation/deletion with reconciliation so an in-flight
/// import cannot lose its `.part` or just-renamed file before persistence records ownership.
actor MediaFileMutationLock {
    static let shared = MediaFileMutationLock()

    private var isLocked = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    init() {}

    func withLock<T: Sendable>(_ action: @Sendable () async throws -> T) async rethrows -> T {
        await acquire()
        defer { release() }
        return try await action()
    }

    private func acquire() async {
        if !isLocked {
            isLocked = true
            return
        }
        await withCheckedContinuation { continuation in
            waiters.append(continuation)
        }
    }

    private func release() {
        if waiters.isEmpty {
            isLocked = false
        } else {
            waiters.removeFirst().resume()
        }
    }
}
