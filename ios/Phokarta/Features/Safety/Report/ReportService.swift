import Foundation

// MARK: - Enums

enum ReportTargetType: String, Codable, Equatable, Sendable {
    case user = "USER"
    case visit = "VISIT"
}

enum ReportReason: String, CaseIterable, Codable, Equatable, Sendable {
    case spam = "SPAM"
    case harassment = "HARASSMENT"
    case hateOrAbuse = "HATE_OR_ABUSE"
    case sexualContent = "SEXUAL_CONTENT"
    case violenceOrThreat = "VIOLENCE_OR_THREAT"
    case impersonation = "IMPERSONATION"
    case privacy = "PRIVACY"
    case other = "OTHER"

    var localizedLabel: String {
        switch self {
        case .spam: String(localized: "report.reason.spam")
        case .harassment: String(localized: "report.reason.harassment")
        case .hateOrAbuse: String(localized: "report.reason.hate_or_abuse")
        case .sexualContent: String(localized: "report.reason.sexual_content")
        case .violenceOrThreat: String(localized: "report.reason.violence_or_threat")
        case .impersonation: String(localized: "report.reason.impersonation")
        case .privacy: String(localized: "report.reason.privacy")
        case .other: String(localized: "report.reason.other")
        }
    }
}

enum ReportStatus: String, Codable, Equatable, Sendable {
    case open = "OPEN"
    case reviewed = "REVIEWED"
    case actioned = "ACTIONED"
    case dismissed = "DISMISSED"
}

// MARK: - DTOs

struct CreateReportRequestDTO: Codable, Equatable, Sendable {
    let targetType: ReportTargetType
    let targetId: UUID
    let reason: ReportReason
    let details: String?
}

struct ReportResponseDTO: Decodable, Equatable, Sendable {
    let id: UUID
    let targetType: ReportTargetType
    let reason: ReportReason
    let status: ReportStatus
    let createdAt: String
}

// MARK: - Endpoint

struct SubmitReportEndpoint: APIEndpoint {
    typealias Response = ReportResponseDTO
    typealias Body = CreateReportRequestDTO

    let requestBody: CreateReportRequestDTO

    var method: HTTPMethod { .post }
    var path: String { "api/v1/reports" }
    var requiresAuthentication: Bool { true }
    var body: CreateReportRequestDTO? { requestBody }
}

// MARK: - Protocol

protocol ReportServing: Sendable {
    func submitReport(_ request: CreateReportRequestDTO) async throws -> ReportResponseDTO
}

// MARK: - Implementation

struct ReportService: ReportServing {
    private let client: APIClient

    init(client: APIClient) {
        self.client = client
    }

    func submitReport(_ request: CreateReportRequestDTO) async throws -> ReportResponseDTO {
        try await client.send(SubmitReportEndpoint(requestBody: request))
    }
}
