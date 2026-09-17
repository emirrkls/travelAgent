import Observation
import SwiftUI

struct SettingsScreen: View {
    let session: AuthSessionController
    let blockController: BlockController
    let reportController: ReportController
    let deleteAccountController: DeleteAccountController
    let policyStore: PolicyStatusStore
    let blockService: any BlockServing
    var syncEngine: MutationSyncEngine? = nil
    @AppStorage("phokarta.language") private var languageRaw = PhokartaLanguage.system.rawValue
    @Environment(\.locale) private var locale

    var body: some View {
        List {
            languageSection
            policySection
            safetySection
            accountSection
        }
        .navigationTitle(phokartaString("settings.title", locale: locale))
        .navigationBarTitleDisplayMode(.inline)
    }

    private var languageSection: some View {
        Section {
            Picker("settings.language", selection: $languageRaw) {
                ForEach(PhokartaLanguage.allCases, id: \.rawValue) { language in
                    Text(LocalizedStringKey(language.localizationKey)).tag(language.rawValue)
                }
            }
        } header: {
            Text("settings.language")
        }
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
    @State private var controller: BlockedUsersListController

    init(service: any BlockServing) {
        _controller = State(initialValue: BlockedUsersListController(service: service))
    }

    var body: some View {
        List {
            ForEach(controller.users) { user in
                HStack {
                    VStack(alignment: .leading) {
                        Text(user.displayName)
                            .font(.body)
                        Text("@\(user.username)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()

                    Button {
                        Task { await controller.unblock(userID: user.userId) }
                    } label: {
                        if controller.unblockingUserID == user.userId {
                            ProgressView()
                                .controlSize(.small)
                                .accessibilityLabel(String(localized: "block.unblock"))
                        } else {
                            Text(String(localized: "block.unblock"))
                        }
                    }
                    .buttonStyle(.bordered)
                    .disabled(controller.unblockingUserID != nil)
                    .accessibilityIdentifier("blocked-user-unblock-\(user.userId.uuidString.lowercased())")
                }
            }

            if let error = controller.error {
                Label(error.localizedMessage, systemImage: "exclamationmark.circle")
                    .font(.caption)
                    .foregroundStyle(.red)
                    .accessibilityLabel(error.localizedMessage)
            }

            if controller.hasNext && !controller.isLoading {
                ProgressView()
                    .frame(maxWidth: .infinity)
                    .task { await controller.loadMore() }
            }
        }
        .navigationTitle(String(localized: "settings.blocked_users"))
        .overlay {
            if controller.users.isEmpty && !controller.isLoading {
                ContentUnavailableView(
                    String(localized: "block.empty_title"),
                    systemImage: "person.slash",
                    description: Text(String(localized: "block.empty_body"))
                )
            }
        }
        .task { await controller.loadMore() }
    }
}

@MainActor
@Observable
final class BlockedUsersListController {
    private(set) var users: [BlockedUser] = []
    private(set) var isLoading = false
    private(set) var hasNext = true
    private(set) var unblockingUserID: UUID?
    private(set) var error: AppError?

    private let service: any BlockServing
    private var page = 0

    init(service: any BlockServing) {
        self.service = service
    }

    func loadMore() async {
        guard !isLoading, hasNext else { return }
        isLoading = true
        defer { isLoading = false }

        do {
            let result = try await service.listBlocked(page: page, size: 20)
            let existingIDs = Set(users.map(\.userId))
            users.append(contentsOf: result.content.filter { !existingIDs.contains($0.userId) })
            hasNext = result.hasNext
            page += 1
            error = nil
        } catch is CancellationError {
            return
        } catch let appError as AppError {
            error = appError
        } catch {
            self.error = .server
        }
    }

    func unblock(userID: UUID) async {
        guard unblockingUserID == nil else { return }
        unblockingUserID = userID
        error = nil
        defer { unblockingUserID = nil }

        do {
            try await service.unblock(userId: userID)
            users.removeAll { $0.userId == userID }
        } catch is CancellationError {
            return
        } catch let appError as AppError {
            error = appError
        } catch {
            self.error = .server
        }
    }
}
