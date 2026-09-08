import SwiftUI

struct PolicyAcceptanceScreen: View {
    let store: PolicyStatusStore
    var syncEngine: MutationSyncEngine? = nil
    let onAccepted: () -> Void
    @State private var isChecked = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    // Draft warning
                    Label(String(localized: "policy.draft_warning"), systemImage: "exclamationmark.triangle")
                        .font(.caption)
                        .foregroundStyle(.orange)
                        .padding(.horizontal)

                    Text(String(localized: "policy.intro"))
                        .font(.body)
                        .padding(.horizontal)

                    if let version = store.requiredVersion {
                        Text(String(localized: "policy.version \(version)"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal)
                    }

                    Divider()

                    // Checkbox
                    Button {
                        isChecked.toggle()
                    } label: {
                        HStack(alignment: .top, spacing: 12) {
                            Image(systemName: isChecked ? "checkmark.square.fill" : "square")
                                .font(.title3)
                                .foregroundStyle(isChecked ? Color.accentColor : Color.secondary)
                            Text(String(localized: "policy.checkbox_label"))
                                .font(.subheadline)
                                .foregroundStyle(.primary)
                                .multilineTextAlignment(.leading)
                        }
                        .padding(.horizontal)
                    }
                    .accessibilityAddTraits(isChecked ? .isSelected : [])
                    .accessibilityLabel(String(localized: "policy.checkbox_accessibility"))

                    if let error = store.error {
                        Label(error.localizedMessage, systemImage: "exclamationmark.circle")
                            .foregroundStyle(.red)
                            .font(.caption)
                            .padding(.horizontal)
                    }

                    Button {
                        Task {
                            let accepted = await store.acceptPolicy()
                            if accepted {
                                _ = await syncEngine?.drain()
                                onAccepted()
                            }
                        }
                    } label: {
                        if store.isLoading {
                            ProgressView()
                                .frame(maxWidth: .infinity)
                        } else {
                            Text(String(localized: "policy.accept_action"))
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!isChecked || store.isLoading)
                    .padding(.horizontal)

                    Spacer()
                }
                .padding(.vertical)
            }
            .navigationTitle(String(localized: "policy.title"))
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
