import Foundation
import Observation

@MainActor
@Observable
final class VisitComposerController {
    var state: VisitComposerState
    let mediaCoordinator: VisitMediaUploadCoordinator
    private let store: VisitStore
    private let uuid: () -> UUID
    private var lastSentPayload: VisitCreateRequest.LogicalPayload?
    private var composerAccountID: UUID?
    private let draftRepository: (any VisitDraftRepository)?
    private let mutationRepository: (any OfflineMutationRepository)?
    private let mediaStore: (any DurableMediaStoring)?
    private let syncEngine: MutationSyncEngine?
    private var autosaveTask: Task<Void, Never>?

    var isDirty: Bool {
        state.isDirty || !mediaCoordinator.isEmpty
    }

    var canPublish: Bool {
        state.publishState != .publishing &&
            mediaCoordinator.mediaReadyForPublish &&
            !mediaCoordinator.hasActiveWork &&
            VisitValidation.validate(state) == nil
    }

    init(
        place: PlaceDetail,
        store: VisitStore,
        mediaService: (any VisitMediaServing)? = nil,
        draftRepository: (any VisitDraftRepository)? = nil,
        mutationRepository: (any OfflineMutationRepository)? = nil,
        mediaStore: (any DurableMediaStoring)? = nil,
        syncEngine: MutationSyncEngine? = nil,
        uuid: @escaping () -> UUID = UUID.init
    ) {
        self.store = store
        self.uuid = uuid
        self.composerAccountID = store.accountID
        self.draftRepository = draftRepository
        self.mutationRepository = mutationRepository
        self.mediaStore = mediaStore
        self.syncEngine = syncEngine
        let activeAccount = store.accountID ?? UUID()
        let resolvedMediaService = mediaService ?? store.mediaService
        self.mediaCoordinator = VisitMediaUploadCoordinator(
            service: resolvedMediaService,
            accountId: activeAccount
        )
        self.state = VisitComposerState(
            placeId: place.id,
            placeName: place.name,
            category: place.category,
            clientMutationId: uuid()
        )

        // Asynchronously restore persisted draft if available
        if let draftRepo = draftRepository, let currentUserId = store.accountID {
            Task { [weak self] in
                if let draft = try? await draftRepo.getDraft(placeId: place.id, userId: currentUserId) {
                    self?.restoreDraft(draft)
                }
            }
        }
    }

    func restoreDraft(_ draft: DurableVisitDraft) {
        state.overallScore = draft.overallScore
        state.publicReview = draft.publicReview
        state.privateMemory = draft.privateMemory
        state.visitedAt = Date(timeIntervalSince1970: Double(draft.visitedAtEpochDay) * 86400)
        state.visibility = VisitVisibility(rawValue: draft.visibility) ?? .public
        state.dimensionScores = Dictionary(uniqueKeysWithValues: draft.dimensions.map { ($0.dimensionKey, $0.score) })
        state.isDirty = true
    }

    func setOverall(_ value: Double) { edit { $0.overallScore = rounded(value) } }
    func setReview(_ value: String) { edit { $0.publicReview = String(value.prefix(VisitValidation.textLimit)) } }
    func setPrivateMemory(_ value: String) { edit { $0.privateMemory = String(value.prefix(VisitValidation.textLimit)) } }
    func setDate(_ value: Date) { edit { $0.visitedAt = value } }
    func setVisibility(_ value: VisitVisibility) { edit { $0.visibility = value } }

    func enableDimension(_ key: String) {
        guard VisitDimensionCatalog.keys(for: state.category).contains(key) else { return }
        edit { $0.dimensionScores[key] = rounded($0.overallScore) }
    }

    func setDimension(_ key: String, value: Double) {
        guard state.dimensionScores[key] != nil else { return }
        edit { $0.dimensionScores[key] = rounded(value) }
    }

    func removeDimension(_ key: String) { edit { $0.dimensionScores[key] = nil } }

    func moveMedia(fromOffsets: IndexSet, toOffset: Int) {
        guard state.publishState != .publishing else { return }
        mediaCoordinator.move(fromOffsets: fromOffsets, toOffset: toOffset)
        syncMediaState()
        triggerAutosave()
        if state.publishState != .idle { state.publishState = .idle }
    }

    func removeMedia(id: UUID) {
        guard state.publishState != .publishing else { return }
        mediaCoordinator.removeItem(id: id)
        syncMediaState()
        triggerAutosave()
        if state.publishState != .idle { state.publishState = .idle }
    }

    func retryMedia(id: UUID) {
        guard state.publishState != .publishing else { return }
        mediaCoordinator.retryFailed(id: id)
        syncMediaState()
        if state.publishState != .idle { state.publishState = .idle }
    }

    func discard() {
        autosaveTask?.cancel()
        mediaCoordinator.clear()
        syncMediaState()
        state.publishState = .idle

        if let draftRepo = draftRepository, let accountID = composerAccountID {
            let placeId = state.placeId
            Task {
                try? await draftRepo.deleteDraft(placeId: placeId, userId: accountID)
            }
        }
    }

    func syncMediaState() {
        state.mediaCount = mediaCoordinator.items.count
        state.mediaReadyForPublish = mediaCoordinator.mediaReadyForPublish
        state.mediaHasActiveWork = mediaCoordinator.hasActiveWork
    }

    private func triggerAutosave() {
        autosaveTask?.cancel()
        guard let draftRepo = draftRepository, let accountID = composerAccountID else { return }
        let currentDraft = makeDurableDraft(userId: accountID)
        let placeId = state.placeId
        autosaveTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: VisitDraftRepositoryConstants.autosaveDebounceMs * 1_000_000)
            guard !Task.isCancelled, self != nil else { return }
            try? await draftRepo.saveDraft(placeId: placeId, draft: currentDraft, userId: accountID)
        }
    }

    private func makeDurableDraft(userId: UUID) -> DurableVisitDraft {
        let calendar = Calendar(identifier: .iso8601)
        let epochDay = Int64(calendar.startOfDay(for: state.visitedAt).timeIntervalSince1970 / 86400)
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let dims = state.dimensionScores.map {
            DurableDraftDimensionScore(userId: userId, placeId: state.placeId, dimensionKey: $0.key, score: rounded($0.value))
        }
        return DurableVisitDraft(
            userId: userId,
            placeId: state.placeId,
            overallScore: rounded(state.overallScore),
            publicReview: state.publicReview,
            privateMemory: state.privateMemory,
            visitedAtEpochDay: epochDay,
            visibility: state.visibility.rawValue,
            dimensionsExpanded: !state.dimensionScores.isEmpty,
            createdAtEpochMillis: now,
            updatedAtEpochMillis: now,
            dimensions: dims,
            photos: []
        )
    }

    @discardableResult
    func publish() async -> OwnerVisit? {
        guard state.publishState != .publishing else { return nil }
        syncMediaState()
        guard canPublish else { return nil }
        if let issue = VisitValidation.validate(state) {
            state.publishState = .validationFailure(issue.localizedMessage)
            return nil
        }

        // Account isolation check: ensure current store account matches composer account
        if let currentAccount = store.accountID, let composerAccount = composerAccountID, currentAccount != composerAccount {
            discard()
            state.publishState = .retryableFailure(.unauthorized)
            return nil
        }

        // 1. If durable mutation repository is available, use durable enqueue!
        if let mutationRepo = mutationRepository, let accountID = composerAccountID {
            autosaveTask?.cancel()
            let calendar = Calendar(identifier: .iso8601)
            let epochDay = Int64(calendar.startOfDay(for: state.visitedAt).timeIntervalSince1970 / 86400)

            let payload = DurablePendingVisitPayload(
                mutationId: state.clientMutationId,
                placeId: state.placeId,
                visitedAtEpochDay: epochDay,
                overallRating: rounded(state.overallScore),
                publicReview: state.publicReview,
                privateMemory: state.privateMemory,
                visibility: state.visibility.rawValue
            )
            let dims = state.dimensionScores.map {
                DurablePendingDimensionScore(mutationId: state.clientMutationId, dimensionKey: $0.key, score: rounded($0.value))
            }

            var photos: [DurablePendingPhoto] = []
            for (index, item) in mediaCoordinator.items.enumerated() {
                let localPath = item.tempFileURL.map { "visit-media/\(accountID.uuidString)/\($0.lastPathComponent)" }
                photos.append(
                    DurablePendingPhoto(
                        mutationId: state.clientMutationId,
                        position: index,
                        ownerUserId: accountID,
                        clientMediaId: item.id,
                        localRelativePath: localPath,
                        contentType: item.contentType,
                        byteSize: item.byteSize,
                        width: item.width,
                        height: item.height,
                        remoteMediaId: item.canonicalMediaId,
                        uploadState: item.canonicalMediaId != nil ? .readyRemote : .localOnly,
                        failureCategory: nil
                    )
                )
            }

            do {
                _ = try await mutationRepo.commitVisit(
                    payload: payload,
                    dimensions: dims,
                    photos: photos,
                    userId: accountID
                )
                state.publishState = .success
                mediaCoordinator.clear()
                syncMediaState()

                let engine = syncEngine
                Task {
                    _ = await engine?.drain()
                }
                return nil
            } catch {
                state.publishState = .retryableFailure(.server)
                return nil
            }
        }

        // 2. Online-first publish fallback (for in-memory / legacy tests)
        var request = makeRequest(mutationID: state.clientMutationId)
        if let lastSentPayload, lastSentPayload != request.logicalPayload {
            state.clientMutationId = uuid()
            request = makeRequest(mutationID: state.clientMutationId)
        }
        lastSentPayload = request.logicalPayload
        state.publishState = .publishing
        do {
            let canonical = try await store.publish(request)
            state.publishState = .success
            mediaCoordinator.clear()
            syncMediaState()
            return canonical
        } catch is CancellationError {
            state.publishState = .idle
            return nil
        } catch let failure as AppError {
            switch failure {
            case .policyAcceptanceRequired(let requiredVersion):
                state.publishState = .policyRequired(requiredVersion: requiredVersion)
            case .validation:
                state.publishState = .validationFailure(failure.localizedMessage)
            case .conflict:
                state.publishState = .validationFailure(String(localized: "visit.error.changed_payload"))
            default:
                state.publishState = .retryableFailure(failure)
            }
            return nil
        } catch {
            state.publishState = .retryableFailure(.server)
            return nil
        }
    }

    private func edit(_ mutation: (inout VisitComposerState) -> Void) {
        guard state.publishState != .publishing else { return }
        mutation(&state)
        syncMediaState()
        triggerAutosave()
        if state.publishState != .idle { state.publishState = .idle }
    }

    private func makeRequest(mutationID: UUID) -> VisitCreateRequest {
        let dateFormatter = DateFormatter()
        dateFormatter.calendar = Calendar(identifier: .iso8601)
        dateFormatter.locale = Locale(identifier: "en_US_POSIX")
        dateFormatter.timeZone = .current
        dateFormatter.dateFormat = "yyyy-MM-dd"
        let confirmedIds = mediaCoordinator.confirmedMediaIds
        return VisitCreateRequest(
            clientMutationId: mutationID,
            placeId: state.placeId,
            visitedAt: dateFormatter.string(from: state.visitedAt),
            overallRating: rounded(state.overallScore),
            dimensions: state.dimensionScores.map { VisitDimensionScore(key: $0.key, score: rounded($0.value)) }
                .sorted { $0.key < $1.key },
            publicReview: VisitValidation.trimmedOptional(state.publicReview),
            privateMemory: VisitValidation.trimmedOptional(state.privateMemory),
            mediaIds: confirmedIds.isEmpty ? nil : confirmedIds,
            visibility: state.visibility
        )
    }

    private func rounded(_ value: Double) -> Double { (value * 10).rounded() / 10 }
}
