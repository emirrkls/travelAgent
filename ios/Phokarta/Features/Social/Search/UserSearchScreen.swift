import SwiftUI

struct UserSearchScreen: View {
    @State private var controller: UserSearchController
    let onSelectUser: (UUID) -> Void
    @Environment(\.colorScheme) private var colorScheme

    init(
        service: any SocialServing,
        store: SocialStateStore,
        onSelectUser: @escaping (UUID) -> Void
    ) {
        self.onSelectUser = onSelectUser
        _controller = State(initialValue: UserSearchController(service: service, store: store))
    }

    var body: some View {
        Group {
            if controller.isLoading && controller.items.isEmpty {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .accessibilityLabel(String(localized: "social.searching"))
            } else if let error = controller.errorMessage, controller.items.isEmpty {
                FeatureEmptyState(
                    title: error.localizedMessage,
                    retryTitle: String(localized: "action.try_again"),
                    retry: { controller.retry() }
                )
            } else if controller.items.isEmpty {
                if controller.query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    FeatureEmptyState(
                        title: String(localized: "social.search_people"),
                        message: String(localized: "social.search_people_subtitle")
                    )
                } else {
                    FeatureEmptyState(
                        title: String(localized: "social.no_people_found"),
                        message: String(localized: "social.no_people_found_subtitle")
                    )
                }
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
                        .listRowBackground(Color.clear)
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
                        .listRowBackground(Color.clear)
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(PhokartaColor.background(for: colorScheme))
        .navigationTitle(String(localized: "social.search_people"))
        .searchable(
            text: Binding(
                get: { controller.query },
                set: { controller.setQuery($0) }
            ),
            prompt: Text("social.search_people_hint")
        )
    }
}
