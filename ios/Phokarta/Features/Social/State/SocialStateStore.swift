import Foundation
import Observation

@MainActor
@Observable
public final class SocialStateStore {
    public private(set) var accountID: UUID?
    public private(set) var ownerProfile: OwnerUserProfile?
    public private(set) var confirmedRelationships: [UUID: RelationshipState] = [:]
    public private(set) var desiredFollowing: [UUID: Bool] = [:]
    public private(set) var busyUserIDs: Set<UUID> = []
    public private(set) var errors: [UUID: AppError] = [:]
    public private(set) var followerCountDeltas: [UUID: Int64] = [:]

    private let service: any SocialServing
    private var tasks: [UUID: Task<Void, Never>] = [:]
    private var revision: UInt64 = 0
    private var userRevision: [UUID: UInt64] = [:]
    private var ownerRefreshID: UInt64 = 0

    public var onFriendshipChanged: (@Sendable (UUID, Bool) -> Void)?

    public init(service: any SocialServing) {
        self.service = service
    }

    public func activate(accountID: UUID) {
        guard self.accountID != accountID else { return }
        clear()
        self.accountID = accountID
    }

    public func clear() {
        tasks.values.forEach { $0.cancel() }
        tasks.removeAll()
        accountID = nil
        ownerProfile = nil
        confirmedRelationships.removeAll()
        desiredFollowing.removeAll()
        busyUserIDs.removeAll()
        errors.removeAll()
        followerCountDeltas.removeAll()
        revision = 0
        userRevision.removeAll()
        ownerRefreshID &+= 1
    }

    public func isFollowing(_ userId: UUID) -> Bool {
        if let desired = desiredFollowing[userId] {
            return desired
        }
        return confirmedRelationships[userId]?.isFollowing ?? false
    }

    public func isFriend(_ userId: UUID) -> Bool {
        let following = isFollowing(userId)
        let followsYou = confirmedRelationships[userId]?.followsYou ?? false
        return following && followsYou
    }

    public func relationship(for userId: UUID) -> RelationshipState? {
        guard let confirmed = confirmedRelationships[userId] else {
            if let desired = desiredFollowing[userId] {
                return RelationshipState(isFollowing: desired, followsYou: false)
            }
            return nil
        }
        if let desired = desiredFollowing[userId] {
            return RelationshipState(isFollowing: desired, followsYou: confirmed.followsYou)
        }
        return confirmed
    }

    public func effectiveFollowerCount(baseCount: Int64, for userId: UUID) -> Int64 {
        max(0, baseCount + (followerCountDeltas[userId] ?? 0))
    }

    public func isBusy(_ userId: UUID) -> Bool {
        busyUserIDs.contains(userId)
    }

    public func error(for userId: UUID) -> AppError? {
        errors[userId]
    }

    public func currentRevision(for userId: UUID) -> UInt64 {
        userRevision[userId] ?? 0
    }

    public func recordConfirmedRelationship(_ relationship: RelationshipState?, for userId: UUID, requestRevision: UInt64 = 0) {
        guard (userRevision[userId] ?? 0) <= requestRevision else {
            // Newer local mutation exists, do not overwrite with stale response.
            return
        }
        if let relationship {
            confirmedRelationships[userId] = relationship
        } else {
            confirmedRelationships.removeValue(forKey: userId)
        }
        if desiredFollowing[userId] == nil {
            followerCountDeltas[userId] = 0
        }
    }

    public func recordConfirmedProfile(_ profile: PublicUserProfile, requestRevision: UInt64 = 0) {
        recordConfirmedRelationship(profile.relationship, for: profile.id, requestRevision: requestRevision)
    }

    public func refreshOwnerProfile() async throws -> OwnerUserProfile {
        guard accountID != nil else { throw AppError.unauthorized }
        ownerRefreshID &+= 1
        let currentRefresh = ownerRefreshID
        let profile = try await service.getMeProfile()
        guard currentRefresh == ownerRefreshID, !Task.isCancelled else { return profile }
        ownerProfile = profile
        return profile
    }

    public func toggleFollow(targetId: UUID, seedRelationship: RelationshipState? = nil) {
        if let seed = seedRelationship, confirmedRelationships[targetId] == nil {
            confirmedRelationships[targetId] = seed
        }
        let current = isFollowing(targetId)
        setDesired(!current, for: targetId)
    }

    public func setDesired(_ desired: Bool, for targetId: UUID) {
        guard accountID != nil else { return }
        guard targetId != accountID else {
            errors[targetId] = .validation("Cannot follow yourself")
            return
        }

        let confirmed = confirmedRelationships[targetId]?.isFollowing ?? false
        desiredFollowing[targetId] = desired
        errors[targetId] = nil
        busyUserIDs.insert(targetId)

        // Compute count delta relative to confirmed state:
        if desired && !confirmed {
            followerCountDeltas[targetId] = 1
        } else if !desired && confirmed {
            followerCountDeltas[targetId] = -1
        } else {
            followerCountDeltas[targetId] = 0
        }

        guard tasks[targetId] == nil else { return }
        tasks[targetId] = Task { [weak self] in
            await self?.runMutationLoop(targetId: targetId)
        }
    }

    public func waitForMutation(of targetId: UUID) async {
        await tasks[targetId]?.value
    }

    private func runMutationLoop(targetId: UUID) async {
        defer { tasks[targetId] = nil }
        while !Task.isCancelled, let account = accountID {
            let confirmed = confirmedRelationships[targetId]?.isFollowing ?? false
            guard let target = desiredFollowing[targetId] else {
                busyUserIDs.remove(targetId)
                return
            }

            if target == confirmed {
                desiredFollowing.removeValue(forKey: targetId)
                followerCountDeltas.removeValue(forKey: targetId)
                busyUserIDs.remove(targetId)
                return
            }

            do {
                if target {
                    try await service.follow(userId: targetId)
                } else {
                    try await service.unfollow(userId: targetId)
                }

                guard account == accountID, !Task.isCancelled else { return }

                revision &+= 1
                userRevision[targetId] = revision

                let followsYou = confirmedRelationships[targetId]?.followsYou ?? false
                let wasFriend = confirmed && followsYou
                let newRelationship = RelationshipState(isFollowing: target, followsYou: followsYou)
                confirmedRelationships[targetId] = newRelationship
                let isNowFriend = newRelationship.isFriend

                if wasFriend != isNowFriend {
                    onFriendshipChanged?(targetId, isNowFriend)
                }

                if desiredFollowing[targetId] == target {
                    desiredFollowing.removeValue(forKey: targetId)
                    followerCountDeltas.removeValue(forKey: targetId)
                    busyUserIDs.remove(targetId)
                    return
                }
            } catch is CancellationError {
                return
            } catch let error as AppError {
                guard account == accountID else { return }
                desiredFollowing.removeValue(forKey: targetId)
                followerCountDeltas.removeValue(forKey: targetId)
                busyUserIDs.remove(targetId)
                errors[targetId] = error
                return
            } catch {
                guard account == accountID else { return }
                desiredFollowing.removeValue(forKey: targetId)
                followerCountDeltas.removeValue(forKey: targetId)
                busyUserIDs.remove(targetId)
                errors[targetId] = .server
                return
            }
        }
    }
}
