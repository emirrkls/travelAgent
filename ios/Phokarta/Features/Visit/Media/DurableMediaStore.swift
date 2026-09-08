import Foundation
import UniformTypeIdentifiers

protocol DurableMediaStoring: Sendable {
    var rootDirectory: URL { get }

    func importMedia(
        ownerUserId: UUID,
        placeId: UUID,
        position: Int,
        data: Data,
        clientMediaId: UUID?
    ) async throws -> DurableDraftPhoto

    func resolveOwned(ownerUserId: UUID, relativePath: String) -> URL?
    func deleteOwned(ownerUserId: UUID, relativePath: String?) async
    func deleteAllOwned(userId: UUID) async
    func getAllFileUrls() -> [URL]
}

final class DurableMediaStore: DurableMediaStoring, @unchecked Sendable {
    let rootDirectory: URL
    private let clock: any EpochClock
    private let fileManager = FileManager.default

    init(customRootDirectory: URL? = nil, clock: any EpochClock = SystemEpochClock()) {
        self.clock = clock
        if let custom = customRootDirectory {
            self.rootDirectory = custom
        } else {
            let appSupport = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
                ?? URL(fileURLWithPath: NSTemporaryDirectory())
            self.rootDirectory = appSupport
                .appendingPathComponent("Phokarta", isDirectory: true)
                .appendingPathComponent("visit-media", isDirectory: true)
        }
        ensureRootDirectory()
    }

    private func ensureRootDirectory() {
        try? fileManager.createDirectory(at: rootDirectory, withIntermediateDirectories: true)
        var resourceValues = URLResourceValues()
        resourceValues.isExcludedFromBackup = true
        var mutableRoot = rootDirectory
        try? mutableRoot.setResourceValues(resourceValues)
    }

    public func importMedia(
        ownerUserId: UUID,
        placeId: UUID,
        position: Int,
        data: Data,
        clientMediaId: UUID? = nil
    ) async throws -> DurableDraftPhoto {
        let mediaId = clientMediaId ?? UUID()

        // Sanitize image (strip GPS, convert HEIC to JPEG, validate bounds & size)
        let prepared = try VisitMediaPreparation.prepare(data: data, itemID: mediaId)

        let ext: String
        switch prepared.contentType {
        case "image/png": ext = "png"
        case "image/webp": ext = "webp"
        default: ext = "jpg"
        }

        let safeOwner = safeOwnerId(ownerUserId)
        let relativePath = "visit-media/\(safeOwner)/\(mediaId.uuidString).\(ext)"

        guard let targetUrl = resolveOwned(ownerUserId: ownerUserId, relativePath: relativePath) else {
            throw VisitMediaPreparation.PrepareError.loadFailed
        }

        let ownerDir = targetUrl.deletingLastPathComponent()
        try fileManager.createDirectory(at: ownerDir, withIntermediateDirectories: true)

        let tempPart = ownerDir.appendingPathComponent("\(targetUrl.lastPathComponent).part")

        // Read sanitized bytes from temp file and write atomically to durable destination
        let sanitizedBytes = try Data(contentsOf: prepared.tempFileURL)
        try sanitizedBytes.write(to: tempPart, options: [.atomic])

        // Set file protection level
        try? (tempPart as NSURL).setResourceValue(
            URLFileProtection.completeUntilFirstUserAuthentication,
            forKey: .fileProtectionKey
        )

        // Rename .part to target
        if fileManager.fileExists(atPath: targetUrl.path) {
            try? fileManager.removeItem(at: targetUrl)
        }
        try fileManager.moveItem(at: tempPart, to: targetUrl)

        // Clean up temporary preparation file
        try? fileManager.removeItem(at: prepared.tempFileURL)

        // Set modification date based on clock so orphan sweeper grace period evaluates correctly
        let modDate = Date(timeIntervalSince1970: Double(clock.nowMillis()) / 1000.0)
        try? fileManager.setAttributes([.modificationDate: modDate], ofItemAtPath: targetUrl.path)

        let byteSize = (try? fileManager.attributesOfItem(atPath: targetUrl.path)[.size] as? Int64)
            ?? prepared.byteSize

        return DurableDraftPhoto(
            ownerUserId: ownerUserId,
            placeId: placeId,
            position: position,
            clientMediaId: mediaId,
            localRelativePath: relativePath,
            contentType: prepared.contentType,
            byteSize: byteSize,
            width: prepared.width,
            height: prepared.height,
            remoteMediaId: nil,
            uploadState: .localOnly,
            failureCategory: nil
        )
    }

    public func resolveOwned(ownerUserId: UUID, relativePath: String) -> URL? {
        if relativePath.hasPrefix("/") || relativePath.contains("..") || relativePath.contains("\\") {
            return nil
        }
        let safeOwner = safeOwnerId(ownerUserId)
        let ownerRoot = rootDirectory.appendingPathComponent(safeOwner, isDirectory: true).standardizedFileURL

        // Only allow paths scoped to safeOwner or bare filenames:
        // 1) "visit-media/<safeOwner>/<file>"
        // 2) "<safeOwner>/<file>"
        // 3) "<file>" (contains no slashes)
        let filename: String
        if relativePath.hasPrefix("visit-media/\(safeOwner)/") {
            filename = String(relativePath.dropFirst("visit-media/\(safeOwner)/".count))
        } else if relativePath.hasPrefix("\(safeOwner)/") {
            filename = String(relativePath.dropFirst("\(safeOwner)/".count))
        } else if !relativePath.contains("/") {
            filename = relativePath
        } else {
            // Any other path containing path separators belongs to another user or an invalid directory
            return nil
        }

        if filename.isEmpty || filename.contains("/") || filename.contains("\\") {
            return nil
        }

        let candidate = ownerRoot.appendingPathComponent(filename).standardizedFileURL
        guard candidate.path.hasPrefix(ownerRoot.path) else { return nil }
        return candidate
    }

    public func deleteOwned(ownerUserId: UUID, relativePath: String?) async {
        guard let rel = relativePath, let url = resolveOwned(ownerUserId: ownerUserId, relativePath: rel) else {
            return
        }
        try? fileManager.removeItem(at: url)
    }

    public func deleteAllOwned(userId: UUID) async {
        let safeOwner = safeOwnerId(userId)
        let ownerDir = rootDirectory.appendingPathComponent(safeOwner, isDirectory: true)
        try? fileManager.removeItem(at: ownerDir)
    }

    public func getAllFileUrls() -> [URL] {
        guard let enumerator = fileManager.enumerator(
            at: rootDirectory,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]
        ) else {
            return []
        }
        var files: [URL] = []
        for case let fileURL as URL in enumerator {
            let isFile = (try? fileURL.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) ?? false
            if isFile {
                files.append(fileURL)
            }
        }
        return files
    }

    private func safeOwnerId(_ userId: UUID) -> String {
        userId.uuidString.replacingOccurrences(of: "[^A-Za-z0-9._-]", with: "_", options: .regularExpression)
    }
}
