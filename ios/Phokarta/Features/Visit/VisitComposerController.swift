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
    private var restoreTask: Task<Void, Never>?

    var isDirty: Bool {
        state.isDirty || !mediaCoordinator.isEmpty || mediaCoordinator.hasActiveWork
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
        self.state = VisitComposerState(
            placeId: place.id,
            placeName: place.name,
            category: place.category,
            clientMutationId: uuid()
        )
        self.state.payloadVersion = mutationRepository == nil ? 1 : 2
        self.mediaCoordinator = VisitMediaUploadCoordinator(
            service: resolvedMediaService,
            accountId: activeAccount,
            placeId: place.id,
            draftRepository: draftRepository,
            mediaStore: mediaStore,
            maximumItems: mutationRepository == nil ? MediaContract.maxPerVisit : 6
        )
        self.mediaCoordinator.configureDraftProvider { [weak self] in
            guard let self else {
                let now = Int64(Date().timeIntervalSince1970 * 1000)
                return DurableVisitDraft(
                    userId: activeAccount,
                    placeId: place.id,
                    overallScore: 5,
                    publicReview: "",
                    privateMemory: "",
                    visitedAtEpochDay: 0,
                    visibility: VisitVisibility.publicAccess.rawValue,
                    dimensionsExpanded: false,
                    createdAtEpochMillis: now,
                    updatedAtEpochMillis: now
                )
            }
            return self.makeDurableDraft(userId: activeAccount)
        }

        // Asynchronously restore persisted draft if available
        if let draftRepo = draftRepository, let currentUserId = store.accountID {
            restoreTask = Task { [weak self] in
                if let draft = try? await draftRepo.getDraft(placeId: place.id, userId: currentUserId) {
                    await self?.restoreDraft(draft)
                }
            }
        }
    }

    func restoreDraft(_ draft: DurableVisitDraft) async {
        guard draft.userId == composerAccountID, draft.placeId == state.placeId else { return }
        state.overallScore = draft.overallScore
        state.publicReview = draft.publicReview
        state.privateMemory = draft.privateMemory
        state.visitedAt = Self.localDate(forEpochDay: draft.visitedAtEpochDay)
        state.visibility = VisitVisibility(rawValue: draft.visibility) ?? .publicAccess
        state.payloadVersion = draft.payloadVersion
        state.dimensionScores = Dictionary(uniqueKeysWithValues: draft.dimensions.filter {
            $0.semanticStateCode == nil
        }.map { ($0.dimensionKey, $0.score) })
        state.semanticDimensions = Dictionary(uniqueKeysWithValues: draft.dimensions.compactMap { row in
            row.semanticStateCode.flatMap(DimensionStateCode.init(rawValue:)).map { (row.dimensionKey, $0) }
        })
        state.primaryExperience = draft.primaryExperienceCode.flatMap(PrimaryExperienceCode.init(rawValue:))
        state.rawExperienceLabel = draft.rawExperienceLabel ?? ""
        state.overallFeeling = draft.overallFeelingCode.flatMap(OverallFeelingCode.init(rawValue:))
        state.companion = draft.companionCode.flatMap(CompanionCode.init(rawValue:))
        state.timeOfDay = draft.timeOfDayCode.flatMap(TimeOfDayCode.init(rawValue:))
        state.vibes = Set(draft.vibeCodes.compactMap(VibeCode.init(rawValue:)))
        state.practicalSignals = Set(draft.practicalSignalCodes.compactMap(PracticalSignalCode.init(rawValue:)))
        state.title = draft.title ?? ""
        state.titleSource = ExperienceTitleSource(rawValue: draft.titleSource) ?? .generated
        state.story = draft.story
        state.tip = draft.tip
        await mediaCoordinator.restoreDurablePhotos(draft.photos)
        syncMediaState()
    }

    func waitForInitialRestore() async {
        await restoreTask?.value
    }

    func setOverall(_ value: Double) { edit { $0.overallScore = rounded(value) } }
    func setReview(_ value: String) {
        edit {
            let trimmed = String(value.prefix(VisitValidation.textLimit))
            $0.publicReview = trimmed
            if $0.payloadVersion == 2 { $0.story = trimmed }
        }
    }
    func setPrimaryExperience(_ value: PrimaryExperienceCode) {
        edit {
            $0.primaryExperience = value
            if value != .other { $0.rawExperienceLabel = "" }
            let allowed = Set(ExperienceDimensionCatalog.keys(for: value))
            $0.semanticDimensions = $0.semanticDimensions.filter { allowed.contains($0.key) }
        }
    }
    func setRawExperienceLabel(_ value: String) { edit { $0.rawExperienceLabel = String(value.prefix(120)) } }
    func setOverallFeeling(_ value: OverallFeelingCode) { edit { $0.overallFeeling = value } }
    func setCompanion(_ value: CompanionCode?) { edit { $0.companion = value } }
    func setTimeOfDay(_ value: TimeOfDayCode?) { edit { $0.timeOfDay = value } }
    func toggleVibe(_ value: VibeCode) {
        edit {
            if $0.vibes.contains(value) { $0.vibes.remove(value) }
            else if $0.vibes.count < 2 { $0.vibes.insert(value) }
        }
    }
    func togglePracticalSignal(_ value: PracticalSignalCode) {
        edit {
            if $0.practicalSignals.contains(value) { $0.practicalSignals.remove(value) }
            else { $0.practicalSignals.insert(value) }
        }
    }
    func setSemanticDimension(_ key: String, value: DimensionStateCode?) {
        guard ExperienceDimensionCatalog.keys(for: state.primaryExperience).contains(key) else { return }
        edit { $0.semanticDimensions[key] = value }
    }
    func setTitle(_ value: String) {
        edit {
            $0.title = String(value.prefix(240))
            $0.titleSource = $0.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? .generated : .custom
        }
    }
    func useGeneratedTitle() { edit { $0.title = ""; $0.titleSource = .generated } }
    func setTip(_ value: String) { edit { $0.tip = String(value.prefix(1_000)) } }
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

    func discard() async {
        autosaveTask?.cancel()
        await mediaCoordinator.clear()
        syncMediaState()
        state.publishState = .idle
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
        let epochDay = Self.epochDay(for: state.visitedAt)
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let dims = state.dimensionScores.map {
            DurableDraftDimensionScore(userId: userId, placeId: state.placeId, dimensionKey: $0.key, score: rounded($0.value))
        } + state.semanticDimensions.compactMap { key, semantic -> DurableDraftDimensionScore? in
            guard let score = semantic.compatibilityScore else { return nil }
            return DurableDraftDimensionScore(
                userId: userId, placeId: state.placeId, dimensionKey: key,
                score: Double(score), semanticStateCode: semantic.rawValue, templateVersion: 1
            )
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
            photos: [],
            payloadVersion: state.payloadVersion,
            primaryExperienceCode: state.primaryExperience?.rawValue,
            rawExperienceLabel: VisitValidation.trimmedOptional(state.rawExperienceLabel),
            overallFeelingCode: state.overallFeeling?.rawValue,
            companionCode: state.companion?.rawValue,
            timeOfDayCode: state.timeOfDay?.rawValue,
            vibeCodes: state.vibes.map(\.rawValue).sorted(),
            practicalSignalCodes: state.practicalSignals.map(\.rawValue).sorted(),
            title: VisitValidation.trimmedOptional(state.title),
            titleSource: state.titleSource.rawValue,
            story: state.story,
            tip: state.tip
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
            await discard()
            state.publishState = .retryableFailure(.unauthorized)
            return nil
        }

        // 1. If durable mutation repository is available, use durable enqueue!
        if let mutationRepo = mutationRepository, let accountID = composerAccountID {
            autosaveTask?.cancel()
            await mediaCoordinator.flushPersistence()
            let epochDay = Self.epochDay(for: state.visitedAt)

            var photos: [DurablePendingPhoto] = []
            for (index, item) in mediaCoordinator.items.enumerated() {
                photos.append(
                    DurablePendingPhoto(
                        mutationId: state.clientMutationId,
                        position: index,
                        ownerUserId: accountID,
                        clientMediaId: item.id,
                        localRelativePath: item.localRelativePath,
                        contentType: item.contentType,
                        byteSize: item.uploadBytes,
                        width: item.width,
                        height: item.height,
                        remoteMediaId: item.canonicalMediaId,
                        uploadState: item.canonicalMediaId != nil ? .readyRemote : .localOnly,
                        failureCategory: nil
                    )
                )
            }

            do {
                if state.payloadVersion == 2 {
                    let payload = DurablePendingExperienceV2Payload(
                        mutationId: state.clientMutationId,
                        placeId: state.placeId,
                        visitedAtEpochDay: epochDay,
                        primaryExperienceCode: state.primaryExperience!.rawValue,
                        rawExperienceLabel: VisitValidation.trimmedOptional(state.rawExperienceLabel),
                        overallFeelingCode: state.overallFeeling!.rawValue,
                        companionCode: state.companion?.rawValue,
                        timeOfDayCode: state.timeOfDay?.rawValue,
                        vibeCodes: state.vibes.map(\.rawValue).sorted(),
                        practicalSignalCodes: state.practicalSignals.map(\.rawValue).sorted(),
                        title: VisitValidation.trimmedOptional(state.title),
                        titleSource: state.titleSource.rawValue,
                        story: state.story.trimmingCharacters(in: .whitespacesAndNewlines),
                        tip: state.tip.trimmingCharacters(in: .whitespacesAndNewlines),
                        privateMemory: state.privateMemory.trimmingCharacters(in: .whitespacesAndNewlines),
                        visibility: state.visibility.rawValue
                    )
                    let dimensions = state.semanticDimensions.map {
                        DurablePendingExperienceV2Dimension(
                            mutationId: state.clientMutationId,
                            dimensionKey: $0.key,
                            semanticStateCode: $0.value.rawValue,
                            templateVersion: 1
                        )
                    }
                    _ = try await mutationRepo.commitExperienceV2(
                        payload: payload, dimensions: dimensions, photos: photos, userId: accountID
                    )
                } else {
                    let payload = DurablePendingVisitPayload(
                        mutationId: state.clientMutationId,
                        placeId: state.placeId,
                        visitedAtEpochDay: epochDay,
                        overallRating: rounded(state.overallScore),
                        publicReview: state.publicReview,
                        privateMemory: state.privateMemory,
                        visibility: state.visibility.rawValue
                    )
                    let dimensions = state.dimensionScores.map {
                        DurablePendingDimensionScore(
                            mutationId: state.clientMutationId,
                            dimensionKey: $0.key,
                            score: rounded($0.value)
                        )
                    }
                    _ = try await mutationRepo.commitVisit(
                        payload: payload, dimensions: dimensions, photos: photos, userId: accountID
                    )
                }
                state.publishState = .success
                await mediaCoordinator.clear(keepingDurableFiles: true)
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
            await mediaCoordinator.clear()
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

    private static func epochDay(for date: Date) -> Int64 {
        let components = Calendar.current.dateComponents([.year, .month, .day], from: date)
        var utc = Calendar(identifier: .iso8601)
        utc.timeZone = TimeZone(secondsFromGMT: 0)!
        guard let midnightUTC = utc.date(from: components) else { return 0 }
        return Int64(midnightUTC.timeIntervalSince1970 / 86_400)
    }

    private static func localDate(forEpochDay epochDay: Int64) -> Date {
        var utc = Calendar(identifier: .iso8601)
        utc.timeZone = TimeZone(secondsFromGMT: 0)!
        let instant = Date(timeIntervalSince1970: Double(epochDay) * 86_400)
        let components = utc.dateComponents([.year, .month, .day], from: instant)
        return Calendar.current.date(from: components) ?? instant
    }

    private func rounded(_ value: Double) -> Double { (value * 10).rounded() / 10 }
}
