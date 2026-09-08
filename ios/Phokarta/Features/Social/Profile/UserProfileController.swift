import Foundation
import Observation

public enum UserProfilePhase: Equatable, Sendable {
    case idle
    case loading
    case content
    case unavailable
    case error(AppError)
}

@MainActor
@Observable
public final class UserProfileController {
    public let userId: UUID
    public let isOwnProfile: Bool

    public private(set) var phase: UserProfilePhase = .idle
    public private(set) var profile: PublicUserProfile?
    public private(set) var ownerProfile: OwnerUserProfile?

    private let service: any SocialServing
    private let store: SocialStateStore
    private var generation: UInt64 = 0
    private var didStart = false

    public init(
        userId: UUID,
        isOwnProfile: Bool,
        service: any SocialServing,
        store: SocialStateStore
    ) {
        self.userId = userId
        self.isOwnProfile = isOwnProfile
        self.service = service
        self.store = store
    }

    public var effectiveRelationship: RelationshipState? {
        store.relationship(for: userId) ?? profile?.relationship
    }

    public var effectiveFollowerCount: Int64 {
        let base = profile?.followerCount ?? ownerProfile?.followerCount ?? 0
        return store.effectiveFollowerCount(baseCount: base, for: userId)
    }

    public var followingCount: Int64 {
        profile?.followingCount ?? ownerProfile?.followingCount ?? 0
    }

    public var friendCount: Int64 {
        profile?.friendCount ?? ownerProfile?.friendCount ?? 0
    }

    public var isMutatingFollow: Bool {
        store.isBusy(userId)
    }

    public var followError: AppError? {
        store.error(for: userId)
    }

    public func startIfNeeded() {
        guard !didStart else { return }
        didStart = true
        Task { await load() }
    }

    public func load() async {
        generation &+= 1
        let currentGen = generation

        if profile == nil && ownerProfile == nil {
            phase = .loading
        }

        let requestRevision = store.currentRevision(for: userId)

        do {
            if isOwnProfile {
                let owner = try await service.getMeProfile()
                guard currentGen == generation, !Task.isCancelled else { return }
                ownerProfile = owner
                phase = .content
            } else {
                let fetched = try await service.getPublicProfile(userId: userId)
                guard currentGen == generation, !Task.isCancelled else { return }
                profile = fetched
                store.recordConfirmedProfile(fetched, requestRevision: requestRevision)
                phase = .content
            }
        } catch is CancellationError {
            return
        } catch let error as AppError {
            guard currentGen == generation else { return }
            if error == .notFound || error == .forbidden {
                phase = .unavailable
            } else {
                phase = (profile != nil || ownerProfile != nil) ? .content : .error(error)
            }
        } catch {
            guard currentGen == generation else { return }
            phase = (profile != nil || ownerProfile != nil) ? .content : .error(.server)
        }
    }

    public func refresh() async {
        await load()
    }

    public func toggleFollow() {
        guard !isOwnProfile else { return }
        store.toggleFollow(targetId: userId, seedRelationship: profile?.relationship)
    }
}
