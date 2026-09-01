import SwiftUI

struct VisitComposerScreen: View {
    @State private var controller: VisitComposerController
    @Environment(\.dismiss) private var dismiss
    @State private var showDiscardConfirmation = false
    let onPublished: (OwnerVisit) -> Void

    init(place: PlaceDetail, store: VisitStore, onPublished: @escaping (OwnerVisit) -> Void) {
        _controller = State(initialValue: VisitComposerController(place: place, store: store))
        self.onPublished = onPublished
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(controller.state.placeName).font(.headline)
                    DatePicker(
                        "visit.date",
                        selection: Binding(get: { controller.state.visitedAt }, set: { controller.setDate($0) }),
                        in: ...Date(),
                        displayedComponents: .date
                    )
                }

                Section("visit.overall") {
                    OverallRatingControl(value: controller.state.overallScore, onChange: controller.setOverall)
                }

                let keys = VisitDimensionCatalog.keys(for: controller.state.category)
                if !keys.isEmpty {
                    Section {
                        ForEach(keys, id: \.self) { key in
                            DimensionRatingRow(
                                key: key,
                                value: controller.state.dimensionScores[key],
                                onEnable: { controller.enableDimension(key) },
                                onChange: { controller.setDimension(key, value: $0) },
                                onRemove: { controller.removeDimension(key) }
                            )
                        }
                    } header: {
                        Text("visit.dimensions")
                    } footer: {
                        Text("visit.dimensions.help")
                    }
                }

                Section {
                    ReviewEditor(text: controller.state.publicReview, onChange: controller.setReview)
                } header: {
                    Text("visit.review")
                } footer: {
                    Text(String(localized: String.LocalizationValue(controller.state.visibility.helperLocalizationKey)))
                }

                Section {
                    PrivateMemoryEditor(text: controller.state.privateMemory, onChange: controller.setPrivateMemory)
                } header: {
                    Text("visit.memory")
                } footer: {
                    Text("visit.memory.help")
                }

                Section("visit.visibility") {
                    VisibilityPicker(selection: controller.state.visibility, onChange: controller.setVisibility)
                }

                if let message = publishMessage {
                    Section { Text(message).foregroundStyle(.red) }
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle(String(localized: "visit.record"))
            .navigationBarTitleDisplayMode(.inline)
            .interactiveDismissDisabled(controller.state.isDirty)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("action.cancel") {
                        if controller.state.isDirty { showDiscardConfirmation = true } else { dismiss() }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        Task {
                            if let visit = await controller.publish() {
                                onPublished(visit)
                                dismiss()
                            }
                        }
                    } label: {
                        if controller.state.publishState == .publishing {
                            ProgressView().accessibilityLabel(String(localized: "visit.publishing"))
                        } else {
                            Text("visit.publish")
                        }
                    }
                    .disabled(!controller.state.canPublish)
                }
            }
            .alert("visit.discard.title", isPresented: $showDiscardConfirmation) {
                Button("visit.discard", role: .destructive) { dismiss() }
                Button("action.cancel", role: .cancel) {}
            } message: {
                Text("visit.discard.message")
            }
        }
    }

    private var publishMessage: String? {
        switch controller.state.publishState {
        case .idle, .publishing, .success: nil
        case .retryableFailure(let failure): failure.localizedMutationMessage(fallbackKey: "visit.error.publish")
        case .validationFailure(let message): message
        case .policyRequired: String(localized: "visit.error.policy")
        }
    }
}
