import Foundation
import CoreLocation

enum LocationAuthorizationStatus: Sendable, Equatable {
    case notDetermined
    case restricted
    case denied
    case authorized
}

enum LocationError: Error, Sendable, Equatable {
    case denied
    case restricted
    case unavailable
}

@MainActor
protocol LocationProviding: AnyObject, Sendable {
    var authorizationStatus: LocationAuthorizationStatus { get }
    func requestAuthorization()
    func requestLocation() async -> Result<CLLocationCoordinate2D, LocationError>
}

@MainActor
final class CoreLocationService: NSObject, LocationProviding, CLLocationManagerDelegate {
    private let manager: CLLocationManager
    private var locationContinuation: CheckedContinuation<Result<CLLocationCoordinate2D, LocationError>, Never>?

    override init() {
        self.manager = CLLocationManager()
        super.init()
        self.manager.delegate = self
        self.manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    var authorizationStatus: LocationAuthorizationStatus {
        switch manager.authorizationStatus {
        case .notDetermined: return .notDetermined
        case .restricted: return .restricted
        case .denied: return .denied
        case .authorizedWhenInUse, .authorizedAlways: return .authorized
        @unknown default: return .notDetermined
        }
    }

    func requestAuthorization() {
        manager.requestWhenInUseAuthorization()
    }

    func requestLocation() async -> Result<CLLocationCoordinate2D, LocationError> {
        let status = authorizationStatus
        guard status != .denied else { return .failure(.denied) }
        guard status != .restricted else { return .failure(.restricted) }

        if status == .notDetermined {
            requestAuthorization()
        }

        return await withCheckedContinuation { continuation in
            self.locationContinuation = continuation
            self.manager.requestLocation()
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        // Handled reactively on next request
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        Task { @MainActor in
            guard let location = locations.last?.coordinate else {
                self.locationContinuation?.resume(returning: .failure(.unavailable))
                self.locationContinuation = nil
                return
            }
            self.locationContinuation?.resume(returning: .success(location))
            self.locationContinuation = nil
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in
            self.locationContinuation?.resume(returning: .failure(.unavailable))
            self.locationContinuation = nil
        }
    }
}

@MainActor
final class MockLocationService: LocationProviding {
    var authorizationStatus: LocationAuthorizationStatus = .notDetermined
    var mockResult: Result<CLLocationCoordinate2D, LocationError> = .failure(.unavailable)
    var requestAuthorizationCalled = false

    func requestAuthorization() {
        requestAuthorizationCalled = true
    }

    func requestLocation() async -> Result<CLLocationCoordinate2D, LocationError> {
        mockResult
    }
}
