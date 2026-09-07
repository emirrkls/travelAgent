import Foundation

// MARK: - Media API Endpoints

struct UploadIntentEndpoint: APIEndpoint {
    typealias Response = MediaUploadIntentResponse
    typealias Body = MediaUploadIntentRequest
    let body: MediaUploadIntentRequest?
    var method: HTTPMethod { .post }
    var path: String { "api/v1/me/media/upload-intents" }
    var requiresAuthentication: Bool { true }
}

struct ConfirmUploadEndpoint: APIEndpoint {
    typealias Response = MediaConfirmResponse
    let mediaId: UUID
    var method: HTTPMethod { .post }
    var path: String { "api/v1/me/media/\(mediaId.uuidString.uppercased())/confirm" }
    var requiresAuthentication: Bool { true }
}

struct MediaAccessEndpoint: APIEndpoint {
    typealias Response = MediaAccessResponse
    let mediaId: UUID
    var method: HTTPMethod { .get }
    var path: String { "api/v1/media/\(mediaId.uuidString.uppercased())/access" }
    var requiresAuthentication: Bool { true }
}

// MARK: - Media Service

/// Network service for media upload operations.
///
/// Uses `APIClient` for authenticated intent/confirm/access calls.
/// Uses raw `URLSession` for presigned PUT to avoid leaking Bearer tokens to object storage.
protocol VisitMediaServing: Sendable {
    func createUploadIntent(_ request: MediaUploadIntentRequest) async throws -> MediaUploadIntentResponse
    func confirmUpload(mediaId: UUID) async throws -> MediaConfirmResponse
    func mediaAccess(mediaId: UUID) async throws -> MediaAccessResponse
    func uploadToPresignedURL(_ url: URL, data: Data, contentType: String, requiredHeaders: [String: String]) async throws
    func uploadToPresignedURL(_ url: URL, fileURL: URL, contentType: String, requiredHeaders: [String: String]) async throws
}

struct VisitMediaService: VisitMediaServing {
    let client: APIClient

    func createUploadIntent(_ request: MediaUploadIntentRequest) async throws -> MediaUploadIntentResponse {
        try await client.send(UploadIntentEndpoint(body: request))
    }

    func confirmUpload(mediaId: UUID) async throws -> MediaConfirmResponse {
        try await client.send(ConfirmUploadEndpoint(mediaId: mediaId))
    }

    func mediaAccess(mediaId: UUID) async throws -> MediaAccessResponse {
        try await client.send(MediaAccessEndpoint(mediaId: mediaId))
    }

    /// Upload file bytes directly to a presigned S3 URL.
    ///
    /// **CRITICAL:** This method does NOT attach `Authorization: Bearer` headers.
    /// The presigned URL contains auth in its query parameters.
    /// Only `requiredHeaders` from the intent response are applied.
    func uploadToPresignedURL(
        _ url: URL,
        data: Data,
        contentType: String,
        requiredHeaders: [String: String]
    ) async throws {
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        for (name, value) in requiredHeaders {
            request.setValue(value, forHTTPHeaderField: name)
        }

        let (_, response) = try await URLSession.shared.upload(for: request, from: data)
        guard let http = response as? HTTPURLResponse else {
            throw AppError.networkUnavailable
        }
        // S3 returns 200 on successful PUT
        guard (200..<300).contains(http.statusCode) else {
            if http.statusCode == 401 || http.statusCode == 403 {
                throw PresignedUploadError.urlExpiredOrForbidden
            }
            throw PresignedUploadError.httpError(http.statusCode)
        }
    }

    /// Upload from a file URL to presigned S3 URL (avoids loading full data into memory).
    ///
    /// **CRITICAL:** No `Authorization: Bearer` header is attached.
    func uploadToPresignedURL(
        _ url: URL,
        fileURL: URL,
        contentType: String,
        requiredHeaders: [String: String]
    ) async throws {
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        for (name, value) in requiredHeaders {
            request.setValue(value, forHTTPHeaderField: name)
        }

        let (_, response) = try await URLSession.shared.upload(for: request, fromFile: fileURL)
        guard let http = response as? HTTPURLResponse else {
            throw AppError.networkUnavailable
        }
        guard (200..<300).contains(http.statusCode) else {
            if http.statusCode == 401 || http.statusCode == 403 {
                throw PresignedUploadError.urlExpiredOrForbidden
            }
            throw PresignedUploadError.httpError(http.statusCode)
        }
    }
}

/// Null object pattern implementation of VisitMediaServing for testing or unconfigured environments.
struct NullVisitMediaService: VisitMediaServing {
    func createUploadIntent(_ request: MediaUploadIntentRequest) async throws -> MediaUploadIntentResponse {
        throw AppError.networkUnavailable
    }

    func confirmUpload(mediaId: UUID) async throws -> MediaConfirmResponse {
        throw AppError.networkUnavailable
    }

    func mediaAccess(mediaId: UUID) async throws -> MediaAccessResponse {
        throw AppError.networkUnavailable
    }

    func uploadToPresignedURL(
        _ url: URL,
        data: Data,
        contentType: String,
        requiredHeaders: [String: String]
    ) async throws {
        throw AppError.networkUnavailable
    }

    func uploadToPresignedURL(
        _ url: URL,
        fileURL: URL,
        contentType: String,
        requiredHeaders: [String: String]
    ) async throws {
        throw AppError.networkUnavailable
    }
}

/// Errors specific to the presigned PUT (not routed through APIClient).
enum PresignedUploadError: Error, Equatable, Sendable {
    /// Presigned URL expired or storage rejected the request.
    case urlExpiredOrForbidden
    /// Non-2xx HTTP status from object storage.
    case httpError(Int)
}
