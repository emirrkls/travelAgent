import Foundation

// MARK: - Backend Media DTOs

/// Upload intent request — sent to `POST /api/v1/me/media/upload-intents`.
struct MediaUploadIntentRequest: Encodable, Equatable, Sendable {
    let clientMediaId: UUID
    let contentType: String
    let byteSize: Int64
    let width: Int?
    let height: Int?
}

/// Upload intent response — returned by backend with presigned upload URL.
struct MediaUploadIntentResponse: Decodable, Equatable, Sendable {
    let mediaId: UUID
    let status: MediaAssetStatus
    let uploadUrl: URL?
    let requiredHeaders: [String: String]?
    let expiresAt: String?
}

/// Confirm response — returned by `POST /api/v1/me/media/{mediaId}/confirm`.
struct MediaConfirmResponse: Decodable, Equatable, Sendable {
    let mediaId: UUID
    let status: MediaAssetStatus
}

/// Media access response — returned by `GET /api/v1/media/{mediaId}/access`.
struct MediaAccessResponse: Decodable, Equatable, Sendable {
    let url: URL
    let expiresAt: String
}

/// Visit media attachment — returned inline in VisitOwnerResponse.
struct VisitMediaDTO: Decodable, Equatable, Sendable, Identifiable {
    let id: UUID
    let sortOrder: Int
    let accessUrl: URL?
    let accessExpiresAt: String?
}

/// Backend media asset status enum.
enum MediaAssetStatus: String, Codable, Equatable, Sendable {
    case pendingUpload = "PENDING_UPLOAD"
    case ready = "READY"
    case attached = "ATTACHED"
    case deleting = "DELETING"
}

// MARK: - Contract Constants

/// Backend media contract constants derived from `application.yml`.
enum MediaContract {
    /// Maximum upload file size in bytes (15 MB).
    static let maxBytes: Int64 = 15_728_640

    /// Maximum number of media items per Visit.
    static let maxPerVisit = 20

    /// Allowed upload content types.
    static let acceptedContentTypes: Set<String> = [
        "image/jpeg",
        "image/png",
        "image/webp"
    ]

    /// Upload presigned URL TTL (informational; server controls expiry).
    static let uploadTTLMinutes = 15

    /// Signed read URL TTL (informational; server controls expiry).
    static let readTTLMinutes = 10
}
