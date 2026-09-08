import SwiftUI

struct ReportSheet: View {
    @Bindable var controller: ReportController
    let onDismiss: () -> Void

    var body: some View {
        NavigationStack {
            Group {
                switch controller.phase {
                case .selectingReason:
                    reportForm
                case .submitting:
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                case .success:
                    reportSuccess
                case .rateLimited:
                    reportRateLimited
                case .error(let error):
                    reportError(error)
                default:
                    EmptyView()
                }
            }
            .navigationTitle(String(localized: "report.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "action.cancel")) {
                        controller.cancel()
                        onDismiss()
                    }
                }
            }
        }
    }

    private var reportForm: some View {
        Form {
            if let target = controller.target {
                Section {
                    Text(target.displayContext)
                        .font(.headline)
                } header: {
                    Text(controller.target?.targetType == .user
                         ? String(localized: "report.target.user")
                         : String(localized: "report.target.visit"))
                }
            }

            Section {
                ForEach(ReportReason.allCases, id: \.self) { reason in
                    Button {
                        controller.selectedReason = reason
                    } label: {
                        HStack {
                            Text(reason.localizedLabel)
                                .foregroundStyle(.primary)
                            Spacer()
                            if controller.selectedReason == reason {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(Color.accentColor)
                                    .accessibilityLabel(String(localized: "accessibility.selected"))
                            }
                        }
                    }
                    .accessibilityAddTraits(controller.selectedReason == reason ? .isSelected : [])
                }
            } header: {
                Text(String(localized: "report.select_reason"))
            }

            Section {
                TextField(
                    String(localized: "report.details_placeholder"),
                    text: $controller.details,
                    axis: .vertical
                )
                .lineLimit(3...8)
                .onChange(of: controller.details) { _, newValue in
                    if newValue.count > ReportController.maxDetailsLength {
                        controller.details = String(newValue.prefix(ReportController.maxDetailsLength))
                    }
                }

                Text("\(controller.details.count)/\(ReportController.maxDetailsLength)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            } header: {
                Text(String(localized: "report.details_header"))
            } footer: {
                Text(String(localized: "report.details_footer"))
            }

            Section {
                Button(String(localized: "report.submit")) {
                    controller.submit()
                }
                .disabled(controller.selectedReason == nil)
                .frame(maxWidth: .infinity)
                .accessibilityHint(String(localized: "report.submit_hint"))
            }
        }
    }

    private var reportSuccess: some View {
        ContentUnavailableView {
            Label(String(localized: "report.submitted_title"), systemImage: "checkmark.circle")
        } description: {
            Text(String(localized: "report.submitted_body"))
        } actions: {
            Button(String(localized: "action.done")) {
                controller.dismiss()
                onDismiss()
            }
        }
    }

    private var reportRateLimited: some View {
        ContentUnavailableView {
            Label(String(localized: "report.rate_limited_title"), systemImage: "clock")
        } description: {
            Text(String(localized: "report.rate_limited_body"))
        } actions: {
            Button(String(localized: "action.done")) {
                controller.dismiss()
                onDismiss()
            }
        }
    }

    private func reportError(_ error: AppError) -> some View {
        ContentUnavailableView {
            Label(String(localized: "report.error_title"), systemImage: "exclamationmark.triangle")
        } description: {
            Text(error.localizedMessage)
        } actions: {
            Button(String(localized: "action.try_again")) {
                controller.phase = .selectingReason
            }
        }
    }
}
