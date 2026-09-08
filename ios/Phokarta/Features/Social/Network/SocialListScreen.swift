import SwiftUI

public struct SocialListScreen: View {
    @State private var controller: SocialListController
    public let onSelectUser: (UUID) -> Void
    @Environment(\.colorScheme) private var colorScheme

    public init(
        kind: SocialListKind,
        service: any SocialServing,
        store: SocialStateStore,
        onSelectUser: @escaping (UUID) -> Void
    ) {
        self.onSelectUser = onSelectUser
        _controller = State(initialValue: SocialListController(
            kind: kind,
            service: service,
            store: store
        ))
    }

    public var body: some View {
        Group {
            if controller.isLoadingInitial && controller.items.isEmpty {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .accessibilityLabel(String(localized: "social.loading_list"))
            } else if let error = controller.errorMessage, controller.items.isEmpty {
                FeatureEmptyState(
                    title: error.localizedMessage,
                    retryTitle: String(localized: "action.try_again"),
                    retry: { Task { await controller.refresh() } }
                )
            } else if controller.items.isEmpty {
                FeatureEmptyState(
                    title: emptyTitleText,
                    subtitle: emptySubtitleText
                )
            } else {
                List {
                    ForEach(controller.items) { user in
                        SocialUserRow(
                            user: user,
                            relationship: controller.effectiveRelationship(for: user),
                            isMutating: controller.isMutating(userId: user.id),
                            showFollowAction: true,
                            onSelect: { onSelectUser(user.id) },
                            onToggleFollow: { controller.toggleFollow(for: user) }
                        )
                        .listRowBackground(PhokartaColor.surface(for: colorScheme))
                        .onAppear {
                            if user.id == controller.items.last?.id && controller.hasNext {
                                Task { await controller.loadNextPage() }
                            }
                        }
                    }

                    if controller.isLoadingMore {
                        HStack {
                            Spacer()
                            ProgressView()
                            Spacer()
                        }
                        .listRowBackground(Color.transparent)
                    }

                    if let loadMoreError = controller.loadMoreErrorMessage {
                        VStack(spacing: 4) {
                            Text(loadMoreError.localizedMessage)
                                .font(.caption)
                                .foregroundStyle(.red)
                            Button(String(localized: "action.try_again")) {
                                Task { await controller.loadNextPage() }
                            }
                            .font(.caption.bold())
                        }
                        .frame(maxWidth: .infinity)
                        .listRowBackground(Color.transparent)
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .refreshable {
                    await controller.refresh()
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(PhokartaColor.background(for: colorScheme))
        .navigationTitle(navigationTitleText)
        .task {
            controller.startIfNeeded()
        }
    }

    private var navigationTitleText: String {
        switch controller.kind {
        case .followers:
            return String(localized: "social.followers")
        case .following:
            return String(localized: "social.following")
        case .friends:
            return String(localized: "social.friends")
        }
    }

    private var emptyTitleText: String {
        switch controller.kind {
        case .followers:
            return String(localized: "social.no_followers_title")
        case .following:
            return String(localized: "social.no_following_title")
        case .friends:
            return String(localized: "social.no_friends_title")
        }
    }

    private var emptySubtitleText: String {
        switch controller.kind {
        case .followers:
            return String(localized: "social.no_followers_body")
        case .following:
            return String(localized: "social.no_following_body")
        case .friends:
            return String(localized: "social.no_friends_body")
        }
    }
}
