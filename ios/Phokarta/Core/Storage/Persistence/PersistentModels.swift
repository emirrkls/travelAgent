import Foundation

enum MutationType: String, Sendable, Codable, CaseIterable {
    case publishVisit = "PUBLISH_VISIT"
    case setSavedState = "SET_SAVED_STATE"
}

enum MutationState: String, Sendable, Codable, CaseIterable {
    case pending = "PENDING"
    case syncing = "SYNCING"
    case failedRetryable = "FAILED_RETRYABLE"
    case failedPermanent = "FAILED_PERMANENT"

    public static let queued = MutationState.pending
}

enum MediaUploadState: String, Sendable, Codable, CaseIterable {
    case localOnly = "LOCAL_ONLY"
    case intentCreated = "INTENT_CREATED"
    case readyRemote = "READY_REMOTE"
}

enum MediaFailureCategory {
    public static let legacyMediaReselectRequired = "LEGACY_MEDIA_RESELECT_REQUIRED"
    public static let unsupportedType = "UNSUPPORTED_TYPE"
    public static let tooLarge = "TOO_LARGE"
    public static let missingFile = "MISSING_FILE"
    public static let ownership = "OWNERSHIP"
    public static let invalidState = "INVALID_STATE"
    public static let uploadRejected = "UPLOAD_REJECTED"
}

enum SyncFailureReason: String, Sendable, Codable {
    case legacyMediaReselectRequired = "LEGACY_MEDIA_RESELECT_REQUIRED"
    case validation = "VALIDATION"
    case forbidden = "FORBIDDEN"
    case notFound = "NOT_FOUND"
    case idempotencyConflict = "IDEMPOTENCY_CONFLICT"
    case policyAcceptanceRequired = "POLICY_ACCEPTANCE_REQUIRED"
    case unknownPermanent = "UNKNOWN_PERMANENT"

    public static func from(category: String?) -> SyncFailureReason? {
        guard let category = category, !category.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }
        switch category {
        case "LEGACY_MEDIA_RESELECT_REQUIRED":
            return .legacyMediaReselectRequired
        case "VALIDATION":
            return .validation
        case "FORBIDDEN":
            return .forbidden
        case "NOT_FOUND":
            return .notFound
        case "CONFLICT":
            return .idempotencyConflict
        case "POLICY_ACCEPTANCE_REQUIRED":
            return .policyAcceptanceRequired
        case "MISSING_PAYLOAD", "INVALID_RESPONSE", "UNKNOWN_TYPE":
            return .unknownPermanent
        default:
            return .unknownPermanent
        }
    }

    public var localizationKey: String {
        switch self {
        case .legacyMediaReselectRequired:
            return "sync.failure.legacy_media_reselect"
        case .validation:
            return "sync.failure.validation"
        case .forbidden:
            return "sync.failure.forbidden"
        case .notFound:
            return "sync.failure.not_found"
        case .idempotencyConflict:
            return "sync.failure.idempotency"
        case .policyAcceptanceRequired:
            return "sync.failure.policy_acceptance"
        case .unknownPermanent:
            return "sync.failure.generic"
        }
    }
}

struct PendingVisitActions: Sendable, Equatable {
    public let showRetry: Bool
    public let showEditAndRetry: Bool
    public let showRemove: Bool
    public let showAcceptPolicy: Bool

    public init(
        showRetry: Bool = false,
        showEditAndRetry: Bool = false,
        showRemove: Bool = false,
        showAcceptPolicy: Bool = false
    ) {
        self.showRetry = showRetry
        self.showEditAndRetry = showEditAndRetry
        self.showRemove = showRemove
        self.showAcceptPolicy = showAcceptPolicy
    }

    public static func actionsFor(state: MutationState, errorCategory: String?) -> PendingVisitActions {
        switch state {
        case .failedRetryable:
            if errorCategory == "POLICY_ACCEPTANCE_REQUIRED" {
                return PendingVisitActions(showAcceptPolicy: true)
            } else {
                return PendingVisitActions(showRetry: true)
            }
        case .failedPermanent:
            let reason = SyncFailureReason.from(category: errorCategory)
            switch reason {
            case .forbidden, .notFound:
                return PendingVisitActions(showRemove: true)
            default:
                return PendingVisitActions(showEditAndRetry: true, showRemove: true)
            }
        case .pending, .syncing:
            return PendingVisitActions()
        }
    }
}

enum RecoverFailedVisitResult: Sendable, Equatable {
    case success
    case existingDraftConflict
    case notFound
    case notOwner
    case invalidState
}

enum RemoveFailedVisitResult: Sendable, Equatable {
    case success
    case notFound
    case notOwner
    case invalidState
}

// MARK: - Durable Record Structs

struct DurableVisitDraft: Sendable, Equatable {
    public let userId: UUID
    public let placeId: UUID
    public var overallScore: Double
    public var publicReview: String
    public var privateMemory: String
    public var visitedAtEpochDay: Int64
    public var visibility: String
    public var dimensionsExpanded: Bool
    public var createdAtEpochMillis: Int64
    public var updatedAtEpochMillis: Int64
    public var dimensions: [DurableDraftDimensionScore]
    public var photos: [DurableDraftPhoto]

    public init(
        userId: UUID,
        placeId: UUID,
        overallScore: Double,
        publicReview: String,
        privateMemory: String,
        visitedAtEpochDay: Int64,
        visibility: String,
        dimensionsExpanded: Bool,
        createdAtEpochMillis: Int64,
        updatedAtEpochMillis: Int64,
        dimensions: [DurableDraftDimensionScore] = [],
        photos: [DurableDraftPhoto] = []
    ) {
        self.userId = userId
        self.placeId = placeId
        self.overallScore = overallScore
        self.publicReview = publicReview
        self.privateMemory = privateMemory
        self.visitedAtEpochDay = visitedAtEpochDay
        self.visibility = visibility
        self.dimensionsExpanded = dimensionsExpanded
        self.createdAtEpochMillis = createdAtEpochMillis
        self.updatedAtEpochMillis = updatedAtEpochMillis
        self.dimensions = dimensions
        self.photos = photos
    }

    public var isExpired: Bool {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return updatedAtEpochMillis < now - VisitDraftRepositoryConstants.expiryMs
    }
}

struct DurableDraftDimensionScore: Sendable, Equatable {
    public let userId: UUID
    public let placeId: UUID
    public let dimensionKey: String
    public let score: Double

    public init(userId: UUID, placeId: UUID, dimensionKey: String, score: Double) {
        self.userId = userId
        self.placeId = placeId
        self.dimensionKey = dimensionKey
        self.score = score
    }
}

struct DurableDraftPhoto: Sendable, Equatable {
    public let ownerUserId: UUID
    public let placeId: UUID
    public var position: Int
    public let clientMediaId: UUID
    public var localRelativePath: String
    public var contentType: String
    public var byteSize: Int64
    public var width: Int?
    public var height: Int?
    public var remoteMediaId: UUID?
    public var uploadState: MediaUploadState
    public var failureCategory: String?

    public init(
        ownerUserId: UUID,
        placeId: UUID,
        position: Int,
        clientMediaId: UUID,
        localRelativePath: String,
        contentType: String,
        byteSize: Int64,
        width: Int? = nil,
        height: Int? = nil,
        remoteMediaId: UUID? = nil,
        uploadState: MediaUploadState = .localOnly,
        failureCategory: String? = nil
    ) {
        self.ownerUserId = ownerUserId
        self.placeId = placeId
        self.position = position
        self.clientMediaId = clientMediaId
        self.localRelativePath = localRelativePath
        self.contentType = contentType
        self.byteSize = byteSize
        self.width = width
        self.height = height
        self.remoteMediaId = remoteMediaId
        self.uploadState = uploadState
        self.failureCategory = failureCategory
    }
}

struct DurablePendingMutation: Sendable, Equatable {
    public let mutationId: UUID
    public let userId: UUID
    public let type: MutationType
    public let resourceKey: String
    public var state: MutationState
    public var generation: Int64
    public var attemptCount: Int
    public let createdAtEpochMillis: Int64
    public var updatedAtEpochMillis: Int64
    public var lastErrorCategory: String?

    public init(
        mutationId: UUID,
        userId: UUID,
        type: MutationType,
        resourceKey: String,
        state: MutationState,
        generation: Int64,
        attemptCount: Int,
        createdAtEpochMillis: Int64,
        updatedAtEpochMillis: Int64,
        lastErrorCategory: String? = nil
    ) {
        self.mutationId = mutationId
        self.userId = userId
        self.type = type
        self.resourceKey = resourceKey
        self.state = state
        self.generation = generation
        self.attemptCount = attemptCount
        self.createdAtEpochMillis = createdAtEpochMillis
        self.updatedAtEpochMillis = updatedAtEpochMillis
        self.lastErrorCategory = lastErrorCategory
    }
}

struct DurablePendingVisitPayload: Sendable, Equatable {
    public let mutationId: UUID
    public let placeId: UUID
    public let visitedAtEpochDay: Int64
    public let overallRating: Double
    public let publicReview: String
    public let privateMemory: String
    public let visibility: String

    public init(
        mutationId: UUID,
        placeId: UUID,
        visitedAtEpochDay: Int64,
        overallRating: Double,
        publicReview: String,
        privateMemory: String,
        visibility: String
    ) {
        self.mutationId = mutationId
        self.placeId = placeId
        self.visitedAtEpochDay = visitedAtEpochDay
        self.overallRating = overallRating
        self.publicReview = publicReview
        self.privateMemory = privateMemory
        self.visibility = visibility
    }
}

struct DurablePendingDimensionScore: Sendable, Equatable {
    public let mutationId: UUID
    public let dimensionKey: String
    public let score: Double

    public init(mutationId: UUID, dimensionKey: String, score: Double) {
        self.mutationId = mutationId
        self.dimensionKey = dimensionKey
        self.score = score
    }
}

struct DurablePendingPhoto: Sendable, Equatable {
    public let mutationId: UUID
    public var position: Int
    public let ownerUserId: UUID
    public let clientMediaId: UUID
    public var localRelativePath: String?
    public var contentType: String?
    public var byteSize: Int64?
    public var width: Int?
    public var height: Int?
    public var remoteMediaId: UUID?
    public var uploadState: MediaUploadState
    public var failureCategory: String?

    public init(
        mutationId: UUID,
        position: Int,
        ownerUserId: UUID,
        clientMediaId: UUID,
        localRelativePath: String?,
        contentType: String?,
        byteSize: Int64?,
        width: Int? = nil,
        height: Int? = nil,
        remoteMediaId: UUID? = nil,
        uploadState: MediaUploadState = .localOnly,
        failureCategory: String? = nil
    ) {
        self.mutationId = mutationId
        self.position = position
        self.ownerUserId = ownerUserId
        self.clientMediaId = clientMediaId
        self.localRelativePath = localRelativePath
        self.contentType = contentType
        self.byteSize = byteSize
        self.width = width
        self.height = height
        self.remoteMediaId = remoteMediaId
        self.uploadState = uploadState
        self.failureCategory = failureCategory
    }
}

struct PendingVisitMutationBundle: Sendable {
    public let mutation: DurablePendingMutation
    public let payload: DurablePendingVisitPayload
    public let dimensions: [DurablePendingDimensionScore]
    public let photos: [DurablePendingPhoto]

    public init(
        mutation: DurablePendingMutation,
        payload: DurablePendingVisitPayload,
        dimensions: [DurablePendingDimensionScore],
        photos: [DurablePendingPhoto]
    ) {
        self.mutation = mutation
        self.payload = payload
        self.dimensions = dimensions
        self.photos = photos
    }
}

struct PendingVisit: Sendable, Identifiable, Equatable {
    let mutationId: UUID
    let placeId: UUID
    let userId: UUID
    let visitedAt: Date
    let overallRating: Double
    let ratingDimensions: [String: Double]
    let review: String
    let personalNote: String
    let photos: [String]
    let visibility: VisitVisibility
    let state: MutationState
    let lastErrorCategory: String?

    var id: UUID { mutationId }

    var failed: Bool {
        state == .failedPermanent || state == .failedRetryable
    }

    var failureReason: SyncFailureReason? {
        if lastErrorCategory == "POLICY_ACCEPTANCE_REQUIRED" {
            return .policyAcceptanceRequired
        }
        if state == .failedPermanent {
            return SyncFailureReason.from(category: lastErrorCategory)
        }
        return nil
    }

    var actions: PendingVisitActions {
        PendingVisitActions.actionsFor(state: state, errorCategory: lastErrorCategory)
    }

    init(
        mutationId: UUID,
        placeId: UUID,
        userId: UUID,
        visitedAt: Date,
        overallRating: Double,
        ratingDimensions: [String: Double],
        review: String,
        personalNote: String,
        photos: [String],
        visibility: VisitVisibility,
        state: MutationState,
        lastErrorCategory: String? = nil
    ) {
        self.mutationId = mutationId
        self.placeId = placeId
        self.userId = userId
        self.visitedAt = visitedAt
        self.overallRating = overallRating
        self.ratingDimensions = ratingDimensions
        self.review = review
        self.personalNote = personalNote
        self.photos = photos
        self.visibility = visibility
        self.state = state
        self.lastErrorCategory = lastErrorCategory
    }
}

enum VisitDraftRepositoryConstants {
    static let expiryMs: Int64 = 30 * 24 * 60 * 60 * 1000 // 30 days
    static let autosaveDebounceMs: UInt64 = 400
}

// MARK: - Testable Clock

protocol EpochClock: Sendable {
    func nowMillis() -> Int64
}

struct SystemEpochClock: EpochClock {
    init() {}
    func nowMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}

final class TestEpochClock: EpochClock, @unchecked Sendable {
    private var currentMillis: Int64

    init(initialMillis: Int64 = 0) {
        self.currentMillis = initialMillis
    }

    func nowMillis() -> Int64 {
        return currentMillis
    }

    func advance(byMillis millis: Int64) {
        currentMillis += millis
    }

    func advanceDays(_ days: Int) {
        currentMillis += Int64(days) * 24 * 60 * 60 * 1000
    }

    func setMillis(_ millis: Int64) {
        currentMillis = millis
    }
}
