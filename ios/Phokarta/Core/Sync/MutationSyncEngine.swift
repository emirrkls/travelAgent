import Foundation

struct SyncRunResult: Sendable, Equatable {
    let retryableFailure: Bool
    let processed: Int

    init(retryableFailure: Bool, processed: Int) {
        self.retryableFailure = retryableFailure
        self.processed = processed
    }
}

protocol SessionOwnerProvider: Sendable {
    func currentUserId() async -> UUID?
}

actor MutationSyncEngine {
    private let mutationRepository: any OfflineMutationRepository
    private let draftRepository: any VisitDraftRepository
    private let mediaStore: any DurableMediaStoring
    private let mediaService: any VisitMediaServing
    private let visitService: any VisitServing
    private let visitStore: VisitStore?
    private let sessionProvider: any SessionOwnerProvider
    private let clock: any EpochClock
    private var isDraining = false

    init(
        mutationRepository: any OfflineMutationRepository,
        draftRepository: any VisitDraftRepository,
        mediaStore: any DurableMediaStoring,
        mediaService: any VisitMediaServing,
        visitService: any VisitServing,
        visitStore: VisitStore? = nil,
        sessionProvider: any SessionOwnerProvider,
        clock: any EpochClock = SystemEpochClock()
    ) {
        self.mutationRepository = mutationRepository
        self.draftRepository = draftRepository
        self.mediaStore = mediaStore
        self.mediaService = mediaService
        self.visitService = visitService
        self.visitStore = visitStore
        self.sessionProvider = sessionProvider
        self.clock = clock
    }

    func drain(batchSize: Int = 20) async -> SyncRunResult {
        guard !isDraining else {
            return SyncRunResult(retryableFailure: false, processed: 0)
        }
        isDraining = true
        defer { isDraining = false }

        guard let userId = await sessionProvider.currentUserId() else {
            return SyncRunResult(retryableFailure: false, processed: 0)
        }

        // Interrupted SYNCING recovery: any row observed in SYNCING on drain start
        // belongs to a previous killed run and is safe to retry.
        try? await mutationRepository.recoverStaleSyncing(now: Date(timeIntervalSince1970: Double(clock.nowMillis()) / 1000.0))

        var retryable = false
        var processed = 0
        var pauseVisitPublishes = false

        guard let eligible = try? await mutationRepository.getEligibleMutations(userId: userId, limit: batchSize) else {
            return SyncRunResult(retryableFailure: false, processed: 0)
        }

        for mutation in eligible {
            // Verify current session owner hasn't changed
            guard let currentId = await sessionProvider.currentUserId(), currentId == mutation.userId else {
                break
            }

            if pauseVisitPublishes && mutation.type == .publishVisit {
                continue
            }

            // Atomically claim the mutation
            let now = Date(timeIntervalSince1970: Double(clock.nowMillis()) / 1000.0)
            guard let claimed = try? await mutationRepository.claim(mutationId: mutation.mutationId, now: now), claimed else {
                continue
            }

            let outcome: Outcome
            switch mutation.type {
            case .publishVisit:
                outcome = await syncVisit(mutation: mutation, userId: userId)
            case .setSavedState:
                outcome = .failure(retryable: false, category: "UNSUPPORTED_TYPE")
            }

            processed += 1

            switch outcome {
            case .success:
                break
            case .failure(let isRetryable, let category):
                retryable = retryable || isRetryable
                let newState: MutationState = isRetryable ? .failedRetryable : .failedPermanent
                try? await mutationRepository.markFailure(
                    mutationId: mutation.mutationId,
                    generation: mutation.generation,
                    state: newState,
                    category: category,
                    now: Date(timeIntervalSince1970: Double(clock.nowMillis()) / 1000.0)
                )
                if category == "POLICY_ACCEPTANCE_REQUIRED" && mutation.type == .publishVisit {
                    pauseVisitPublishes = true
                }
            }
        }

        return SyncRunResult(retryableFailure: retryable, processed: processed)
    }

    private func syncVisit(mutation: DurablePendingMutation, userId: UUID) async -> Outcome {
        guard let bundle = try? await mutationRepository.getVisitBundle(mutationId: mutation.mutationId) else {
            return .failure(retryable: false, category: "MISSING_PAYLOAD")
        }

        let orderedPhotos = bundle.photos.sorted { $0.position < $1.position }
        var mediaIds: [UUID] = []

        for photo in orderedPhotos {
            let photoOutcome = await prepareAndUploadPhoto(mutation: mutation, photo: photo, userId: userId)
            switch photoOutcome {
            case .photoPrepared(let mediaId):
                mediaIds.append(mediaId)
            case .photoFailed(let failure):
                return failure
            }
        }

        // Format visitedAt string (yyyy-MM-dd)
        let visitedDate = Date(timeIntervalSince1970: Double(bundle.payload.visitedAtEpochDay) * 86400)
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .iso8601)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        formatter.dateFormat = "yyyy-MM-dd"
        let visitedAtStr = formatter.string(from: visitedDate)

        let dimensions = bundle.dimensions.map { VisitDimensionScore(key: $0.dimensionKey, score: $0.score) }
            .sorted { $0.key < $1.key }

        let request = VisitCreateRequest(
            clientMutationId: mutation.mutationId,
            placeId: bundle.payload.placeId,
            visitedAt: visitedAtStr,
            overallRating: bundle.payload.overallRating,
            dimensions: dimensions,
            publicReview: VisitValidation.trimmedOptional(bundle.payload.publicReview),
            privateMemory: VisitValidation.trimmedOptional(bundle.payload.privateMemory),
            mediaIds: mediaIds.isEmpty ? nil : mediaIds,
            visibility: VisitVisibility(rawValue: bundle.payload.visibility) ?? .publicAccess
        )

        do {
            let canonical = try await visitService.create(request)

            // Successful reconciliation: delete mutation, delete owned files, reconcile with VisitStore
            let deleted = try await mutationRepository.deleteIfGeneration(
                mutationId: mutation.mutationId,
                generation: mutation.generation
            )
            guard deleted else {
                return .failure(retryable: true, category: "RECONCILIATION_RACE")
            }

            // Clean up durable local media files
            for photo in orderedPhotos {
                await mediaStore.deleteOwned(ownerUserId: userId, relativePath: photo.localRelativePath)
            }

            // Also clean up any lingering draft for this place
            try? await draftRepository.deleteDraft(placeId: bundle.payload.placeId, userId: userId)

            // Reconcile with UI VisitStore on MainActor
            if let store = visitStore {
                await MainActor.run {
                    store.reconcileCanonicalVisit(canonical)
                }
            }

            return .success
        } catch let appError as AppError {
            return classifyAppError(appError)
        } catch is CancellationError {
            return .failure(retryable: true, category: "CANCELLED")
        } catch {
            return .failure(retryable: true, category: "UNKNOWN_ERROR")
        }
    }

    private func prepareAndUploadPhoto(
        mutation: DurablePendingMutation,
        photo: DurablePendingPhoto,
        userId: UUID
    ) async -> PhotoOutcome {
        if photo.ownerUserId != userId {
            try? await mutationRepository.markPhotoFailure(
                mutationId: mutation.mutationId,
                position: photo.position,
                category: MediaFailureCategory.ownership
            )
            return .photoFailed(.failure(retryable: false, category: MediaFailureCategory.ownership))
        }

        // If media is already confirmed on remote, reuse canonical media ID without uploading!
        if photo.uploadState == .readyRemote, let remoteId = photo.remoteMediaId {
            return .photoPrepared(remoteId)
        }

        guard let path = photo.localRelativePath else {
            return await failPhoto(mutation: mutation, position: photo.position, category: MediaFailureCategory.missingFile)
        }
        guard let contentType = photo.contentType, !contentType.isEmpty else {
            return await failPhoto(mutation: mutation, position: photo.position, category: MediaFailureCategory.invalidState)
        }
        guard let byteSize = photo.byteSize, byteSize > 0 else {
            return await failPhoto(mutation: mutation, position: photo.position, category: MediaFailureCategory.invalidState)
        }

        if byteSize > MediaContract.maxBytes {
            return await failPhoto(mutation: mutation, position: photo.position, category: MediaFailureCategory.tooLarge)
        }

        guard let localUrl = mediaStore.resolveOwned(ownerUserId: userId, relativePath: path),
              FileManager.default.fileExists(atPath: localUrl.path) else {
            return await failPhoto(mutation: mutation, position: photo.position, category: MediaFailureCategory.missingFile)
        }

        // 1. Create upload intent
        let intentReq = MediaUploadIntentRequest(
            clientMediaId: photo.clientMediaId,
            contentType: contentType,
            byteSize: byteSize,
            width: photo.width,
            height: photo.height
        )
        let intent: MediaUploadIntentResponse
        do {
            intent = try await mediaService.createUploadIntent(intentReq)
        } catch let appError as AppError {
            let failure = classifyAppError(appError)
            return .photoFailed(failure)
        } catch {
            return .photoFailed(.failure(retryable: true, category: "INTENT_FAILED"))
        }

        if intent.status == .ready {
            try? await mutationRepository.updatePhotoRemoteState(
                mutationId: mutation.mutationId,
                position: photo.position,
                remoteMediaId: intent.mediaId,
                uploadState: .readyRemote
            )
            return .photoPrepared(intent.mediaId)
        }

        if intent.status == .attached {
            return .photoFailed(.failure(retryable: true, category: "MEDIA_ATTACHED_RESET"))
        }

        try? await mutationRepository.updatePhotoRemoteState(
            mutationId: mutation.mutationId,
            position: photo.position,
            remoteMediaId: intent.mediaId,
            uploadState: .intentCreated
        )

        guard let uploadUrl = intent.uploadUrl else {
            return .photoFailed(.failure(retryable: true, category: "UPLOAD_URL_MISSING"))
        }

        // 2. Direct S3 storage PUT (NO Authorization Bearer header!)
        do {
            try await mediaService.uploadToPresignedURL(
                uploadUrl,
                fileURL: localUrl,
                contentType: contentType,
                requiredHeaders: intent.requiredHeaders ?? [:]
            )
        } catch let appError as AppError {
            let failure = classifyAppError(appError)
            if !failure.retryable {
                return await failPhoto(mutation: mutation, position: photo.position, category: failure.category)
            }
            return .photoFailed(failure)
        } catch {
            return .photoFailed(.failure(retryable: true, category: "STORAGE_PUT_FAILED"))
        }

        // 3. Confirm upload
        do {
            let confirmRes = try await mediaService.confirmUpload(mediaId: intent.mediaId)
            if confirmRes.status == .ready || confirmRes.status == .attached {
                try? await mutationRepository.updatePhotoRemoteState(
                    mutationId: mutation.mutationId,
                    position: photo.position,
                    remoteMediaId: intent.mediaId,
                    uploadState: .readyRemote
                )
                return .photoPrepared(intent.mediaId)
            } else {
                return .photoFailed(.failure(retryable: true, category: "MEDIA_CONFIRM_PENDING"))
            }
        } catch let appError as AppError {
            let failure = classifyAppError(appError)
            return .photoFailed(failure)
        } catch {
            return .photoFailed(.failure(retryable: true, category: "CONFIRM_FAILED"))
        }
    }

    private func failPhoto(mutation: DurablePendingMutation, position: Int, category: String) async -> PhotoOutcome {
        try? await mutationRepository.markPhotoFailure(
            mutationId: mutation.mutationId,
            position: position,
            category: category
        )
        return .photoFailed(.failure(retryable: false, category: category))
    }

    private func classifyAppError(_ error: AppError) -> Outcome {
        if error.isTransient {
            return .failure(retryable: true, category: "CONNECTION")
        }
        switch error {
        case .rateLimited:
            return .failure(retryable: true, category: "HTTP_429")
        case .policyAcceptanceRequired:
            return .failure(retryable: true, category: "POLICY_ACCEPTANCE_REQUIRED")
        case .unauthorized:
            return .failure(retryable: false, category: "UNAUTHORIZED")
        case .validation:
            return .failure(retryable: false, category: "VALIDATION")
        case .notFound:
            return .failure(retryable: false, category: "NOT_FOUND")
        case .forbidden:
            return .failure(retryable: false, category: "FORBIDDEN")
        case .conflict:
            return .failure(retryable: false, category: "CONFLICT")
        default:
            return .failure(retryable: false, category: "UNKNOWN_FAILURE")
        }
    }

    private enum Outcome: Sendable, Equatable {
        case success
        case failure(retryable: Bool, category: String)

        var retryable: Bool {
            switch self {
            case .success: return false
            case .failure(let r, _): return r
            }
        }

        var category: String {
            switch self {
            case .success: return ""
            case .failure(_, let cat): return cat
            }
        }
    }

    private enum PhotoOutcome: Sendable {
        case photoPrepared(UUID)
        case photoFailed(Outcome)
    }
}
