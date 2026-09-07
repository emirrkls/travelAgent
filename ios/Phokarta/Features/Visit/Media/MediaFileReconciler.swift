import Foundation

public struct MediaReconciliationResult: Sendable, Equatable {
    public let removedFiles: Int
    public let retainedFiles: Int

    public init(removedFiles: Int, retainedFiles: Int) {
        self.removedFiles = removedFiles
        self.retainedFiles = retainedFiles
    }
}

public protocol MediaFileReconciling: Sendable {
    func reconcile() async throws -> MediaReconciliationResult
}

public final class MediaFileReconciler: MediaFileReconciling, Sendable {
    private let mediaStore: any DurableMediaStoring
    private let draftRepository: any VisitDraftRepository
    private let mutationRepository: any OfflineMutationRepository
    private let lock: MediaFileMutationLock
    private let clock: any EpochClock
    private let fileManager = FileManager.default

    public static let staleFileGraceMs: Int64 = 5 * 60 * 1000 // 5 minutes

    public init(
        mediaStore: any DurableMediaStoring,
        draftRepository: any VisitDraftRepository,
        mutationRepository: any OfflineMutationRepository,
        lock: MediaFileMutationLock,
        clock: any EpochClock = SystemEpochClock()
    ) {
        self.mediaStore = mediaStore
        self.draftRepository = draftRepository
        self.mutationRepository = mutationRepository
        self.lock = lock
        self.clock = clock
    }

    public func reconcile() async throws -> MediaReconciliationResult {
        try await lock.withLock {
            let root = mediaStore.rootDirectory
            guard fileManager.fileExists(atPath: root.path) else {
                return MediaReconciliationResult(removedFiles: 0, retainedFiles: 0)
            }

            // Gather all active referenced file URLs
            var referencedPaths = Set<String>()

            let draftPhotos = try await draftRepository.getAllPhotos()
            for photo in draftPhotos {
                if let url = mediaStore.resolveOwned(ownerUserId: photo.ownerUserId, relativePath: photo.localRelativePath) {
                    referencedPaths.insert(url.standardizedFileURL.path)
                }
            }

            let mutationPhotos = try await mutationRepository.getAllVisitPhotos()
            for photo in mutationPhotos {
                if let path = photo.localRelativePath,
                   let url = mediaStore.resolveOwned(ownerUserId: photo.ownerUserId, relativePath: path) {
                    referencedPaths.insert(url.standardizedFileURL.path)
                }
            }

            var removed = 0
            var retained = 0
            let now = clock.nowMillis()

            let enumerator = fileManager.enumerator(
                at: root,
                includingPropertiesForKeys: [.isRegularFileKey, .isDirectoryKey, .contentModificationDateKey],
                options: [.skipsHiddenFiles]
            )

            var emptyDirs: [URL] = []

            while let fileURL = enumerator?.nextObject() as? URL {
                let resourceValues = try? fileURL.resourceValues(forKeys: [.isRegularFileKey, .isDirectoryKey, .contentModificationDateKey])
                let isFile = resourceValues?.isRegularFile ?? false
                let isDir = resourceValues?.isDirectory ?? false

                if isFile {
                    let standardPath = fileURL.standardizedFileURL.path
                    if referencedPaths.contains(standardPath) {
                        retained += 1
                    } else {
                        // Check grace period
                        let modDate = resourceValues?.contentModificationDate ?? Date.distantPast
                        let fileAgeMs = now - Int64(modDate.timeIntervalSince1970 * 1000)
                        if fileAgeMs < Self.staleFileGraceMs {
                            retained += 1
                        } else {
                            if (try? fileManager.removeItem(at: fileURL)) != nil {
                                removed += 1
                            }
                        }
                    }
                } else if isDir && fileURL != root {
                    let contents = (try? fileManager.contentsOfDirectory(atPath: fileURL.path)) ?? []
                    if contents.isEmpty {
                        emptyDirs.append(fileURL)
                    }
                }
            }

            // Clean up empty directories bottom-up
            for dir in emptyDirs {
                try? fileManager.removeItem(at: dir)
            }

            return MediaReconciliationResult(removedFiles: removed, retainedFiles: retained)
        }
    }
}
