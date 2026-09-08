import Foundation

// MARK: - DTO

struct DeleteAccountRequestDTO: Encodable, Sendable {
    let currentPassword: String?

    // Redact from any description to prevent accidental logging
    var description: String { "DeleteAccountRequestDTO[currentPassword=REDACTED]" }
}

// MARK: - Endpoint

struct DeleteAccountEndpoint: APIEndpoint {
    typealias Response = EmptyPayload
    typealias Body = DeleteAccountRequestDTO

    let requestBody: DeleteAccountRequestDTO?

    var method: HTTPMethod { .delete }
    var path: String { "api/v1/me" }
    var requiresAuthentication: Bool { true }
    var allowsRetryAfterRefresh: Bool { false }
    var body: DeleteAccountRequestDTO? { requestBody }
}

// MARK: - Protocol

protocol AccountDeletionServing: Sendable {
    func deleteAccount(currentPassword: String?) async throws
}

// MARK: - Implementation

struct AccountDeletionService: AccountDeletionServing {
    private let client: APIClient

    init(client: APIClient) {
        self.client = client
    }

    func deleteAccount(currentPassword: String?) async throws {
        let body: DeleteAccountRequestDTO? = currentPassword.flatMap { pw in
            pw.isEmpty ? nil : DeleteAccountRequestDTO(currentPassword: pw)
        }
        _ = try await client.send(DeleteAccountEndpoint(requestBody: body))
    }
}
