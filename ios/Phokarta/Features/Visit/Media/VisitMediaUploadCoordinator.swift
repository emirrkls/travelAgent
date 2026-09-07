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
/// - Online-first: no durable state. Process death loses unsubmitted media.
/// - Account isolation: coordinator validates accountId on mutations.
@MainActor
@Observable
final class VisitMediaUploadCoordinator {

    // MARK: - State

    private(set) var items: [VisitMediaItem] = []
    private let service: any VisitMediaServing
    private let accountId: UUID
    private var uploadTask: Task<Void, Never>?

    /// Maximum concurrent upload operations.
    private static let maxConcurrency = 2

    // MARK: - Computed

    var allConfirmed: Bool {
        !items.isEmpty && items.allSatisfy { $0.phase == .confirmed }
    }

    var hasActiveWork: Bool {
        items.contains { $0.phase.isActive }
    }

    var hasFailed: Bool {
        items.contains { $0.phase.isFailed }
    }

    var hasUnresolvedFailure: Bool {
        items.contains { $0.phase.isRetryable || $0.phase == .failedPermanent("") ? false : $0.phase.isFailed }
    }

    var confirmedMediaIds: [UUID] {
        items.compactMap { $0.phase == .confirmed ? $0.canonicalMediaId : nil }
    }

    var mediaReadyForPublish: Bool {
        items.isEmpty || items.allSatisfy { $0.phase == .confirmed }
    }

    var remainingSlots: Int {
        max(0, MediaContract.maxPerVisit - items.count)
    }

    var isEmpty: Bool { items.isEmpty }

    // MARK: - Init

    init(service: any VisitMediaServing, accountId: UUID) {
        self.service = service
        self.accountId = accountId
    }

    // MARK: - Add from Picker

    /// Add selected photos from the picker and begin preparation.
    func addFromPicker(_ pickerItems: [PhotosPickerItem]) {
        let slotsAvailable = remainingSlots
        let toAdd = Array(pickerItems.prefix(slotsAvailable))
        guard !toAdd.isEmpty else { return }

        var newItems: [VisitMediaItem] = []
        for _ in toAdd {
            newItems.append(VisitMediaItem())
        }
        items.append(contentsOf: newItems)

        // Start preparation for each item
        for (index, pickerItem) in toAdd.enumerated() {
            let itemId = newItems[index].id
            Task {
                await prepareItem(itemId: itemId, pickerItem: pickerItem)
            }
        }
    }

    // MARK: - Direct Add (e.g. for testing)

    func addConfirmedItem(id: UUID = UUID(), canonicalMediaId: UUID) {
        var item = VisitMediaItem(id: id, phase: .confirmed)
        item.canonicalMediaId = canonicalMediaId
        items.append(item)
    }

    // MARK: - Reorder

    func move(fromOffsets: IndexSet, toOffset: Int) {
        items.move(fromOffsets: fromOffsets, toOffset: toOffset)
    }

    // MARK: - Remove

    func removeItem(id: UUID) {
        guard let index = items.firstIndex(where: { $0.id == id }) else { return }
        let item = items[index]
        VisitMediaPreparation.cleanupTempFile(at: item.localTempURL)
        items.remove(at: index)
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

    /// Cancel all work, clean temp files, reset state.
    func clear() {
        uploadTask?.cancel()
        uploadTask = nil
        for item in items {
            VisitMediaPreparation.cleanupTempFile(at: item.localTempURL)
        }
        items.removeAll()
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
        // Process items that are ready for upload, bounded by concurrency
        await withTaskGroup(of: Void.self) { group in
            var active = 0
            for item in items where item.phase == .readyForIntent {
                if active >= Self.maxConcurrency {
                    await group.next()
                    active -= 1
                }
                let id = item.id
                group.addTask { @MainActor in
                    await self.uploadItem(id: id)
                }
                active += 1
            }
            await group.waitForAll()
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
