import Foundation

// MARK: - Per-Item Lifecycle

/// Explicit lifecycle phase for a single media item in the Visit composer.
/// Each item progresses through these phases during the upload pipeline.
enum VisitMediaItemPhase: Equatable, Sendable {
    /// Picked from the photo library; not yet prepared.
    case selected
    /// Transcoding / GPS stripping / thumbnail generation in progress.
    case preparing
    /// Sanitized bytes on disk and ready for upload intent.
    case readyForIntent
    /// Upload intent API call in flight.
    case requestingIntent
    /// Presigned PUT in flight. Associated value is progress (0.0 – 1.0).
    case uploading(Double)
    /// PUT succeeded; confirmation not yet requested.
    case uploaded
    /// Confirm API call in flight.
    case confirming
    /// Backend confirmed; canonical media asset ID is available.
    case confirmed
    /// Recoverable failure — user can retry.
    case failedRetryable(String)
    /// Non-recoverable failure — item must be removed.
    case failedPermanent(String)

    var isTerminal: Bool {
        switch self {
        case .confirmed, .failedPermanent: true
        default: false
        }
    }

    var isActive: Bool {
        switch self {
        case .preparing, .requestingIntent, .uploading, .uploaded, .confirming: true
        default: false
        }
    }

    var isRetryable: Bool {
        if case .failedRetryable = self { return true }
        return false
    }

    var isFailed: Bool {
        switch self {
        case .failedRetryable, .failedPermanent: true
        default: false
        }
    }
}

// MARK: - Media Item

/// Represents a single media item within the Visit composer.
///
/// - `id` is the client-generated identity (`clientMediaId`) used for backend intent idempotency.
/// - `canonicalMediaId` is the backend-assigned UUID available only after confirmation.
/// - `localTempURL` points to the sanitized file in `NSTemporaryDirectory()`.
struct VisitMediaItem: Identifiable, Equatable, Sendable {
    /// Client-generated UUID used as `clientMediaId` for intent idempotency.
    let id: UUID
    /// Current lifecycle phase.
    var phase: VisitMediaItemPhase
    /// Lightweight thumbnail data for composer UI (≤ 200pt).
    var thumbnailData: Data?
    /// Final upload byte count after sanitization.
    var uploadBytes: Int64?
    /// Final MIME content type after transcoding (e.g. `image/jpeg`).
    var contentType: String?
    /// Image pixel width.
    var width: Int?
    /// Image pixel height.
    var height: Int?
    /// Backend-assigned canonical media asset UUID (available after `.confirmed`).
    var canonicalMediaId: UUID?
    /// Temporary file URL for sanitized upload bytes.
    var localTempURL: URL?

    init(id: UUID = UUID(), phase: VisitMediaItemPhase = .selected) {
        self.id = id
        self.phase = phase
    }
}
