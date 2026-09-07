# Phokarta iOS v0.5 — Media Upload Parity & Contract Specification

This document defines the media upload architecture, verified backend production contracts, Android reference alignment, and iOS implementation details for Phokarta iOS milestone v0.5.

---

## 1. Verified Backend Media Contract

The iOS implementation conforms directly to the production backend (`com.emirrkls.phokarta.backend`).

### Endpoints

| Endpoint | Method | Auth | Description |
|---|---|---|---|
| `/api/v1/me/media/upload-intents` | `POST` | Bearer Token | Creates or returns an existing upload intent for an authenticated owner. |
| `/api/v1/me/media/{mediaId}/confirm` | `POST` | Bearer Token | Confirms the uploaded object exists in private storage and transitions status from `PENDING_UPLOAD` to `READY`. |
| `/api/v1/media/{mediaId}/access` | `GET` | Bearer Token | Generates a short-lived presigned GET bearer URL after validating Visit visibility. |
| Presigned S3 Storage URL | `PUT` | Query Auth | Direct upload to object storage. **MUST NOT** include `Authorization: Bearer` header. |
| `/api/v1/visits` | `POST` | Bearer Token | Attaches confirmed `mediaIds` to the canonical Visit entity. |

### Contract Parameters & Constraints

- **Allowed Content Types**: `image/jpeg`, `image/png`, `image/webp`.
- **Max File Size**: 15,728,640 bytes (15 MB, configured in `application.yml` as `phokarta.media.max-bytes`).
- **Max Media Per Visit**: 20 items (`phokarta.media.max-per-visit`).
- **Upload Presigned PUT TTL**: 15 minutes (`phokarta.media.upload-ttl`).
- **Read Presigned GET TTL**: 10 minutes (`phokarta.media.read-ttl`).
- **Unattached TTL**: 48 hours (`phokarta.media.unattached-ttl`).
- **Cleanup Interval**: 1 hour (`phokarta.media.cleanup-interval`).

### DTOs

- **Upload Intent Request**:
  - `clientMediaId`: UUID (client-generated identity)
  - `contentType`: String (e.g. `"image/jpeg"`)
  - `byteSize`: Long (upload byte count)
  - `width`: Integer? (pixel width)
  - `height`: Integer? (pixel height)
- **Upload Intent Response**:
  - `mediaId`: UUID (backend canonical ID)
  - `status`: MediaStatus (`PENDING_UPLOAD`, `READY`, `ATTACHED`, `DELETING`)
  - `uploadUrl`: URI? (presigned S3 PUT URL)
  - `requiredHeaders`: Map<String, String>? (S3 headers required on PUT)
  - `expiresAt`: OffsetDateTime?
- **Confirm Response**:
  - `mediaId`: UUID
  - `status`: MediaStatus
- **Media Access Response**:
  - `url`: URI (short-lived presigned GET)
  - `expiresAt`: OffsetDateTime

### Idempotency & Lifecycle

- **Upload Intent Idempotency**:
  - Idempotent per authenticated owner and `clientMediaId`.
  - Sending the same `clientMediaId` with identical metadata returns the existing intent/media record (`idempotency_hit`).
  - Sending the same `clientMediaId` with different metadata returns HTTP 409 Conflict.
- **Confirm Idempotency**:
  - Replaying `/confirm` on an asset that is already `READY` returns the current asset state safely.
  - S3 object existence, byte size, and content type are validated server-side during initial confirm.
- **Orphan Handling**:
  - Unattached media older than 48 hours is claimed by `MediaService.cleanupExpired()`, deleted from S3 storage, and purged from the database.

---

## 2. Android Behavioral Reference Alignment

The Android implementation in `app/` was thoroughly audited and used as the reference behavior:

| Dimension | Android Reference | iOS Implementation | Alignment |
|---|---|---|---|
| Photo Picker | `PickMultipleVisualMedia(20)`, `ImageOnly` | `PhotosPicker(selection:maxSelectionCount:matching:)` | Native system picker, no broad library permissions. |
| Selection Limit | 20 photos (`VisitMediaStore.MAX_PHOTOS = 20`) | 20 photos (`MediaContract.maxPerVisit = 20`) | Exact match. |
| Ordering | Preserves picker selection order (`position` index) | Ordered `items` array with explicit `move(fromOffsets:toOffset:)` | Preserves order. |
| EXIF GPS Stripping | Strips 10 GPS tags via `ExifInterface` on JPEG | Strips `kCGImagePropertyGPSDictionary` via `CGImageSource`/`CGImageDestination` | Native CoreGraphics/ImageIO privacy compliance. |
| HEIC Handling | Rejects (not supported) | Transcodes HEIC/HEIF to JPEG at quality 0.85 | Native transcode matches backend JPEG acceptance. |
| Storage PUT | Direct OkHttp PUT without app auth headers | Raw `URLSession.shared.upload` without `Authorization` header | Strict auth isolation on direct storage PUT. |
| Concurrency | Sequential per photo | Bounded concurrency (max 2 parallel uploads) | Bounded memory pressure. |
| Reorder UX | Remove only | Remove only in UI, preserves selection order | Exact match. |
| Offline / Sync | WorkManager + Room sync engine | Online-first, memory-bound coordinator | Explicit v0.5 scope boundary. |

---

## 3. Photo Picker & Permissions

- **API**: Apple-native `PhotosUI.PhotosPicker`.
- **Permission Model**: Uses the out-of-process system picker. Does **NOT** require `NSPhotoLibraryUsageDescription` or camera permissions in `Info.plist`.
- **Dynamic Slot Bound**: `maxSelectionCount` is dynamically computed as `max(1, remainingSlots)` so the user cannot select more than the 20-photo limit.

---

## 4. Media Preparation & Privacy

### EXIF GPS Stripping (Mandatory Privacy Requirement)

All JPEG images undergo GPS stripping prior to upload:
1. `CGImageSourceCreateWithData` reads the image bytes and property dictionary.
2. `kCGImagePropertyGPSDictionary` is stripped from root properties and nested TIFF/EXIF dictionaries.
3. `CGImageDestination` re-encodes the image with clean properties, preserving EXIF orientation (`kCGImagePropertyOrientation`).
4. Automated tests assert that output bytes contain zero GPS tags before any network transmission occurs.

### Format Handling & HEIC Strategy

- **HEIC / HEIF**: Converted to JPEG with 0.85 compression quality. Dimensions and orientation are preserved.
- **JPEG**: Re-saved with stripped GPS metadata at 0.85 quality.
- **PNG**: Preserved as PNG (or converted if size demands; passes through).
- **WebP**: Passed through directly after size validation.
- **Other Formats**: Rejected with localized `visit.media.unsupported_format`.

### Size Validation

- File size is validated against `MediaContract.maxBytes` (15,728,640 bytes) on the **final sanitized upload bytes** on disk, not just the original picker asset.

---

## 5. Upload Architecture & Coordinator

```
[PhotosPicker]
      ↓
[VisitMediaUploadCoordinator]
      ↓ (Task.detached)
[VisitMediaPreparation]  →  Sanitizes EXIF GPS, writes temp file
      ↓
[VisitMediaService.createUploadIntent]  →  POST /api/v1/me/media/upload-intents
      ↓
[Direct Storage PUT]  →  PUT to presigned S3 URL (NO Bearer token!)
      ↓
[VisitMediaService.confirmUpload]  →  POST /api/v1/me/media/{id}/confirm
      ↓
[VisitComposerController]  →  Attaches confirmed mediaIds to VisitCreateRequest
```

### Lifecycle Phases (`VisitMediaItemPhase`)

- `.selected`: Picked, pending preparation.
- `.preparing`: Image preparation / transcoding / GPS stripping in progress.
- `.readyForIntent`: Sanitized bytes ready in temp storage.
- `.requestingIntent`: `upload-intents` call in flight.
- `.uploading(Double)`: Direct S3 PUT in flight with progress tracking.
- `.uploaded`: PUT completed, waiting for confirmation.
- `.confirming`: `confirm` call in flight.
- `.confirmed`: Asset confirmed by backend; canonical UUID available.
- `.failedRetryable(String)`: Recoverable failure (e.g. network timeout, 429, expired URL).
- `.failedPermanent(String)`: Terminal failure (e.g. unsupported type, file too large).

---

## 6. Visit Payload & Idempotency Interaction

### Payload Fingerprint Semantics

The backend Visit idempotency fingerprint combines:
`placeId \u001e visitedAt \u001e overallRating \u001e dimensions \u001e publicReview \u001e privateMemory \u001e photos \u001e visibility [\u001e mediaIds]`
where `mediaIds` are formatted as UUID strings joined by `\u001f` (Unit Separator).

### Client Mutation ID Rules

- **First Publish**: Generates `clientMutationId = UUID()`.
- **Unchanged Lost-ACK Retry**: Reuses the exact same `clientMutationId` and exact ordered `mediaIds`.
- **Material Media Edit**: If media items are added, removed, or reordered after a publish attempt, `VisitCreateRequest.logicalPayload` changes. The coordinator generates a new `clientMutationId` before the next publish attempt.

---

## 7. Account Isolation & Temporary File Lifecycle

- **Account Switch (User A → User B)**:
  - `VisitStore.activate(accountID:)` detects account change and invokes `clear()`.
  - `VisitComposerController` detects account mismatch, clears coordinator state, and discards all pending media.
  - User B never sees User A's thumbnails, selected items, or temporary files.
- **Temporary File Lifecycle**:
  - Temporary files are created in `NSTemporaryDirectory()/phokarta-media/`.
  - Cleaned up on item removal, composer discard, successful publish, and account switch.
- **Process Death**:
  - Unsubmitted composer state and temporary files are discarded across process death (strictly online-first).

---

## 8. Intentional Gaps & Future Milestones

- **Durable Draft Persistence**: Deferred to v0.6.
- **Offline Mutation Queue**: Deferred to v0.6.
- **Background Upload Worker**: Foreground-only in v0.5.
- **Crash Recovery**: In-memory only in v0.5.
- **Media Reordering UI**: Remove-only in v0.5 UI (matching Android).
- **Full Policy Acceptance UI**: Displays targeted error banner without full modal.
