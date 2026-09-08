import SwiftUI

struct DeleteAccountScreen: View {
    @Bindable var controller: DeleteAccountController

    var body: some View {
        Form {
            switch controller.phase {
            case .idle:
                idleSection
            case .confirming:
                warningSection
                passwordSection
                deleteSection
            case .deleting:
                deletingSection
            case .credentialFailure(let message):
                warningSection
                errorBanner(message)
                passwordSection
                deleteSection
            case .retryableFailure(let message):
                errorBanner(message)
                retrySection
            case .success:
                EmptyView()
            }
        }
        .navigationTitle(String(localized: "delete_account.title"))
        .navigationBarTitleDisplayMode(.inline)
        .interactiveDismissDisabled(controller.phase == .deleting)
    }

    private var idleSection: some View {
        Section {
            Button(role: .destructive) {
                controller.showConfirmation()
            } label: {
                Label(String(localized: "delete_account.title"), systemImage: "person.crop.circle.badge.xmark")
            }
        } footer: {
            Text(String(localized: "delete_account.footer"))
        }
    }

    private var warningSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 12) {
                Label(String(localized: "delete_account.warning_title"), systemImage: "exclamationmark.triangle.fill")
                    .font(.headline)
                    .foregroundStyle(.red)

                Text(String(localized: "delete_account.warning_body"))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .padding(.vertical, 4)
        }
    }

    private var passwordSection: some View {
        Section {
            SecureField(String(localized: "delete_account.password_placeholder"), text: $controller.currentPassword)
                .textContentType(.password)
                .autocorrectionDisabled()
        } header: {
            Text(String(localized: "delete_account.password_header"))
        } footer: {
            Text(String(localized: "delete_account.password_footer"))
        }
    }

    private var deleteSection: some View {
        Section {
            Button(role: .destructive) {
                controller.confirmDeletion()
            } label: {
                Text(String(localized: "delete_account.confirm_action"))
                    .frame(maxWidth: .infinity)
            }
            .accessibilityHint(String(localized: "delete_account.confirm_hint"))
        }
    }

    private var deletingSection: some View {
        Section {
            HStack {
                ProgressView()
                Text(String(localized: "delete_account.deleting"))
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .center)
        }
    }

    private func errorBanner(_ message: String) -> some View {
        Section {
            Label(message, systemImage: "exclamationmark.circle")
                .foregroundStyle(.red)
        }
    }

    private var retrySection: some View {
        Section {
            Button {
                controller.retryDeletion()
            } label: {
                Text(String(localized: "action.try_again"))
                    .frame(maxWidth: .infinity)
            }
        }
    }
}
