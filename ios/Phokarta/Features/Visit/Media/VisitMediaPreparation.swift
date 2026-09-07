import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

/// Image preparation pipeline for Visit media upload.
///
/// Responsibilities:
/// - Detect source format (HEIC, JPEG, PNG, WebP)
/// - Convert HEIC/HEIF → JPEG (backend does not accept `image/heic`)
/// - Strip EXIF GPS metadata from JPEG outputs
/// - Preserve EXIF orientation
/// - Generate lightweight thumbnail
/// - Validate final byte size and MIME
/// - Write sanitized bytes to a temporary file
///
/// GPS stripping strategy:
/// Uses `CGImageSource` to read image properties, removes `kCGImagePropertyGPSDictionary`
/// entirely, then writes the image with cleaned properties via `CGImageDestination`.
/// Orientation is preserved through `kCGImagePropertyOrientation`.
enum VisitMediaPreparation {

    /// Result of preparing a single image for upload.
    struct PreparedMedia: Sendable {
        let tempFileURL: URL
        let contentType: String
        let byteSize: Int64
        let width: Int
        let height: Int
        let thumbnailData: Data
    }

    /// Errors that can occur during preparation.
    enum PrepareError: Error, Equatable, Sendable {
        case unsupportedFormat
        case loadFailed
        case transcodeFailed
        case tooLarge
        case thumbnailFailed
    }

    // MARK: - Public API

    /// Prepare raw image data for upload.
    ///
    /// - Parameters:
    ///   - data: Raw image bytes from the photo picker.
    ///   - itemID: Client media ID used for temporary file naming.
    /// - Returns: A `PreparedMedia` with sanitized bytes on disk.
    /// - Throws: `PrepareError` on failure.
    static func prepare(data: Data, itemID: UUID) throws -> PreparedMedia {
        let sourceType = detectSourceType(data)
        let targetType: CFString
        let targetMIME: String

        switch sourceType {
        case .heic, .heif, .jpeg, .unknown:
            // HEIC/HEIF → JPEG conversion; JPEG → re-save with stripped GPS; unknown → attempt JPEG
            targetType = kUTTypeJPEG
            targetMIME = "image/jpeg"
        case .png:
            targetType = kUTTypePNG
            targetMIME = "image/png"
        case .webp:
            // WebP: pass through without re-encoding (no GPS metadata concern)
            return try preparePassthrough(data: data, mime: "image/webp", itemID: itemID)
        }

        guard let imageSource = CGImageSourceCreateWithData(data as CFData, nil) else {
            throw PrepareError.loadFailed
        }

        let count = CGImageSourceGetCount(imageSource)
        guard count > 0 else { throw PrepareError.loadFailed }

        // Read dimensions
        guard let properties = CGImageSourceCopyPropertiesAtIndex(imageSource, 0, nil) as? [CFString: Any] else {
            throw PrepareError.loadFailed
        }

        let pixelWidth = properties[kCGImagePropertyPixelWidth] as? Int
        let pixelHeight = properties[kCGImagePropertyPixelHeight] as? Int
        guard let width = pixelWidth, let height = pixelHeight, width > 0, height > 0 else {
            throw PrepareError.loadFailed
        }

        // Determine display dimensions accounting for orientation
        let orientation = properties[kCGImagePropertyOrientation] as? UInt32 ?? 1
        let (displayWidth, displayHeight) = orientedDimensions(
            width: width, height: height, orientation: orientation
        )

        // Build cleaned properties (strip GPS)
        var cleanedProperties = properties
        cleanedProperties.removeValue(forKey: kCGImagePropertyGPSDictionary)

        // If source has TIFF dictionary with GPS info embedded, strip that too
        if var tiffDict = cleanedProperties[kCGImagePropertyTIFFDictionary] as? [CFString: Any] {
            tiffDict.removeValue(forKey: kCGImagePropertyGPSDictionary)
            cleanedProperties[kCGImagePropertyTIFFDictionary] = tiffDict
        }

        // If source has EXIF dictionary with GPS-related fields, clean it
        if var exifDict = cleanedProperties[kCGImagePropertyExifDictionary] as? [CFString: Any] {
            exifDict.removeValue(forKey: kCGImagePropertyExifSubjectLocation)
            cleanedProperties[kCGImagePropertyExifDictionary] = exifDict
        }

        // Create image for re-encoding
        guard let cgImage = CGImageSourceCreateImageAtIndex(imageSource, 0, nil) else {
            throw PrepareError.transcodeFailed
        }

        // Write to temporary file
        let tempURL = tempFileURL(itemID: itemID, ext: targetType == kUTTypeJPEG ? "jpg" : "png")
        guard let destination = CGImageDestinationCreateWithURL(
            tempURL as CFURL, targetType, 1, nil
        ) else {
            throw PrepareError.transcodeFailed
        }

        // Set destination properties
        var destProperties = cleanedProperties
        if targetType == kUTTypeJPEG {
            destProperties[kCGImageDestinationLossyCompressionQuality as CFString] = 0.85 as CFNumber
        }

        CGImageDestinationAddImage(destination, cgImage, destProperties as CFDictionary)

        guard CGImageDestinationFinalize(destination) else {
            try? FileManager.default.removeItem(at: tempURL)
            throw PrepareError.transcodeFailed
        }

        // Validate size
        let attributes = try? FileManager.default.attributesOfItem(atPath: tempURL.path)
        let fileSize = (attributes?[.size] as? Int64) ?? 0
        guard fileSize > 0, fileSize <= MediaContract.maxBytes else {
            try? FileManager.default.removeItem(at: tempURL)
            throw PrepareError.tooLarge
        }

        // Generate thumbnail
        let thumbnail = try generateThumbnail(source: imageSource)

        return PreparedMedia(
            tempFileURL: tempURL,
            contentType: targetMIME,
            byteSize: fileSize,
            width: displayWidth,
            height: displayHeight,
            thumbnailData: thumbnail
        )
    }

    /// Verify that the provided data does NOT contain GPS metadata.
    /// Used for testing.
    static func containsGPSMetadata(_ data: Data) -> Bool {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              CGImageSourceGetCount(source) > 0,
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] else {
            return false
        }
        return properties[kCGImagePropertyGPSDictionary] != nil
    }

    /// Clean up a temporary file created during preparation.
    static func cleanupTempFile(at url: URL?) {
        guard let url else { return }
        try? FileManager.default.removeItem(at: url)
    }

    // MARK: - Format Detection

    enum SourceImageType: Sendable {
        case jpeg, png, webp, heic, heif, unknown
    }

    static func detectSourceType(_ data: Data) -> SourceImageType {
        guard data.count >= 12 else { return .unknown }

        // Check magic bytes
        let header = [UInt8](data.prefix(12))

        // JPEG: FF D8 FF
        if header[0] == 0xFF, header[1] == 0xD8, header[2] == 0xFF {
            return .jpeg
        }

        // PNG: 89 50 4E 47
        if header[0] == 0x89, header[1] == 0x50, header[2] == 0x4E, header[3] == 0x47 {
            return .png
        }

        // WebP: RIFF....WEBP
        if header[0] == 0x52, header[1] == 0x49, header[2] == 0x46, header[3] == 0x46,
           header[8] == 0x57, header[9] == 0x45, header[10] == 0x42, header[11] == 0x50 {
            return .webp
        }

        // HEIC/HEIF: check for ftyp box
        if data.count >= 12 {
            let ftypRange = data[4..<8]
            let ftyp = String(bytes: ftypRange, encoding: .ascii)
            if ftyp == "ftyp" {
                let brandRange = data[8..<12]
                let brand = String(bytes: brandRange, encoding: .ascii) ?? ""
                if brand.hasPrefix("heic") || brand.hasPrefix("heis") || brand.hasPrefix("mif1") {
                    return .heic
                }
                if brand.hasPrefix("hevc") || brand.hasPrefix("hevx") || brand.hasPrefix("msf1") {
                    return .heif
                }
            }
        }

        return .unknown
    }

    // MARK: - Private

    /// Pass-through for formats that don't need re-encoding (WebP).
    /// Still validates size and generates thumbnail.
    private static func preparePassthrough(data: Data, mime: String, itemID: UUID) throws -> PreparedMedia {
        guard Int64(data.count) <= MediaContract.maxBytes else {
            throw PrepareError.tooLarge
        }

        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              CGImageSourceGetCount(source) > 0,
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] else {
            throw PrepareError.loadFailed
        }

        let pixelWidth = properties[kCGImagePropertyPixelWidth] as? Int
        let pixelHeight = properties[kCGImagePropertyPixelHeight] as? Int
        guard let width = pixelWidth, let height = pixelHeight, width > 0, height > 0 else {
            throw PrepareError.loadFailed
        }

        let orientation = properties[kCGImagePropertyOrientation] as? UInt32 ?? 1
        let (displayWidth, displayHeight) = orientedDimensions(
            width: width, height: height, orientation: orientation
        )

        let ext = mime == "image/webp" ? "webp" : "bin"
        let tempURL = tempFileURL(itemID: itemID, ext: ext)
        try data.write(to: tempURL, options: [.atomic])

        let thumbnail = try generateThumbnail(source: source)

        return PreparedMedia(
            tempFileURL: tempURL,
            contentType: mime,
            byteSize: Int64(data.count),
            width: displayWidth,
            height: displayHeight,
            thumbnailData: thumbnail
        )
    }

    private static func generateThumbnail(source: CGImageSource) throws -> Data {
        let options: [CFString: Any] = [
            kCGImageSourceThumbnailMaxPixelSize: 400,
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true
        ]
        guard let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            throw PrepareError.thumbnailFailed
        }
        let mutableData = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(mutableData, kUTTypeJPEG, 1, nil) else {
            throw PrepareError.thumbnailFailed
        }
        let thumbProperties: [CFString: Any] = [
            kCGImageDestinationLossyCompressionQuality as CFString: 0.6 as CFNumber
        ]
        CGImageDestinationAddImage(dest, thumbnail, thumbProperties as CFDictionary)
        guard CGImageDestinationFinalize(dest) else {
            throw PrepareError.thumbnailFailed
        }
        return mutableData as Data
    }

    private static func tempFileURL(itemID: UUID, ext: String) -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("phokarta-media", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("\(itemID.uuidString).\(ext)")
    }

    /// Swap width/height for rotated orientations (EXIF orientation 5–8).
    private static func orientedDimensions(width: Int, height: Int, orientation: UInt32) -> (Int, Int) {
        switch orientation {
        case 5, 6, 7, 8:
            return (height, width)
        default:
            return (width, height)
        }
    }
}
