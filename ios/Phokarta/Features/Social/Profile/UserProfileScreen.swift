import SwiftUI

public struct UserProfileScreen: View {
    @State private var controller: UserProfileController
    public let userId: UUID
    public let isOwnProfile: Bool
    public let onSelectPlace: (UUID) -> Void
    public let onSelectUser: (UUID) -> Void
    public let onFollowers: () -> Void
    public let onFollowing: () -> Void
    public let onFriends: () -> Void
    public let onUserSearch: () -> Void
    public let onLogout: (() -> Void)?

    @Environment(\.colorScheme) private var colorScheme

    public init(
        userId: UUID,
        isOwnProfile: Bool,
        service: any SocialServing,
        store: SocialStateStore,
        onSelectPlace: @escaping (UUID) -> Void,
        onSelectUser: @escaping (UUID) -> Void,
        onFollowers: @escaping () -> Void,
        onFollowing: @escaping () -> Void,
        onFriends: @escaping () -> Void,
        onUserSearch: @escaping () -> Void,
        onLogout: (() -> Void)? = nil
    ) {
        self.userId = userId
        self.isOwnProfile = isOwnProfile
        self.onSelectPlace = onSelectPlace
        self.onSelectUser = onSelectUser
        self.onFollowers = onFollowers
        self.onFollowing = onFollowing
        self.onFriends = onFriends
        self.onUserSearch = onUserSearch
        self.onLogout = onLogout
        _controller = State(initialValue: UserProfileController(
            userId: userId,
            isOwnProfile: isOwnProfile,
            service: service,
            store: store
        ))
    }

    public var body: some View {
        Group {
            switch controller.phase {
            case .idle, .loading:
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .accessibilityLabel(String(localized: "social.loading_profile"))
            case .unavailable:
                FeatureEmptyState(
                    title: String(localized: "social.profile_unavailable"),
                    retryTitle: nil,
                    retry: nil
                )
            case .error(let error):
                FeatureEmptyState(
                    title: error.localizedMessage,
                    retryTitle: String(localized: "action.try_again"),
                    retry: { Task { await controller.load() } }
                )
            case .content:
                profileContent
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(PhokartaColor.background(for: colorScheme))
        .navigationTitle(navigationTitleText)
        .refreshable {
            await controller.refresh()
        }
        .task {
            controller.startIfNeeded()
        }
    }

    private var navigationTitleText: String {
        if isOwnProfile {
            return String(localized: "tab.profile")
        }
        return controller.profile?.displayName ?? String(localized: "social.profile")
    }

    private var profileContent: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: PhokartaSpacing.lg) {
                // Header: Avatar, Name, Username
                HStack(spacing: PhokartaSpacing.md) {
                    UserAvatarView(
                        urlString: isOwnProfile ? controller.ownerProfile?.avatarUrl : controller.profile?.avatarUrl,
                        name: displayName,
                        size: 76
                    )

                    VStack(alignment: .leading, spacing: 4) {
                        Text(displayName)
                            .font(.title2.bold())
                            .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                            .lineLimit(1)
                        Text(verbatim: "@\(username)")
                            .font(.subheadline)
                            .foregroundStyle(PhokartaColor.muted(for: colorScheme))
                            .lineLimit(1)
                    }
                    Spacer()
                }

                // Bio
                if let bio = bioText, !bio.isEmpty {
                    Text(bio)
                        .font(.body)
                        .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                        .fixedSize(horizontal: false, vertical: true)
                }

                // Counters: Followers, Following, Friends
                SocialCountersRow(
                    followerCount: controller.effectiveFollowerCount,
                    followingCount: controller.followingCount,
                    friendCount: controller.friendCount,
                    isNavigable: isOwnProfile,
                    onFollowers: onFollowers,
                    onFollowing: onFollowing,
                    onFriends: onFriends
                )

                // Follow action or hints (Other profile only)
                if !isOwnProfile {
                    VStack(alignment: .leading, spacing: PhokartaSpacing.sm) {
                        FollowsYouHint(relationship: controller.effectiveRelationship)

                        FollowActionButton(
                            relationship: controller.effectiveRelationship,
                            isMutating: controller.isMutatingFollow,
                            targetName: displayName,
                            action: controller.toggleFollow
                        )
                        .frame(maxWidth: .infinity, alignment: .leading)

                        if let followError = controller.followError {
                            Text(followError.localizedMessage)
                                .font(.caption)
                                .foregroundStyle(.red)
                        }
                    }
                }

                // Travel summary
                if let stats = travelStatsText {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("social.travel_summary")
                            .font(.headline)
                            .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                        Text(stats)
                            .font(.subheadline)
                            .foregroundStyle(PhokartaColor.muted(for: colorScheme))
                    }
                    .padding(.top, PhokartaSpacing.xs)
                }

                // Own Profile Actions: Find People, Logout
                if isOwnProfile {
                    VStack(spacing: PhokartaSpacing.md) {
                        Button(action: onUserSearch) {
                            HStack {
                                Image(systemName: "magnifyingglass")
                                Text("social.find_people")
                            }
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, PhokartaSpacing.md)
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(PhokartaColor.accent(for: colorScheme))
                        .background(
                            PhokartaColor.accent(for: colorScheme).opacity(0.12),
                            in: RoundedRectangle(cornerRadius: PhokartaRadius.md)
                        )
                        .accessibilityLabel(String(localized: "social.find_people"))

                        if let onLogout {
                            Button(action: onLogout) {
                                Text("auth.logout")
                                    .font(.headline)
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, PhokartaSpacing.md)
                            }
                            .buttonStyle(.plain)
                            .foregroundStyle(.white)
                            .background(
                                PhokartaColor.accent(for: colorScheme),
                                in: RoundedRectangle(cornerRadius: PhokartaRadius.md)
                            )
                            .accessibilityLabel(String(localized: "auth.logout"))
                        }
                    }
                    .padding(.top, PhokartaSpacing.md)
                }

                Spacer()
            }
            .padding(PhokartaSpacing.lg)
        }
    }

    private var displayName: String {
        if isOwnProfile {
            return controller.ownerProfile?.displayName ?? ""
        }
        return controller.profile?.displayName ?? ""
    }

    private var username: String {
        if isOwnProfile {
            return controller.ownerProfile?.username ?? ""
        }
        return controller.profile?.username ?? ""
    }

    private var bioText: String? {
        if isOwnProfile {
            return controller.ownerProfile?.bio
        }
        return controller.profile?.bio
    }

    private var travelStatsText: String? {
        guard let p = controller.profile, (p.countryCount > 0 || p.cityCount > 0) else { return nil }
        return String(localized: "social.countries_cities_summary \(p.countryCount) \(p.cityCount)")
    }
}
