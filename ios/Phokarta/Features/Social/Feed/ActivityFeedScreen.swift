import SwiftUI

struct ActivityFeedScreen: View {
    @State private var controller: ActivityFeedController
    let currentUserId: UUID?
    let onOpenPlace: (UUID) -> Void
    let onOpenAuthor: (UUID) -> Void

    @Environment(\.colorScheme) private var colorScheme

    init(
        controller: ActivityFeedController,
        currentUserId: UUID?,
        onOpenPlace: @escaping (UUID) -> Void,
        onOpenAuthor: @escaping (UUID) -> Void
    ) {
        self.currentUserId = currentUserId
        self.onOpenPlace = onOpenPlace
        self.onOpenAuthor = onOpenAuthor
        _controller = State(initialValue: controller)
    }

    init(
        activityService: any ActivityServing,
        socialService: any SocialServing,
        store: SocialStateStore,
        currentUserId: UUID? = nil,
        onOpenPlace: @escaping (UUID) -> Void,
        onOpenAuthor: @escaping (UUID) -> Void
    ) {
        let controller = ActivityFeedController(
            activityService: activityService,
            socialService: socialService,
            store: store
        )
        self.init(
            controller: controller,
            currentUserId: currentUserId,
            onOpenPlace: onOpenPlace,
            onOpenAuthor: onOpenAuthor
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            // Scope segmented selector
            Picker("social.scope", selection: Binding(
                get: { controller.activeScope },
                set: { controller.selectScope($0) }
            )) {
                Text("social.community").tag(ActivityScope.community)
                Text("social.friends").tag(ActivityScope.friends)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, PhokartaSpacing.md)
            .padding(.vertical, PhokartaSpacing.sm)
            .background(PhokartaColor.surface(for: colorScheme))

            feedContent
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(PhokartaColor.background(for: colorScheme))
        .navigationTitle(String(localized: "tab.activity"))
        .task {
            controller.startIfNeeded()
        }
    }

    @ViewBuilder
    private var feedContent: some View {
        let feed = controller.activeFeed
        if feed.isLoadingInitial && feed.items.isEmpty {
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .accessibilityLabel(String(localized: "social.loading_activity"))
        } else if let error = feed.errorMessage, feed.items.isEmpty {
            FeatureEmptyState(
                title: error.localizedMessage,
                retryTitle: String(localized: "action.try_again"),
                retry: { Task { await controller.retry() } }
            )
        } else if feed.items.isEmpty {
            emptyView(for: feed)
        } else {
            ScrollView {
                LazyVStack(spacing: PhokartaSpacing.md) {
                    ForEach(feed.items) { event in
                        ActivityEventCard(
                            event: event,
                            currentUserId: currentUserId,
                            isExpanded: controller.expandedReviewIds.contains(event.visitId),
                            onToggleExpand: { controller.toggleReviewExpanded(visitId: event.visitId) },
                            onOpenPlace: onOpenPlace,
                            onOpenAuthor: onOpenAuthor
                        )
                        .onAppear {
                            if event.id == feed.items.last?.id && feed.hasNext {
                                Task { await controller.loadNextPage() }
                            }
                        }
                    }

                    if feed.isLoadingMore {
                        ProgressView()
                            .padding(.vertical, PhokartaSpacing.md)
                    }

                    if let loadMoreError = feed.loadMoreErrorMessage {
                        VStack(spacing: 4) {
                            Text(loadMoreError.localizedMessage)
                                .font(.caption)
                                .foregroundStyle(.red)
                            Button(String(localized: "action.try_again")) {
                                Task { await controller.retryLoadMore() }
                            }
                            .font(.caption.bold())
                        }
                        .padding(.vertical, PhokartaSpacing.sm)
                    }
                }
                .padding(PhokartaSpacing.md)
            }
            .refreshable {
                await controller.refresh()
            }
        }
    }

    private func emptyView(for feed: ScopeFeedState) -> some View {
        VStack(spacing: PhokartaSpacing.md) {
            Spacer()
            if controller.activeScope == .community {
                FeatureEmptyState(
                    title: String(localized: "social.activity_empty_community_title"),
                    subtitle: String(localized: "social.activity_empty_community_body")
                )
            } else {
                switch feed.friendsEmptyReason {
                case .noFriends:
                    FeatureEmptyState(
                        title: String(localized: "social.activity_empty_no_friends_title"),
                        subtitle: String(localized: "social.activity_empty_no_friends_body")
                    )
                case .noActivity, .none:
                    FeatureEmptyState(
                        title: String(localized: "social.activity_empty_friends_title"),
                        subtitle: String(localized: "social.activity_empty_friends_body")
                    )
                }
            }
            Spacer()
        }
    }
}
