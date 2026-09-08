import SwiftUI

struct SettingsScreen: View {
    let session: AuthSessionController
    let blockController: BlockController
    let reportController: ReportController
    let deleteAccountController: DeleteAccountController
    let policyStore: PolicyStatusStore
    let blockService: any BlockServing
    var syncEngine: MutationSyncEngine? = nil

    var body: some View {
        List {
            policySection
            safetySection
            accountSection
        }
        .navigationTitle(String(localized: "settings.title"))
        .navigationBarTitleDisplayMode(.inline)
    }

    private var policySection: some View {
        Section {
            NavigationLink {
                PolicyAcceptanceScreen(store: policyStore, syncEngine: syncEngine) {}
            } label: {
                HStack {
                    Label(String(localized: "settings.policy"), systemImage: "doc.text")
                    Spacer()
                    if policyStore.needsAcceptance {
                        Image(systemName: "exclamationmark.circle.fill")
                            .foregroundStyle(.orange)
                            .accessibilityLabel(String(localized: "policy.acceptance_required"))
                    }
                }
            }
        } header: {
            Text(String(localized: "settings.section.policy"))
        }
    }

    private var safetySection: some View {
        Section {
            NavigationLink {
                BlockedUsersListScreen(service: blockService)
            } label: {
                Label(String(localized: "settings.blocked_users"), systemImage: "person.slash")
            }
        } header: {
            Text(String(localized: "settings.section.safety"))
        }
    }

    private var accountSection: some View {
        Section {
            NavigationLink {
                DeleteAccountScreen(controller: deleteAccountController)
            } label: {
                Label(String(localized: "settings.delete_account"), systemImage: "person.crop.circle.badge.xmark")
                    .foregroundStyle(.red)
            }
        } header: {
            Text(String(localized: "settings.section.account"))
        }
    }
}

// MARK: - Blocked Users List

struct BlockedUsersListScreen: View {
    let service: any BlockServing
    @State private var users: [BlockedUser] = []
    @State private var isLoading = false
    @State private var page = 0
    @State private var hasNext = true

    var body: some View {
        List {
            ForEach(users) { user in
                HStack {
                    VStack(alignment: .leading) {
                        Text(user.displayName)
                            .font(.body)
                        Text("@\(user.username)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                }
            }

            if hasNext && !isLoading {
                ProgressView()
                    .frame(maxWidth: .infinity)
                    .task { await loadMore() }
            }
        }
        .navigationTitle(String(localized: "settings.blocked_users"))
        .overlay {
            if users.isEmpty && !isLoading {
                ContentUnavailableView(
                    String(localized: "block.empty_title"),
                    systemImage: "person.slash",
                    description: Text(String(localized: "block.empty_body"))
                )
            }
        }
        .task { await loadMore() }
    }

    private func loadMore() async {
        guard !isLoading else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            let result = try await service.listBlocked(page: page, size: 20)
            users.append(contentsOf: result.content)
            hasNext = result.hasNext
            page += 1
        } catch {
            // Silently handle; user can retry by scrolling
        }
    }
}
