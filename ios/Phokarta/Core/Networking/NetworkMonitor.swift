import Foundation
import Network

public protocol NetworkMonitoring: Sendable {
    var isConnected: Bool { get }
    func observePathUpdates() -> AsyncStream<Bool>
}

public final class SystemNetworkMonitor: NetworkMonitoring, @unchecked Sendable {
    private let monitor: NWPathMonitor
    private let queue = DispatchQueue(label: "com.emirrkls.phokarta.networkmonitor")
    private var currentPath: NWPath?
    private let lock = NSLock()
    private var continuations: [UUID: AsyncStream<Bool>.Continuation] = [:]

    public init() {
        self.monitor = NWPathMonitor()
        self.monitor.pathUpdateHandler = { [weak self] path in
            self?.handleUpdate(path)
        }
        self.monitor.start(queue: queue)
    }

    deinit {
        monitor.cancel()
    }

    public var isConnected: Bool {
        lock.lock()
        defer { lock.unlock() }
        return currentPath?.status == .satisfied
    }

    public func observePathUpdates() -> AsyncStream<Bool> {
        AsyncStream { continuation in
            let id = UUID()
            lock.lock()
            continuations[id] = continuation
            let current = currentPath?.status == .satisfied
            lock.unlock()

            continuation.yield(current)

            continuation.onTermination = { [weak self] _ in
                self?.removeContinuation(id: id)
            }
        }
    }

    private func handleUpdate(_ path: NWPath) {
        lock.lock()
        currentPath = path
        let satisfied = path.status == .satisfied
        let activeContinuations = Array(continuations.values)
        lock.unlock()

        for continuation in activeContinuations {
            continuation.yield(satisfied)
        }
    }

    private func removeContinuation(id: UUID) {
        lock.lock()
        defer { lock.unlock() }
        continuations.removeValue(forKey: id)
    }
}

public final class TestNetworkMonitor: NetworkMonitoring, @unchecked Sendable {
    private var connected: Bool
    private let lock = NSLock()
    private var continuations: [UUID: AsyncStream<Bool>.Continuation] = [:]

    public init(initialConnected: Bool = true) {
        self.connected = initialConnected
    }

    public var isConnected: Bool {
        lock.lock()
        defer { lock.unlock() }
        return connected
    }

    public func setConnected(_ value: Bool) {
        lock.lock()
        connected = value
        let active = Array(continuations.values)
        lock.unlock()

        for c in active {
            c.yield(value)
        }
    }

    public func observePathUpdates() -> AsyncStream<Bool> {
        AsyncStream { continuation in
            let id = UUID()
            lock.lock()
            continuations[id] = continuation
            let current = connected
            lock.unlock()

            continuation.yield(current)

            continuation.onTermination = { [weak self] _ in
                self?.lock.lock()
                self?.continuations.removeValue(forKey: id)
                self?.lock.unlock()
            }
        }
    }
}
