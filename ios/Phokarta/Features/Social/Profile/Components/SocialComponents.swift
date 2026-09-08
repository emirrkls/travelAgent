import SwiftUI

struct UserAvatarView: View {
    let urlString: String?
    let name: String
    let size: CGFloat
    @Environment(\.colorScheme) private var colorScheme

    init(urlString: String?, name: String, size: CGFloat = 44) {
        self.urlString = urlString
        self.name = name
        self.size = size
    }

    var body: some View {
        Group {
            if let validURL = PlaceImageURL.displayURL(from: urlString) {
                AsyncImage(url: validURL) { phase in
                    switch phase {
                    case .success(let image):
                        image
                            .resizable()
                            .scaledToFill()
                    default:
                        fallbackInitials
                    }
                }
            } else {
                fallbackInitials
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .accessibilityHidden(true)
    }

    private var fallbackInitials: some View {
        let initial = name.trimmingCharacters(in: .whitespacesAndNewlines).first.map(String.init) ?? "?"
        return ZStack {
            PhokartaColor.accent(for: colorScheme).opacity(0.15)
            Text(initial.uppercased())
                .font(.system(size: size * 0.42, weight: .bold))
                .foregroundStyle(PhokartaColor.accent(for: colorScheme))
        }
    }
}

struct FollowActionButton: View {
    let relationship: RelationshipState?
    let isMutating: Bool
    let targetName: String
    let action: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    init(
        relationship: RelationshipState?,
        isMutating: Bool,
        targetName: String,
        action: @escaping () -> Void
    ) {
        self.relationship = relationship
        self.isMutating = isMutating
        self.targetName = targetName
        self.action = action
    }

    private var isFriend: Bool {
        relationship?.isFriend == true
    }

    private var isFollowing: Bool {
        relationship?.isFollowing == true
    }

    private var buttonTitle: String {
        if isFriend {
            return String(localized: "social.friends")
        } else if isFollowing {
            return String(localized: "social.following")
        } else {
            return String(localized: "social.follow")
        }
    }

    private var accessibilityTitle: String {
        if isFriend || isFollowing {
            return String(localized: "a11y.unfollow_user \(targetName)")
        } else {
            return String(localized: "a11y.follow_user \(targetName)")
        }
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if isMutating {
                    ProgressView()
                        .controlSize(.small)
                }
                Text(buttonTitle)
                    .font(.subheadline.weight(.semibold))
            }
            .frame(minWidth: 96)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(
                Group {
                    if isFriend || isFollowing {
                        RoundedRectangle(cornerRadius: PhokartaRadius.md)
                            .strokeBorder(PhokartaColor.muted(for: colorScheme), lineWidth: 1)
                    } else {
                        RoundedRectangle(cornerRadius: PhokartaRadius.md)
                            .fill(PhokartaColor.accent(for: colorScheme))
                    }
                }
            )
            .foregroundStyle(
                (isFriend || isFollowing)
                    ? PhokartaColor.ink(for: colorScheme)
                    : Color.white
            )
        }
        .buttonStyle(.plain)
        .disabled(isMutating)
        .accessibilityLabel(accessibilityTitle)
    }
}

struct FollowsYouHint: View {
    let relationship: RelationshipState?
    @Environment(\.colorScheme) private var colorScheme

    init(relationship: RelationshipState?) {
        self.relationship = relationship
    }

    var body: some View {
        if relationship?.followsYou == true && relationship?.isFollowing != true {
            Text("social.follows_you")
                .font(.caption.weight(.medium))
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(
                    PhokartaColor.muted(for: colorScheme).opacity(0.12),
                    in: RoundedRectangle(cornerRadius: PhokartaRadius.sm)
                )
                .foregroundStyle(PhokartaColor.muted(for: colorScheme))
        }
    }
}

struct SocialCountersRow: View {
    let followerCount: Int64
    let followingCount: Int64
    let friendCount: Int64
    let isNavigable: Bool
    var onFollowers: (() -> Void)?
    var onFollowing: (() -> Void)?
    var onFriends: (() -> Void)?
    @Environment(\.colorScheme) private var colorScheme

    init(
        followerCount: Int64,
        followingCount: Int64,
        friendCount: Int64,
        isNavigable: Bool = false,
        onFollowers: (() -> Void)? = nil,
        onFollowing: (() -> Void)? = nil,
        onFriends: (() -> Void)? = nil
    ) {
        self.followerCount = followerCount
        self.followingCount = followingCount
        self.friendCount = friendCount
        self.isNavigable = isNavigable
        self.onFollowers = onFollowers
        self.onFollowing = onFollowing
        self.onFriends = onFriends
    }

    var body: some View {
        HStack(spacing: 0) {
            counterItem(
                value: followerCount,
                labelKey: "social.followers",
                a11yKey: "a11y.followers_count",
                action: onFollowers
            )
            Divider().frame(height: 28)
            counterItem(
                value: followingCount,
                labelKey: "social.following",
                a11yKey: "a11y.following_count",
                action: onFollowing
            )
            Divider().frame(height: 28)
            counterItem(
                value: friendCount,
                labelKey: "social.friends",
                a11yKey: "a11y.friends_count",
                action: onFriends
            )
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, PhokartaSpacing.sm)
    }

    private func counterItem(
        value: Int64,
        labelKey: String.LocalizationValue,
        a11yKey: String,
        action: (() -> Void)?
    ) -> some View {
        Button {
            if isNavigable { action?() }
        } label: {
            VStack(spacing: 2) {
                Text(String(value))
                    .font(.title3.bold())
                    .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                Text(String(localized: labelKey))
                    .font(.caption)
                    .foregroundStyle(PhokartaColor.muted(for: colorScheme))
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
        .disabled(!isNavigable)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(String(localized: String.LocalizationValue(a11yKey)) + " \(value)")
    }
}

struct SocialUserRow: View {
    let user: UserSummary
    let relationship: RelationshipState?
    let isMutating: Bool
    let showFollowAction: Bool
    let onSelect: () -> Void
    let onToggleFollow: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    init(
        user: UserSummary,
        relationship: RelationshipState?,
        isMutating: Bool,
        showFollowAction: Bool = true,
        onSelect: @escaping () -> Void,
        onToggleFollow: @escaping () -> Void
    ) {
        self.user = user
        self.relationship = relationship
        self.isMutating = isMutating
        self.showFollowAction = showFollowAction
        self.onSelect = onSelect
        self.onToggleFollow = onToggleFollow
    }

    var body: some View {
        HStack(spacing: PhokartaSpacing.md) {
            Button(action: onSelect) {
                HStack(spacing: PhokartaSpacing.md) {
                    UserAvatarView(urlString: user.avatarUrl, name: user.displayName, size: 48)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(user.displayName)
                            .font(.headline)
                            .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                            .lineLimit(1)
                        Text(verbatim: "@\(user.username)")
                            .font(.subheadline)
                            .foregroundStyle(PhokartaColor.muted(for: colorScheme))
                            .lineLimit(1)
                        FollowsYouHint(relationship: relationship)
                    }
                }
            }
            .buttonStyle(.plain)

            Spacer()

            if showFollowAction {
                FollowActionButton(
                    relationship: relationship,
                    isMutating: isMutating,
                    targetName: user.displayName,
                    action: onToggleFollow
                )
            }
        }
        .padding(.vertical, PhokartaSpacing.xs)
    }
}
