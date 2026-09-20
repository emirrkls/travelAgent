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
    private let experienceService: (any ExperienceDiscoveryServing)?
    private var isDraining = false

    init(
        mutationRepository: any OfflineMutationRepository,
        draftRepository: any VisitDraftRepository,
        mediaStore: any DurableMediaStoring,
        mediaService: any VisitMediaServing,
        visitService: any VisitServing,
        visitStore: VisitStore? = nil,
        sessionProvider: any SessionOwnerProvider,
        clock: any EpochClock = SystemEpochClock(),
        experienceService: (any ExperienceDiscoveryServing)? = nil
    ) {
        self.mutationRepository = mutationRepository
        self.draftRepository = draftRepository
        self.mediaStore = mediaStore
        self.mediaService = mediaService
        self.visitService = visitService
        self.visitStore = visitStore
        self.sessionProvider = sessionProvider
        self.clock = clock
        self.experienceService = experienceService
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

            if pauseVisitPublishes && (mutation.type == .publishVisit || mutation.type == .publishExperienceV2) {
                continue
            }

            // Atomically claim the mutation
            let now = Date(timeIntervalSince1970: Double(clock.nowMillis()) / 1000.0)
            guard let claimed = try? await mutationRepository.claim(mutationId: mutation.mutationId, now: now), claimed else {
                continue
            }

            var claimedMutation = mutation
            claimedMutation.generation += 1

            let outcome: Outcome
            switch claimedMutation.type {
            case .publishVisit:
                outcome = await syncVisit(mutation: claimedMutation, userId: userId)
            case .publishExperienceV2:
                outcome = await syncExperienceV2(mutation: claimedMutation, userId: userId)
            case .setSavedState:
                outcome = .failure(retryable: false, category: "UNSUPPORTED_TYPE")
            case .setPlannedExperienceState:
                outcome = await syncPlannedExperience(mutation: claimedMutation, userId: userId)
            case .acknowledgeExperience:
                outcome = await syncAcknowledgement(mutation: claimedMutation, userId: userId)
            case .createConversationRoot, .createConversationReply,
                 .editConversationEntry, .deleteConversationEntry:
                outcome = await syncConversation(mutation: claimedMutation, userId: userId)
            }

            processed += 1

            switch outcome {
            case .success:
                break
            case .failure(let isRetryable, let category):
                retryable = retryable || isRetryable
                let newState: MutationState = isRetryable ? .failedRetryable : .failedPermanent
                try? await mutationRepository.markFailure(
                    mutationId: claimedMutation.mutationId,
                    generation: claimedMutation.generation,
                    state: newState,
                    category: category,
                    now: Date(timeIntervalSince1970: Double(clock.nowMillis()) / 1000.0)
                )
                if category == "POLICY_ACCEPTANCE_REQUIRED" &&
                    (claimedMutation.type == .publishVisit || claimedMutation.type == .publishExperienceV2) {
                    pauseVisitPublishes = true
                }
            }
        }

        return SyncRunResult(retryableFailure: retryable, processed: processed)
    }

    private func syncConversation(mutation: DurablePendingMutation, userId: UUID) async -> Outcome {
        guard let experienceService,
              let bundle = try? await mutationRepository.getConversationBundle(
                mutationId: mutation.mutationId
              ) else {
            return .failure(retryable: false, category: "MISSING_PAYLOAD")
        }
        do {
            switch mutation.type {
            case .createConversationRoot:
                guard let type = bundle.payload.entryType,
                      type == .question || type == .comment,
                      let body = bundle.payload.body else {
                    return .failure(retryable: false, category: "INVALID_PAYLOAD")
                }
                let canonical = try await experienceService.createConversationEntry(
                    experienceId: bundle.payload.experienceId,
                    request: CreateConversationEntryRequest(
                        clientMutationId: mutation.mutationId, type: type, body: body
                    )
                )
                try await mutationRepository.reconcileConversationEntry(
                    canonical, mutationId: mutation.mutationId, userId: userId
                )
            case .createConversationReply:
                guard let rootId = bundle.payload.parentEntryId,
                      let body = bundle.payload.body else {
                    return .failure(retryable: false, category: "INVALID_PAYLOAD")
                }
                let canonical = try await experienceService.createConversationReply(
                    rootId: rootId,
                    request: CreateConversationReplyRequest(
                        clientMutationId: mutation.mutationId, body: body
                    )
                )
                try await mutationRepository.reconcileConversationEntry(
                    canonical, mutationId: mutation.mutationId, userId: userId
                )
            case .editConversationEntry:
                guard let entryId = bundle.payload.targetEntryId,
                      let body = bundle.payload.body else {
                    return .failure(retryable: false, category: "INVALID_PAYLOAD")
                }
                let canonical = try await experienceService.updateConversationEntry(
                    entryId: entryId, body: body
                )
                try await mutationRepository.reconcileConversationEntry(
                    canonical, mutationId: mutation.mutationId, userId: userId
                )
            case .deleteConversationEntry:
                guard let entryId = bundle.payload.targetEntryId else {
                    return .failure(retryable: false, category: "INVALID_PAYLOAD")
                }
                try await experienceService.deleteConversationEntry(entryId: entryId)
                try await mutationRepository.reconcileConversationDeletion(
                    entryId: entryId, mutationId: mutation.mutationId, userId: userId
                )
            default:
                return .failure(retryable: false, category: "INVALID_PAYLOAD")
            }
            guard try await mutationRepository.deleteIfGeneration(
                mutationId: mutation.mutationId, generation: mutation.generation
            ) else {
                return .failure(retryable: true, category: "RECONCILIATION_RACE")
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

    private func syncPlannedExperience(mutation: DurablePendingMutation, userId: UUID) async -> Outcome {
        guard let experienceId = UUID(uuidString: mutation.resourceKey) else {
            return .failure(retryable: false, category: "INVALID_PAYLOAD")
        }
        do {
            let canonical = try await visitService.setPlannedExperience(
                id: experienceId, desired: mutation.payloadVersion == 1
            )
            try await mutationRepository.reconcilePlannedExperience(
                canonical, experienceId: experienceId, userId: userId
            )
            guard try await mutationRepository.deleteIfGeneration(
                mutationId: mutation.mutationId, generation: mutation.generation
            ) else { return .failure(retryable: true, category: "RECONCILIATION_RACE") }
            return .success
        } catch let appError as AppError {
            if appError == .notFound {
                try? await mutationRepository.reconcilePlannedExperience(
                    nil, experienceId: experienceId, userId: userId
                )
                if (try? await mutationRepository.deleteIfGeneration(
                    mutationId: mutation.mutationId, generation: mutation.generation
                )) == true { return .success }
                return .failure(retryable: true, category: "RECONCILIATION_RACE")
            }
            return classifyAppError(appError)
        }
        catch { return .failure(retryable: true, category: "UNKNOWN_ERROR") }
    }

    private func syncAcknowledgement(mutation: DurablePendingMutation, userId: UUID) async -> Outcome {
        guard let experienceId = UUID(uuidString: mutation.resourceKey) else {
            return .failure(retryable: false, category: "INVALID_PAYLOAD")
        }
        do {
            guard let anchor = try await mutationRepository.getAcknowledgementAnchor(
                sourceExperienceId: experienceId, userId: userId
            ) else { return .failure(retryable: false, category: "MISSING_ACKNOWLEDGEMENT_ANCHOR") }
            let canonical = try await visitService.acknowledgeExperience(
                id: experienceId, clientAcknowledgementId: mutation.mutationId, anchor: anchor
            )
            try await mutationRepository.reconcileAcknowledgement(
                canonical, sourceExperienceId: experienceId, userId: userId
            )
            guard try await mutationRepository.deleteIfGeneration(
                mutationId: mutation.mutationId, generation: mutation.generation
            ) else { return .failure(retryable: true, category: "RECONCILIATION_RACE") }
            return .success
        } catch let appError as AppError { return classifyAppError(appError) }
        catch { return .failure(retryable: true, category: "UNKNOWN_ERROR") }
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

    private func syncExperienceV2(mutation: DurablePendingMutation, userId: UUID) async -> Outcome {
        guard mutation.payloadVersion == 2,
              let bundle = try? await mutationRepository.getExperienceV2Bundle(mutationId: mutation.mutationId) else {
            return .failure(retryable: false, category: "MISSING_PAYLOAD")
        }
        let orderedPhotos = bundle.photos.sorted { $0.position < $1.position }
        guard orderedPhotos.count <= 6 else {
            return .failure(retryable: false, category: "V2_MEDIA_LIMIT")
        }
        var mediaIds: [UUID] = []
        for photo in orderedPhotos {
            switch await prepareAndUploadPhoto(mutation: mutation, photo: photo, userId: userId) {
            case .photoPrepared(let mediaId): mediaIds.append(mediaId)
            case .photoFailed(let failure): return failure
            }
        }
        guard let primary = PrimaryExperienceCode(rawValue: bundle.payload.primaryExperienceCode),
              primary != .unknown, primary != .unknownLegacy,
              let feeling = OverallFeelingCode(rawValue: bundle.payload.overallFeelingCode),
              feeling != .unknown,
              let titleSource = ExperienceTitleSource(rawValue: bundle.payload.titleSource),
              titleSource != .unknown else {
            return .failure(retryable: false, category: "INVALID_PAYLOAD")
        }
        let companion = bundle.payload.companionCode.flatMap(CompanionCode.init(rawValue:))
        let timeOfDay = bundle.payload.timeOfDayCode.flatMap(TimeOfDayCode.init(rawValue:))
        let vibes = bundle.payload.vibeCodes.compactMap(VibeCode.init(rawValue:))
        let practicalSignals = bundle.payload.practicalSignalCodes.compactMap(PracticalSignalCode.init(rawValue:))
        guard (bundle.payload.companionCode == nil || companion.map { $0 != .unknown } == true),
              (bundle.payload.timeOfDayCode == nil || timeOfDay.map { $0 != .unknown } == true),
              vibes.count == bundle.payload.vibeCodes.count,
              !vibes.contains(.unknown),
              vibes.count <= 2,
              Set(vibes).count == vibes.count,
              practicalSignals.count == bundle.payload.practicalSignalCodes.count,
              !practicalSignals.contains(.unknown),
              Set(practicalSignals).count == practicalSignals.count else {
            return .failure(retryable: false, category: "INVALID_PAYLOAD")
        }
        let dimensions: [ExperienceV2CreateDimension] = bundle.dimensions.compactMap { row in
            guard let state = DimensionStateCode(rawValue: row.semanticStateCode), state != .unknown else {
                return nil
            }
            return ExperienceV2CreateDimension(
                key: row.dimensionKey,
                semanticStateCode: state,
                templateVersion: row.templateVersion
            )
        }
        let dimensionKeys = dimensions.map(\.key)
        let allowedDimensionKeys = Set(ExperienceDimensionCatalog.keys(for: primary))
        guard dimensions.count == bundle.dimensions.count,
              Set(dimensionKeys).count == dimensionKeys.count,
              Set(dimensionKeys).isSubset(of: allowedDimensionKeys) else {
            return .failure(retryable: false, category: "INVALID_PAYLOAD")
        }
        let visitedDate = Date(timeIntervalSince1970: Double(bundle.payload.visitedAtEpochDay) * 86400)
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .iso8601)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyy-MM-dd"
        let request = ExperienceV2CreateRequest(
            clientMutationId: mutation.mutationId,
            placeId: bundle.payload.placeId,
            visitDate: formatter.string(from: visitedDate),
            primaryExperienceCode: primary,
            rawExperienceLabel: bundle.payload.rawExperienceLabel,
            overallFeelingCode: feeling,
            companionCode: companion,
            timeOfDayCode: timeOfDay,
            vibeCodes: vibes,
            practicalSignalCodes: practicalSignals,
            dimensions: dimensions,
            title: bundle.payload.title,
            titleSource: titleSource,
            story: VisitValidation.trimmedOptional(bundle.payload.story),
            tip: VisitValidation.trimmedOptional(bundle.payload.tip),
            privateMemory: VisitValidation.trimmedOptional(bundle.payload.privateMemory),
            visibility: VisitVisibility(rawValue: bundle.payload.visibility) ?? .publicAccess,
            mediaIds: mediaIds,
            originAcknowledgementId: bundle.payload.originAcknowledgementId
        )
        do {
            let experience = try await visitService.createExperience(request)
            let canonical = OwnerVisit(
                id: experience.id,
                place: PlaceSummary(
                    id: experience.place.id,
                    name: experience.place.name,
                    category: experience.place.category,
                    coverImage: experience.place.coverImage,
                    city: experience.place.city,
                    region: experience.place.region,
                    country: experience.place.country,
                    latitude: 0,
                    longitude: 0,
                    priceLevel: 0,
                    communityScore: nil,
                    ratingCount: 0
                ),
                visitedAt: experience.experiencedAt,
                overallRating: experience.feeling.compatibilityNumericRating,
                dimensions: experience.dimensions.map {
                    VisitDimensionScore(key: $0.key, score: $0.numericScore)
                },
                publicReview: experience.story,
                privateMemory: bundle.payload.privateMemory,
                media: experience.media.compactMap { media in
                    guard let id = media.id else { return nil }
                    return VisitMediaDTO(
                        id: id,
                        sortOrder: media.position,
                        accessUrl: URL(string: media.url),
                        accessExpiresAt: media.accessExpiresAt
                    )
                },
                visibility: experience.visibility
            )
            if let acknowledgementId = bundle.payload.originAcknowledgementId {
                do {
                    try await mutationRepository.markAcknowledgementConverted(
                        id: acknowledgementId, experienceId: experience.id, userId: userId
                    )
                } catch {
                    return .failure(retryable: true, category: "ACKNOWLEDGEMENT_RECONCILIATION")
                }
            }
            guard try await mutationRepository.deleteIfGeneration(
                mutationId: mutation.mutationId,
                generation: mutation.generation
            ) else {
                return .failure(retryable: true, category: "RECONCILIATION_RACE")
            }
            for photo in orderedPhotos {
                await mediaStore.deleteOwned(ownerUserId: userId, relativePath: photo.localRelativePath)
            }
            try? await draftRepository.deleteDraft(placeId: bundle.payload.placeId, userId: userId)
            if let store = visitStore {
                await MainActor.run { store.reconcileCanonicalVisit(canonical) }
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
