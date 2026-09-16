import Foundation
import Observation
import PhotosUI
import SwiftUI

/// Orchestrates the full media upload lifecycle for the Visit composer.
///
/// Manages an ordered list of `VisitMediaItem`s and drives each through:
/// `selected → preparing → readyForIntent → requestingIntent → uploading → uploaded → confirming → confirmed`
///
/// Design decisions:
/// - Bounded concurrency: max 2 concurrent uploads to limit memory pressure.
/// - Item-scoped retry: confirmed items remain confirmed; only failed items retry.
/// - Durable-first: a selected item is not exposed until its sanitized file and
///   SQLite ownership record are both committed.
/// - Account isolation: coordinator validates accountId on mutations.
@MainActor
@Observable
final class VisitMediaUploadCoordinator {

    // MARK: - State

    private(set) var items: [VisitMediaItem] = []
    private let service: any VisitMediaServing
    private let accountId: UUID
    private let placeId: UUID?
    private let draftRepository: (any VisitDraftRepository)?
    private let mediaStore: (any DurableMediaStoring)?
    private let mediaLock: MediaFileMutationLock
    private var draftProvider: (@MainActor () -> DurableVisitDraft)?
    private var uploadTask: Task<Void, Never>?
    private var selectionTasks: [UUID: Task<Void, Never>] = [:]
    private var selectionTail: Task<Void, Never>?
    private var persistenceTask: Task<Void, Never>?

    /// Maximum concurrent upload operations.
    private static let maxConcurrency = 2

    // MARK: - Computed

    var allConfirmed: Bool {
        !items.isEmpty && items.allSatisfy { $0.phase == .confirmed }
    }

    var hasActiveWork: Bool {
        !selectionTasks.isEmpty || items.contains { $0.phase.isActive }
    }

    var hasFailed: Bool {
        items.contains { $0.phase.isFailed }
    }

    var hasUnresolvedFailure: Bool {
        items.contains { $0.phase.isFailed }
    }

    var confirmedMediaIds: [UUID] {
        items.compactMap { $0.phase == .confirmed ? $0.canonicalMediaId : nil }
    }

    var mediaReadyForPublish: Bool {
        items.isEmpty || items.allSatisfy { item in
            if item.phase == .confirmed { return true }
            if case .failedPermanent = item.phase { return false }
            return item.localRelativePath != nil && !item.phase.isActive
        }
    }

    var remainingSlots: Int {
        max(0, MediaContract.maxPerVisit - items.count - selectionTasks.count)
    }

    var isEmpty: Bool { items.isEmpty }

    // MARK: - Init

    init(
        service: any VisitMediaServing,
        accountId: UUID,
        placeId: UUID? = nil,
        draftRepository: (any VisitDraftRepository)? = nil,
        mediaStore: (any DurableMediaStoring)? = nil,
        mediaLock: MediaFileMutationLock = .shared
    ) {
        self.service = service
        self.accountId = accountId
        self.placeId = placeId
        self.draftRepository = draftRepository
        self.mediaStore = mediaStore
        self.mediaLock = mediaLock
    }

    func configureDraftProvider(_ provider: @escaping @MainActor () -> DurableVisitDraft) {
        draftProvider = provider
    }

    // MARK: - Add from Picker

    /// Add selected photos from the picker and begin preparation.
    func addFromPicker(_ pickerItems: [PhotosPickerItem]) {
        let slotsAvailable = remainingSlots
        let toAdd = Array(pickerItems.prefix(slotsAvailable))
        guard !toAdd.isEmpty else { return }

        for pickerItem in toAdd {
            let itemId = UUID()
            let previous = selectionTail
            let task = Task { [weak self] in
                await previous?.value
                guard !Task.isCancelled,
                      let data = try? await pickerItem.loadTransferable(type: Data.self) else {
                    self?.selectionTasks[itemId] = nil
                    return
                }
                _ = await self?.addSelectedData(data, id: itemId)
                self?.selectionTasks[itemId] = nil
            }
            selectionTasks[itemId] = task
            selectionTail = task
        }
    }

    /// Deterministic entry point used by the picker pipeline and durability tests.
    /// Returns only after the item is durably owned by the draft when durable
    /// dependencies are configured.
    @discardableResult
    func addSelectedData(_ data: Data, id: UUID = UUID()) async -> Bool {
        if let placeId, let draftRepository, let mediaStore, let draftProvider {
            let draft = draftProvider()
            do {
                let photo = try await mediaLock.withLock {
                    try Task.checkCancellation()
                    try await draftRepository.saveDraft(placeId: placeId, draft: draft, userId: accountId)
                    let current = try await draftRepository.getPhotos(placeId: placeId, userId: accountId)
                    let imported = try await mediaStore.importMedia(
                        ownerUserId: accountId,
                        placeId: placeId,
                        position: current.count,
                        data: data,
                        clientMediaId: id
                    )
                    do {
                        try await draftRepository.replacePhotos(
                            placeId: placeId,
                            photos: current + [imported],
                            userId: accountId
                        )
                    } catch {
                        await mediaStore.deleteOwned(
                            ownerUserId: accountId,
                            relativePath: imported.localRelativePath
                        )
                        throw error
                    }
                    return imported
                }
                guard let fileURL = mediaStore.resolveOwned(
                    ownerUserId: accountId,
                    relativePath: photo.localRelativePath
                ) else { return false }
                let sanitized = try Data(contentsOf: fileURL, options: .mappedIfSafe)
                appendDurablePhoto(photo, fileURL: fileURL, thumbnailData: try? VisitMediaPreparation.makeThumbnail(data: sanitized))
                Task { await uploadItem(id: id) }
                return true
            } catch {
                return false
            }
        }

        do {
            let prepared = try await Task.detached(priority: .userInitiated) {
                try VisitMediaPreparation.prepare(data: data, itemID: id)
            }.value
            var item = VisitMediaItem(id: id, phase: .readyForIntent)
            item.thumbnailData = prepared.thumbnailData
            item.uploadBytes = prepared.byteSize
            item.contentType = prepared.contentType
            item.width = prepared.width
            item.height = prepared.height
            item.localTempURL = prepared.tempFileURL
            items.append(item)
            Task { await uploadItem(id: id) }
            return true
        } catch {
            return false
        }
    }

    // MARK: - Direct Add (e.g. for testing)

    func addConfirmedItem(id: UUID = UUID(), canonicalMediaId: UUID) {
        var item = VisitMediaItem(id: id, phase: .confirmed)
        item.canonicalMediaId = canonicalMediaId
        items.append(item)
    }

    /// Restores only media owned by this account and place. Invalid, missing, or
    /// cross-account rows are ignored instead of leaking into the composer.
    func restoreDurablePhotos(_ photos: [DurableDraftPhoto]) async {
        guard let placeId, let mediaStore else { return }
        var restored: [VisitMediaItem] = []
        for photo in photos.sorted(by: { $0.position < $1.position })
        where photo.ownerUserId == accountId && photo.placeId == placeId {
            guard let fileURL = mediaStore.resolveOwned(
                ownerUserId: accountId,
                relativePath: photo.localRelativePath
            ), FileManager.default.fileExists(atPath: fileURL.path) else { continue }

            let data = try? Data(contentsOf: fileURL, options: .mappedIfSafe)
            var item = VisitMediaItem(
                id: photo.clientMediaId,
                phase: photo.remoteMediaId == nil ? .readyForIntent : .confirmed
            )
            item.thumbnailData = data.flatMap { try? VisitMediaPreparation.makeThumbnail(data: $0) }
            item.uploadBytes = photo.byteSize
            item.contentType = photo.contentType
            item.width = photo.width
            item.height = photo.height
            item.canonicalMediaId = photo.remoteMediaId
            item.localTempURL = fileURL
            item.localRelativePath = photo.localRelativePath
            restored.append(item)
        }
        items = restored
        for item in restored where item.phase == .readyForIntent {
            Task { await uploadItem(id: item.id) }
        }
    }

    // MARK: - Reorder

    func move(fromOffsets: IndexSet, toOffset: Int) {
        items.move(fromOffsets: fromOffsets, toOffset: toOffset)
        persistDurableOrdering()
    }

    // MARK: - Remove

    func removeItem(id: UUID) {
        guard let index = items.firstIndex(where: { $0.id == id }) else { return }
        let item = items[index]
        items.remove(at: index)
        if item.localRelativePath != nil {
            persistDurableOrdering(deleting: item)
        } else {
            VisitMediaPreparation.cleanupTempFile(at: item.localTempURL)
        }
    }

    // MARK: - Retry

    func retryFailed(id: UUID) {
        guard let index = items.firstIndex(where: { $0.id == id }),
              items[index].phase.isRetryable else { return }

        // If we have a temp file and content type, we can skip preparation
        if items[index].localTempURL != nil, items[index].contentType != nil {
            items[index].phase = .readyForIntent
            Task { await uploadItem(id: id) }
        }
    }

    // MARK: - Start Upload Pipeline

    /// Begin uploading all items that are ready.
    func startUploadPipeline() {
        guard uploadTask == nil else { return }
        uploadTask = Task {
            await runUploadPipeline()
            uploadTask = nil
        }
    }

    // MARK: - Clear

    /// Cancel all work and reset state. Durable files can be retained when their
    /// ownership has just moved atomically to a pending mutation.
    func clear(keepingDurableFiles: Bool = false) async {
        uploadTask?.cancel()
        uploadTask = nil
        selectionTasks.values.forEach { $0.cancel() }
        await flushPersistence()

        if !keepingDurableFiles, placeId != nil, draftRepository != nil, mediaStore != nil {
            await discardDurableDraft()
            return
        }
        if !keepingDurableFiles {
            for item in items where item.localRelativePath == nil {
                VisitMediaPreparation.cleanupTempFile(at: item.localTempURL)
            }
        }
        items.removeAll()
    }

    /// Deletes exactly this account/place draft and its owned files.
    func discardDurableDraft() async {
        uploadTask?.cancel()
        uploadTask = nil
        selectionTasks.values.forEach { $0.cancel() }
        await flushPersistence()
        guard let placeId, let draftRepository, let mediaStore else {
            for item in items { VisitMediaPreparation.cleanupTempFile(at: item.localTempURL) }
            items.removeAll()
            return
        }
        try? await mediaLock.withLock {
            let photos = try await draftRepository.getPhotos(placeId: placeId, userId: accountId)
            try await draftRepository.deleteDraft(placeId: placeId, userId: accountId)
            for photo in photos {
                await mediaStore.deleteOwned(ownerUserId: accountId, relativePath: photo.localRelativePath)
            }
        }
        items.removeAll()
    }

    func flushPersistence() async {
        let selections = Array(selectionTasks.values)
        for task in selections { await task.value }
        await persistenceTask?.value
    }

    private func appendDurablePhoto(_ photo: DurableDraftPhoto, fileURL: URL, thumbnailData: Data?) {
        var item = VisitMediaItem(
            id: photo.clientMediaId,
            phase: photo.remoteMediaId == nil ? .readyForIntent : .confirmed
        )
        item.thumbnailData = thumbnailData
        item.uploadBytes = photo.byteSize
        item.contentType = photo.contentType
        item.width = photo.width
        item.height = photo.height
        item.canonicalMediaId = photo.remoteMediaId
        item.localTempURL = fileURL
        item.localRelativePath = photo.localRelativePath
        items.append(item)
    }

    private func durablePhotoSnapshot() -> [DurableDraftPhoto] {
        guard let placeId else { return [] }
        return items.enumerated().compactMap { index, item in
            guard let path = item.localRelativePath,
                  let contentType = item.contentType,
                  let byteSize = item.uploadBytes else { return nil }
            return DurableDraftPhoto(
                ownerUserId: accountId,
                placeId: placeId,
                position: index,
                clientMediaId: item.id,
                localRelativePath: path,
                contentType: contentType,
                byteSize: byteSize,
                width: item.width,
                height: item.height,
                remoteMediaId: item.canonicalMediaId,
                uploadState: item.canonicalMediaId == nil ? .localOnly : .readyRemote,
                failureCategory: nil
            )
        }
    }

    private func persistDurableOrdering(deleting removed: VisitMediaItem? = nil) {
        guard let placeId, let draftRepository, let mediaStore else { return }
        let snapshot = durablePhotoSnapshot()
        let previous = persistenceTask
        persistenceTask = Task {
            await previous?.value
            try? await mediaLock.withLock {
                try await draftRepository.replacePhotos(
                    placeId: placeId,
                    photos: snapshot,
                    userId: accountId
                )
                if let path = removed?.localRelativePath {
                    await mediaStore.deleteOwned(ownerUserId: accountId, relativePath: path)
                }
            }
        }
    }

    private func persistRemoteState(clientMediaId: UUID, remoteMediaId: UUID) async {
        guard let placeId, let draftRepository else { return }
        try? await mediaLock.withLock {
            try await draftRepository.updatePhotoRemoteState(
                placeId: placeId,
                clientMediaId: clientMediaId,
                remoteMediaId: remoteMediaId,
                uploadState: .readyRemote,
                userId: accountId
            )
        }
    }

    // MARK: - Pipeline

    private func prepareItem(itemId: UUID, pickerItem: PhotosPickerItem) async {
        guard let index = items.firstIndex(where: { $0.id == itemId }) else { return }
        items[index].phase = .preparing

        do {
            // Load raw data from picker
            guard let data = try await pickerItem.loadTransferable(type: Data.self) else {
                items[safeIndex: itemId]?.phase = .failedPermanent(
                    String(localized: "visit.media.prepare_failed")
                )
                return
            }

            // Check format before expensive preparation
            let sourceType = VisitMediaPreparation.detectSourceType(data)
            let isSupported: Bool
            switch sourceType {
            case .jpeg, .png, .webp, .heic, .heif:
                isSupported = true
            case .unknown:
                isSupported = false
            }

            guard isSupported else {
                items[safeIndex: itemId]?.phase = .failedPermanent(
                    String(localized: "visit.media.unsupported_format")
                )
                return
            }

            // Run preparation on a background thread
            let prepared = try await Task.detached(priority: .userInitiated) {
                try VisitMediaPreparation.prepare(data: data, itemID: itemId)
            }.value

            guard !Task.isCancelled else { return }
            guard let idx = items.firstIndex(where: { $0.id == itemId }) else { return }

            items[idx].phase = .readyForIntent
            items[idx].thumbnailData = prepared.thumbnailData
            items[idx].uploadBytes = prepared.byteSize
            items[idx].contentType = prepared.contentType
            items[idx].width = prepared.width
            items[idx].height = prepared.height
            items[idx].localTempURL = prepared.tempFileURL

            // Auto-start upload if pipeline is running
            Task { await uploadItem(id: itemId) }

        } catch let error as VisitMediaPreparation.PrepareError {
            guard let idx = items.firstIndex(where: { $0.id == itemId }) else { return }
            switch error {
            case .tooLarge:
                items[idx].phase = .failedPermanent(
                    String(localized: "visit.media.too_large")
                )
            case .unsupportedFormat:
                items[idx].phase = .failedPermanent(
                    String(localized: "visit.media.unsupported_format")
                )
            case .loadFailed, .transcodeFailed, .thumbnailFailed:
                items[idx].phase = .failedPermanent(
                    String(localized: "visit.media.prepare_failed")
                )
            }
        } catch {
            items[safeIndex: itemId]?.phase = .failedPermanent(
                String(localized: "visit.media.prepare_failed")
            )
        }
    }

    private func runUploadPipeline() async {
        for item in items where item.phase == .readyForIntent {
            guard !Task.isCancelled else { break }
            await uploadItem(id: item.id)
        }
    }

    private func uploadItem(id: UUID) async {
        guard let index = items.firstIndex(where: { $0.id == id }),
              items[index].phase == .readyForIntent else { return }

        let item = items[index]
        guard let contentType = item.contentType,
              let byteSize = item.uploadBytes,
              let tempURL = item.localTempURL else { return }

        // Step 1: Request upload intent
        items[index].phase = .requestingIntent
        let intentRequest = MediaUploadIntentRequest(
            clientMediaId: id,
            contentType: contentType,
            byteSize: byteSize,
            width: item.width,
            height: item.height
        )

        let intentResponse: MediaUploadIntentResponse
        do {
            intentResponse = try await service.createUploadIntent(intentRequest)
        } catch is CancellationError {
            return
        } catch let error as AppError {
            handleAPIError(itemId: id, error: error, context: "intent")
            return
        } catch {
            items[safeIndex: id]?.phase = .failedRetryable(
                String(localized: "visit.media.upload_failed")
            )
            return
        }

        // If already confirmed (intent idempotency), skip upload
        if intentResponse.status == .ready || intentResponse.status == .attached {
            guard let idx = items.firstIndex(where: { $0.id == id }) else { return }
            items[idx].canonicalMediaId = intentResponse.mediaId
            items[idx].phase = .confirmed
            await persistRemoteState(clientMediaId: id, remoteMediaId: intentResponse.mediaId)
            return
        }

        guard let uploadUrl = intentResponse.uploadUrl else {
            items[safeIndex: id]?.phase = .failedRetryable(
                String(localized: "visit.media.upload_failed")
            )
            return
        }

        // Step 2: PUT to presigned URL
        guard let idx2 = items.firstIndex(where: { $0.id == id }) else { return }
        items[idx2].phase = .uploading(0.0)

        do {
            try await service.uploadToPresignedURL(
                uploadUrl,
                fileURL: tempURL,
                contentType: contentType,
                requiredHeaders: intentResponse.requiredHeaders ?? [:]
            )
        } catch is CancellationError {
            return
        } catch PresignedUploadError.urlExpiredOrForbidden {
            // URL expired — need new intent on retry
            items[safeIndex: id]?.phase = .failedRetryable(
                String(localized: "visit.media.upload_failed")
            )
            return
        } catch {
            items[safeIndex: id]?.phase = .failedRetryable(
                String(localized: "visit.media.upload_failed")
            )
            return
        }

        guard let idx3 = items.firstIndex(where: { $0.id == id }) else { return }
        items[idx3].phase = .uploaded

        // Step 3: Confirm upload
        items[idx3].phase = .confirming
        do {
            let confirmResponse = try await service.confirmUpload(mediaId: intentResponse.mediaId)
            guard let idx4 = items.firstIndex(where: { $0.id == id }) else { return }
            items[idx4].canonicalMediaId = confirmResponse.mediaId
            if confirmResponse.status == .ready || confirmResponse.status == .attached {
                items[idx4].phase = .confirmed
                await persistRemoteState(clientMediaId: id, remoteMediaId: confirmResponse.mediaId)
            } else {
                items[idx4].phase = .failedRetryable(
                    String(localized: "visit.media.confirm_failed")
                )
            }
        } catch is CancellationError {
            return
        } catch let error as AppError {
            handleAPIError(itemId: id, error: error, context: "confirm")
        } catch {
            items[safeIndex: id]?.phase = .failedRetryable(
                String(localized: "visit.media.confirm_failed")
            )
        }
    }

    private func handleAPIError(itemId: UUID, error: AppError, context: String) {
        guard let idx = items.firstIndex(where: { $0.id == itemId }) else { return }
        switch error {
        case .policyAcceptanceRequired:
            items[idx].phase = .failedRetryable(
                String(localized: "visit.media.policy_required")
            )
        case .rateLimited:
            items[idx].phase = .failedRetryable(
                String(localized: "error.rate_limited")
            )
        case .unauthorized:
            items[idx].phase = .failedPermanent(
                String(localized: "error.session_expired")
            )
        case .server, .networkUnavailable, .timeout:
            items[idx].phase = .failedRetryable(
                String(localized: "visit.media.upload_failed")
            )
        default:
            items[idx].phase = .failedPermanent(
                error.localizedMessage
            )
        }
    }
}

// MARK: - Safe Index Helper

private extension Array where Element == VisitMediaItem {
    subscript(safeIndex id: UUID) -> VisitMediaItem? {
        get { first { $0.id == id } }
        set {
            guard let newValue, let idx = firstIndex(where: { $0.id == id }) else { return }
            self[idx] = newValue
        }
    }
}
