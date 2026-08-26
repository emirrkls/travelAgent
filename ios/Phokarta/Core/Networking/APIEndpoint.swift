import Foundation

enum HTTPMethod: String, Sendable {
    case get = "GET"
    case post = "POST"
    case put = "PUT"
    case patch = "PATCH"
    case delete = "DELETE"
}

protocol APIEndpoint: Sendable {
    associatedtype Response: Decodable & Sendable
    associatedtype Body: Encodable & Sendable = Never

    var method: HTTPMethod { get }
    var path: String { get }
    var queryItems: [URLQueryItem] { get }
    var body: Body? { get }
    var requiresAuthentication: Bool { get }
    var allowsRetryAfterRefresh: Bool { get }
}

extension APIEndpoint {
    var queryItems: [URLQueryItem] { [] }
    var allowsRetryAfterRefresh: Bool { requiresAuthentication }
}

extension APIEndpoint where Body == Never {
    var body: Never? { nil }
}

struct EmptyPayload: Decodable, Sendable {
    init() {}

    init(from decoder: Decoder) throws {
        self.init()
    }
}
