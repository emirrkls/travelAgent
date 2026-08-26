import Foundation

protocol HTTPTransport: Sendable {
    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse)
}

struct URLSessionTransport: HTTPTransport {
    let session: URLSession

    static let `default` = URLSessionTransport(session: makeSession())

    init(session: URLSession) {
        self.session = session
    }

    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw AppError.networkUnavailable
            }
            return (data, http)
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError {
            switch error.code {
            case .cancelled:
                throw CancellationError()
            case .timedOut:
                throw AppError.timeout
            default:
                throw AppError.networkUnavailable
            }
        }
    }

    private static func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 30
        configuration.waitsForConnectivity = false
        configuration.httpAdditionalHeaders = [
            "Accept": "application/json",
        ]
        // Default URLSession delegate: system TLS trust evaluation only. No trust-all.
        return URLSession(configuration: configuration)
    }
}

protocol AuthRetryHandling: Sendable {
    func accessToken() async -> String?
    func refreshAfterUnauthorized(failedAccessToken: String?) async throws -> String
}
