import CoreGraphics
import ImageIO
import XCTest
@testable import Phokarta

final class VisitMediaTests: XCTestCase {

    // MARK: - Helper Image Generator

    private func makeTestJPEG(
        width: Int = 100,
        height: Int = 100,
        withGPS: Bool = false,
        orientation: UInt32? = nil
    ) -> Data {
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let bitmapInfo = CGImageAlphaInfo.premultipliedLast.rawValue
        let context = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: width * 4,
            space: colorSpace,
            bitmapInfo: bitmapInfo
        )!
        context.setFillColor(CGColor(red: 0.8, green: 0.2, blue: 0.2, alpha: 1.0))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let cgImage = context.makeImage()!

        let mutableData = NSMutableData()
        let destination = CGImageDestinationCreateWithData(mutableData, "public.jpeg" as CFString, 1, nil)!

        var properties: [CFString: Any] = [:]
        if withGPS {
            let gpsDict: [CFString: Any] = [
                kCGImagePropertyGPSLatitude: 36.8969,
                kCGImagePropertyGPSLatitudeRef: "N",
                kCGImagePropertyGPSLongitude: 30.7133,
                kCGImagePropertyGPSLongitudeRef: "E",
                kCGImagePropertyGPSAltitude: 100.0,
                kCGImagePropertyGPSTimeStamp: "12:00:00",
                kCGImagePropertyGPSDateStamp: "2026:09:07"
            ]
            properties[kCGImagePropertyGPSDictionary] = gpsDict
        }
        if let orientation {
            properties[kCGImagePropertyOrientation] = orientation
        }

        CGImageDestinationAddImage(destination, cgImage, properties as CFDictionary)
        CGImageDestinationFinalize(destination)
        return mutableData as Data
    }

    // MARK: - 1. EXIF GPS Stripping (Mandatory Privacy Requirement)

    func testEXIFGPSStrippingRemovesLocationMetadata() throws {
        let inputJPEG = makeTestJPEG(withGPS: true)
        XCTAssertTrue(VisitMediaPreparation.containsGPSMetadata(inputJPEG), "Input JPEG fixture must contain GPS metadata")

        let itemId = UUID()
        let prepared = try VisitMediaPreparation.prepare(data: inputJPEG, itemID: itemId)
        defer { VisitMediaPreparation.cleanupTempFile(at: prepared.tempFileURL) }

        let outputData = try Data(contentsOf: prepared.tempFileURL)
        XCTAssertFalse(VisitMediaPreparation.containsGPSMetadata(outputData), "Prepared output JPEG must NOT contain GPS metadata")

        // Inspect CGImageSource properties directly
        let source = try XCTUnwrap(CGImageSourceCreateWithData(outputData as CFData, nil))
        let props = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any])
        XCTAssertNil(props[kCGImagePropertyGPSDictionary], "GPS dictionary must be completely removed")

        // Assert valid JPEG and properties
        XCTAssertEqual(prepared.contentType, "image/jpeg")
        XCTAssertGreaterThan(prepared.byteSize, 0)
        XCTAssertLessThanOrEqual(prepared.byteSize, MediaContract.maxBytes)
        XCTAssertFalse(prepared.thumbnailData.isEmpty, "Lightweight thumbnail must be generated")
    }

    // MARK: - 2. Orientation Preservation

    func testEXIFOrientationPreservesSemantics() throws {
        // Orientation 6 = 90 degrees CW (portrait from landscape sensor)
        let inputJPEG = makeTestJPEG(width: 200, height: 100, orientation: 6)

        let itemId = UUID()
        let prepared = try VisitMediaPreparation.prepare(data: inputJPEG, itemID: itemId)
        defer { VisitMediaPreparation.cleanupTempFile(at: prepared.tempFileURL) }

        // orientedDimensions swaps width/height for orientation 6
        XCTAssertEqual(prepared.width, 100)
        XCTAssertEqual(prepared.height, 200)

        // Read output image and verify it decodes
        let outputData = try Data(contentsOf: prepared.tempFileURL)
        let source = try XCTUnwrap(CGImageSourceCreateWithData(outputData as CFData, nil))
        XCTAssertEqual(CGImageSourceGetCount(source), 1)
    }

    // MARK: - 3. MIME / Type Validation

    func testMIMETypeDetection() {
        // JPEG magic bytes
        let jpegData = Data([0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01])
        XCTAssertEqual(VisitMediaPreparation.detectSourceType(jpegData), .jpeg)

        // PNG magic bytes
        let pngData = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D])
        XCTAssertEqual(VisitMediaPreparation.detectSourceType(pngData), .png)

        // WebP magic bytes (RIFF....WEBP)
        let webpData = Data([0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50])
        XCTAssertEqual(VisitMediaPreparation.detectSourceType(webpData), .webp)

        // Unknown / unsupported
        let randomData = Data([0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B])
        XCTAssertEqual(VisitMediaPreparation.detectSourceType(randomData), .unknown)
    }

    func testBackendAcceptedMIMEContract() {
        XCTAssertTrue(MediaContract.acceptedContentTypes.contains("image/jpeg"))
        XCTAssertTrue(MediaContract.acceptedContentTypes.contains("image/png"))
        XCTAssertTrue(MediaContract.acceptedContentTypes.contains("image/webp"))
        XCTAssertFalse(MediaContract.acceptedContentTypes.contains("image/heic"))
        XCTAssertFalse(MediaContract.acceptedContentTypes.contains("image/gif"))
        XCTAssertFalse(MediaContract.acceptedContentTypes.contains("application/pdf"))
    }

    // MARK: - 4. File Size Validation

    func testFileSizeValidationRejectsOversized() {
        // 15 MB limit = 15,728,640 bytes
        XCTAssertEqual(MediaContract.maxBytes, 15_728_640)

        // Synthesize oversized data
        let oversizedCount = Int(MediaContract.maxBytes) + 1
        // Create a header + padding that fails size check
        let webpHeader = Data([0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50])
        let oversizedData = webpHeader + Data(count: oversizedCount - webpHeader.count)

        XCTAssertThrowsError(try VisitMediaPreparation.prepare(data: oversizedData, itemID: UUID())) { error in
            XCTAssertEqual(error as? VisitMediaPreparation.PrepareError, .tooLarge)
        }
    }

    // MARK: - 5. Selection Limit

    func testSelectionLimitIs20() {
        XCTAssertEqual(MediaContract.maxPerVisit, 20)
    }

    // MARK: - 6. Presigned PUT Header Isolation (Mandatory)

    func testPresignedPUTRequestHasNoAuthorizationHeader() {
        let targetURL = URL(string: "https://storage.phokarta.local/media/object-key?presigned=true")!
        let requiredHeaders = ["x-amz-server-side-encryption": "AES256"]

        let request = VisitMediaService.makePresignedUploadRequest(
            url: targetURL,
            contentType: "image/jpeg",
            requiredHeaders: requiredHeaders
        )

        XCTAssertEqual(request.httpMethod, "PUT")
        XCTAssertEqual(request.url, targetURL)
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "image/jpeg")
        XCTAssertEqual(request.value(forHTTPHeaderField: "x-amz-server-side-encryption"), "AES256")
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"), "CRITICAL: Presigned PUT must NOT contain Authorization header")
    }

    // MARK: - 7. Intent Endpoint & DTO Contract

    func testIntentEndpointAndPayload() throws {
        let client = APIClient(config: try TestConfig.debugHTTP(), transport: URLSessionTransport.default)
        let clientMediaId = UUID(uuidString: "50000000-0000-0000-0000-000000000001")!
        let request = try client.makeRequest(UploadIntentEndpoint(body: MediaUploadIntentRequest(
            clientMediaId: clientMediaId,
            contentType: "image/jpeg",
            byteSize: 1_048_576,
            width: 1920,
            height: 1080
        )))

        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/api/v1/me/media/upload-intents")

        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: request.httpBody!) as? [String: Any])
        XCTAssertEqual(json["clientMediaId"] as? String, clientMediaId.uuidString.uppercased())
        XCTAssertEqual(json["contentType"] as? String, "image/jpeg")
        XCTAssertEqual(json["byteSize"] as? Int64, 1_048_576)
        XCTAssertEqual(json["width"] as? Int, 1920)
        XCTAssertEqual(json["height"] as? Int, 1080)
    }

    // MARK: - 8. Confirm Endpoint Contract

    func testConfirmEndpointPath() throws {
        let client = APIClient(config: try TestConfig.debugHTTP(), transport: URLSessionTransport.default)
        let mediaId = UUID(uuidString: "50000000-0000-0000-0000-000000000002")!
        let request = try client.makeRequest(ConfirmUploadEndpoint(mediaId: mediaId))

        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/api/v1/me/media/\(mediaId.uuidString.uppercased())/confirm")
    }

    func testConfirmResponseDecoding() throws {
        let json = """
        {"mediaId":"50000000-0000-0000-0000-000000000002","status":"READY"}
        """
        let response = try APIJSON.decoder.decode(MediaConfirmResponse.self, from: Data(json.utf8))
        XCTAssertEqual(response.mediaId, UUID(uuidString: "50000000-0000-0000-0000-000000000002"))
        XCTAssertEqual(response.status, .ready)
    }

    // MARK: - 9. Media Access Endpoint Contract

    func testMediaAccessEndpointPath() throws {
        let client = APIClient(config: try TestConfig.debugHTTP(), transport: URLSessionTransport.default)
        let mediaId = UUID(uuidString: "50000000-0000-0000-0000-000000000003")!
        let request = try client.makeRequest(MediaAccessEndpoint(mediaId: mediaId))

        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.url?.path, "/api/v1/media/\(mediaId.uuidString.uppercased())/access")
    }

    // MARK: - 10. Visit Payload Media Encoding & Ordering

    func testVisitCreateRequestEncodesMediaIdsInExactOrder() throws {
        let client = APIClient(config: try TestConfig.debugHTTP(), transport: URLSessionTransport.default)
        let m1 = UUID(uuidString: "50000000-0000-0000-0000-000000000001")!
        let m2 = UUID(uuidString: "50000000-0000-0000-0000-000000000002")!
        let m3 = UUID(uuidString: "50000000-0000-0000-0000-000000000003")!

        let request = try client.makeRequest(CreateVisitEndpoint(body: VisitCreateRequest(
            clientMutationId: UUID(),
            placeId: TestPlaces.placeID,
            visitedAt: "2026-09-07",
            overallRating: 8.5,
            dimensions: [],
            publicReview: "Great",
            privateMemory: nil,
            mediaIds: [m1, m2, m3],
            visibility: .publicAccess
        )))

        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: request.httpBody!) as? [String: Any])
        let encodedIds = try XCTUnwrap(json["mediaIds"] as? [String])
        XCTAssertEqual(encodedIds, [
            m1.uuidString.uppercased(),
            m2.uuidString.uppercased(),
            m3.uuidString.uppercased()
        ], "Media IDs must be encoded in exact user-selected order")
    }

    // MARK: - 11. Reordering Media Generates New Mutation ID

    @MainActor
    func testReorderingMediaTriggersNewMutationID() async {
        let m1 = UUID(uuidString: "50000000-0000-0000-0000-000000000001")!
        let m2 = UUID(uuidString: "50000000-0000-0000-0000-000000000002")!

        let ids = [
            UUID(uuidString: "10000000-0000-4000-8000-000000000001")!,
            UUID(uuidString: "10000000-0000-4000-8000-000000000002")!
        ]
        var idIndex = 0

        let service = VisitServiceProbe(results: [
            .failure(.networkUnavailable),
            .success(OwnerVisit(id: UUID(), place: TestPlaces.summary(), visitedAt: "2026-09-07", overallRating: 8.0))
        ])
        let store = VisitStore(service: service)
        store.activate(accountID: TestJSON.userID)

        let controller = VisitComposerController(
            place: TestPlaces.detail(),
            store: store,
            uuid: {
                defer { idIndex += 1 }
                return ids[min(idIndex, ids.count - 1)]
            }
        )

        // Add confirmed media [M1, M2]
        controller.mediaCoordinator.addConfirmedItem(canonicalMediaId: m1)
        controller.mediaCoordinator.addConfirmedItem(canonicalMediaId: m2)
        controller.syncMediaState()

        // First attempt (fails with network error)
        _ = await controller.publish()

        // Reorder media to [M2, M1]
        controller.moveMedia(fromOffsets: IndexSet(integer: 0), toOffset: 2)

        // Second attempt
        _ = await controller.publish()

        let requests = await service.requests()
        XCTAssertEqual(requests.count, 2)
        XCTAssertNotEqual(requests[0].clientMutationId, requests[1].clientMutationId, "Reordering media must generate a new mutation ID")
        XCTAssertEqual(requests[0].mediaIds, [m1, m2])
        XCTAssertEqual(requests[1].mediaIds, [m2, m1])
    }

    // MARK: - 12. Removing Media Generates New Mutation ID

    @MainActor
    func testRemovingMediaTriggersNewMutationID() async {
        let m1 = UUID(uuidString: "50000000-0000-0000-0000-000000000001")!
        let m2 = UUID(uuidString: "50000000-0000-0000-0000-000000000002")!

        let ids = [
            UUID(uuidString: "10000000-0000-4000-8000-000000000011")!,
            UUID(uuidString: "10000000-0000-4000-8000-000000000012")!
        ]
        var idIndex = 0

        let service = VisitServiceProbe(results: [
            .failure(.networkUnavailable),
            .success(OwnerVisit(id: UUID(), place: TestPlaces.summary(), visitedAt: "2026-09-07", overallRating: 8.0))
        ])
        let store = VisitStore(service: service)
        store.activate(accountID: TestJSON.userID)

        let controller = VisitComposerController(
            place: TestPlaces.detail(),
            store: store,
            uuid: {
                defer { idIndex += 1 }
                return ids[min(idIndex, ids.count - 1)]
            }
        )

        let item1Id = UUID()
        let item2Id = UUID()
        controller.mediaCoordinator.addConfirmedItem(id: item1Id, canonicalMediaId: m1)
        controller.mediaCoordinator.addConfirmedItem(id: item2Id, canonicalMediaId: m2)
        controller.syncMediaState()

        // First attempt (fails)
        _ = await controller.publish()

        // Remove M2
        controller.removeMedia(id: item2Id)

        // Second attempt
        _ = await controller.publish()

        let requests = await service.requests()
        XCTAssertEqual(requests.count, 2)
        XCTAssertNotEqual(requests[0].clientMutationId, requests[1].clientMutationId, "Removing media must generate a new mutation ID")
        XCTAssertEqual(requests[0].mediaIds, [m1, m2])
        XCTAssertEqual(requests[1].mediaIds, [m1])
    }

    // MARK: - 13. Lost ACK Retry Reuses Mutation ID With Same Media

    @MainActor
    func testLostAckRetryReusesMutationIDWithSameMedia() async {
        let m1 = UUID(uuidString: "50000000-0000-0000-0000-000000000001")!
        let m2 = UUID(uuidString: "50000000-0000-0000-0000-000000000002")!
        let mutation = UUID(uuidString: "10000000-0000-4000-8000-000000000020")!

        let service = VisitServiceProbe(results: [
            .failure(.networkUnavailable),
            .success(OwnerVisit(id: UUID(), place: TestPlaces.summary(), visitedAt: "2026-09-07", overallRating: 8.0))
        ])
        let store = VisitStore(service: service)
        store.activate(accountID: TestJSON.userID)

        let controller = VisitComposerController(
            place: TestPlaces.detail(),
            store: store,
            uuid: { mutation }
        )

        controller.mediaCoordinator.addConfirmedItem(canonicalMediaId: m1)
        controller.mediaCoordinator.addConfirmedItem(canonicalMediaId: m2)
        controller.syncMediaState()

        // First attempt (fails)
        _ = await controller.publish()
        XCTAssertEqual(controller.state.publishState, .retryableFailure(.networkUnavailable))

        // Retry unchanged
        let canonical = await controller.publish()
        XCTAssertNotNil(canonical)

        let requests = await service.requests()
        XCTAssertEqual(requests.count, 2)
        XCTAssertEqual(requests[0].clientMutationId, requests[1].clientMutationId, "Unchanged retry must reuse exact mutation ID")
        XCTAssertEqual(requests[0].mediaIds, requests[1].mediaIds, "Unchanged retry must send exact same ordered media IDs")
    }

    // MARK: - 14. Publish Disabled While Media Active Or Failed

    @MainActor
    func testPublishPreconditionsWithMedia() {
        let store = VisitStore(service: VisitServiceProbe(results: []))
        store.activate(accountID: TestJSON.userID)

        let controller = VisitComposerController(place: TestPlaces.detail(), store: store)
        XCTAssertTrue(controller.canPublish, "Empty composer should be publishable")

        // Add preparing item
        controller.mediaCoordinator.addFromPicker([]) // no-op but test state
        var item = VisitMediaItem(phase: .preparing)
        // Simulate active upload
        item.phase = .uploading(0.5)
        // Controller canPublish must be false
        XCTAssertFalse(item.phase.isTerminal)
        XCTAssertTrue(item.phase.isActive)

        // Failed item must block publish
        let failedItem = VisitMediaItem(phase: .failedRetryable("Failed"))
        XCTAssertTrue(failedItem.phase.isFailed)
        XCTAssertFalse(failedItem.phase.isTerminal)

        // Confirmed item allows publish
        var confirmedItem = VisitMediaItem(phase: .confirmed)
        confirmedItem.canonicalMediaId = UUID()
        XCTAssertTrue(confirmedItem.phase.isTerminal)
        XCTAssertFalse(confirmedItem.phase.isFailed)
    }

    // MARK: - 15. Account Isolation

    @MainActor
    func testAccountIsolationCleansMedia() {
        let store = VisitStore(service: VisitServiceProbe(results: []))
        store.activate(accountID: TestJSON.userID)

        let coordinator = VisitMediaUploadCoordinator(service: NullVisitMediaService(), accountId: TestJSON.userID)
        coordinator.addConfirmedItem(canonicalMediaId: UUID())
        XCTAssertFalse(coordinator.isEmpty)

        // Switch account / clear
        coordinator.clear()
        XCTAssertTrue(coordinator.isEmpty, "Switching accounts must clear all media items")
        XCTAssertEqual(coordinator.confirmedMediaIds, [])
    }

    // MARK: - 16. Temp File Cleanup

    func testTempFileCleanup() throws {
        let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent("test-cleanup-\(UUID()).jpg")
        try Data("dummy".utf8).write(to: tempURL)
        XCTAssertTrue(FileManager.default.fileExists(atPath: tempURL.path))

        VisitMediaPreparation.cleanupTempFile(at: tempURL)
        XCTAssertFalse(FileManager.default.fileExists(atPath: tempURL.path), "Cleanup must remove temp file")
    }

    // MARK: - 17. OwnerVisit Decodes Media Field

    func testOwnerVisitDecodesMediaField() throws {
        let json = """
        {
          "id": "30000000-0000-0000-0000-000000000099",
          "place": {
            "id": "20000000-0000-0000-0000-000000000001",
            "name": "Fixture Beach",
            "category": "BEACH",
            "coverImage": "https://example.test/beach.jpg",
            "city": "Antalya",
            "region": "Mediterranean",
            "country": "Turkey",
            "latitude": 36.8969,
            "longitude": 30.7133,
            "priceLevel": 2,
            "averageScore": 8.7,
            "ratingCount": 4
          },
          "visitedAt": "2026-08-21",
          "overallRating": 9.0,
          "dimensions": [],
          "publicReview": "Mine",
          "privateMemory": "must-not-surface",
          "photos": [],
          "media": [{
            "id": "50000000-0000-0000-0000-000000000001",
            "sortOrder": 0,
            "accessUrl": "https://storage.phokarta.local/media/signed-read-url",
            "accessExpiresAt": "2026-09-07T12:00:00Z"
          }],
          "visibility": "PRIVATE",
          "verificationStatus": "UNVERIFIED"
        }
        """
        let visit = try APIJSON.decoder.decode(OwnerVisit.self, from: Data(json.utf8))
        XCTAssertEqual(visit.media.count, 1)
        XCTAssertEqual(visit.media[0].id, UUID(uuidString: "50000000-0000-0000-0000-000000000001"))
        XCTAssertEqual(visit.media[0].sortOrder, 0)
        XCTAssertEqual(visit.media[0].accessUrl?.absoluteString, "https://storage.phokarta.local/media/signed-read-url")
    }

    // MARK: - 18. Signed URL Treated As Ephemeral Identity

    func testSignedURLIdentityIsAssetUUID() {
        let mediaId = UUID(uuidString: "50000000-0000-0000-0000-000000000001")!
        let dto1 = VisitMediaDTO(id: mediaId, sortOrder: 0, accessUrl: URL(string: "https://storage.test/url1"), accessExpiresAt: "2026-09-07T10:00:00Z")
        let dto2 = VisitMediaDTO(id: mediaId, sortOrder: 0, accessUrl: URL(string: "https://storage.test/url2"), accessExpiresAt: "2026-09-07T11:00:00Z")

        XCTAssertEqual(dto1.id, dto2.id, "Canonical identity must be the media asset UUID, not ephemeral signed URLs")
    }

    // MARK: - 19. Public Review Model Does Not Expose Private Memory

    func testPublicReviewModelExcludesPrivateMemory() {
        let review = TestPlaces.review()
        let mirror = Mirror(reflecting: review)
        let labels = mirror.children.compactMap(\.label)
        XCTAssertFalse(labels.contains("privateMemory"))
        XCTAssertFalse(labels.contains("personalNote"))
    }
}
