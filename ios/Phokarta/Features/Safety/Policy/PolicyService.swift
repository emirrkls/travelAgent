import Foundation

// MARK: - DTOs

struct PolicyStatus: Codable, Equatable, Sendable {
    let requiredVersion: String
    let acceptedVersion: String?
    let accepted: Bool
}

struct PolicyAcceptanceRequestDTO: Codable, Equatable, Sendable {
    let policyVersion: String
}

// MARK: - Endpoints

struct PolicyStatusEndpoint: APIEndpoint {
    typealias Response = PolicyStatus
    var method: HTTPMethod { .get }
    var path: String { "api/v1/me/policy-status" }
    var requiresAuthentication: Bool { true }
}

struct PolicyAcceptanceEndpoint: APIEndpoint {
    typealias Response = PolicyStatus
    typealias Body = PolicyAcceptanceRequestDTO

    let requestBody: PolicyAcceptanceRequestDTO

    var method: HTTPMethod { .post }
    var path: String { "api/v1/me/policy-acceptance" }
    var requiresAuthentication: Bool { true }
    var body: PolicyAcceptanceRequestDTO? { requestBody }
}

// MARK: - Protocol

protocol PolicyServing: Sendable {
    func getPolicyStatus() async throws -> PolicyStatus
    func acceptPolicy(version: String) async throws -> PolicyStatus
}

// MARK: - Implementation

struct PolicyService: PolicyServing {
    private let client: APIClient

    init(client: APIClient) {
        self.client = client
    }

    func getPolicyStatus() async throws -> PolicyStatus {
        try await client.send(PolicyStatusEndpoint())
    }

    func acceptPolicy(version: String) async throws -> PolicyStatus {
        try await client.send(
            PolicyAcceptanceEndpoint(
                requestBody: PolicyAcceptanceRequestDTO(policyVersion: version)
            )
        )
    }
}
