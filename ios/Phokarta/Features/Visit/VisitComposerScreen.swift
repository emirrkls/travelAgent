import SwiftUI

struct VisitComposerScreen: View {
    @State private var controller: VisitComposerController
    @Environment(\.dismiss) private var dismiss
    @State private var showDiscardConfirmation = false
    let onPublished: (OwnerVisit) -> Void

    init(
        place: PlaceDetail,
        store: VisitStore,
        mediaService: (any VisitMediaServing)? = nil,
        draftRepository: (any VisitDraftRepository)? = nil,
        mutationRepository: (any OfflineMutationRepository)? = nil,
        mediaStore: (any DurableMediaStoring)? = nil,
        syncEngine: MutationSyncEngine? = nil,
        onPublished: @escaping (OwnerVisit) -> Void
    ) {
        _controller = State(initialValue: VisitComposerController(
            place: place,
            store: store,
            mediaService: mediaService,
            draftRepository: draftRepository,
            mutationRepository: mutationRepository,
            mediaStore: mediaStore,
            syncEngine: syncEngine
        ))
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

                Section("experience.primary") {
                    Picker("experience.primary", selection: Binding(
                        get: { controller.state.primaryExperience },
                        set: { if let value = $0 { controller.setPrimaryExperience(value) } }
                    )) {
                        Text("experience.select").tag(PrimaryExperienceCode?.none)
                        ForEach(PrimaryExperienceCode.allCases.filter { $0 != .unknown && $0 != .unknownLegacy }, id: \.rawValue) {
                            Text(displayName($0.rawValue)).tag(Optional($0))
                        }
                    }
                    if controller.state.primaryExperience == .other {
                        TextField("experience.other", text: Binding(
                            get: { controller.state.rawExperienceLabel },
                            set: { controller.setRawExperienceLabel($0) }
                        ))
                    }
                }

                Section("experience.feeling") {
                    Picker("experience.feeling", selection: Binding(
                        get: { controller.state.overallFeeling },
                        set: { if let value = $0 { controller.setOverallFeeling(value) } }
                    )) {
                        Text("experience.select").tag(OverallFeelingCode?.none)
                        ForEach(OverallFeelingCode.allCases.filter { $0 != .unknown }, id: \.rawValue) {
                            Text(displayName($0.rawValue)).tag(Optional($0))
                        }
                    }
                }

                Section("experience.context") {
                    Menu {
                        Button("experience.none") { controller.setCompanion(nil) }
                        ForEach(CompanionCode.allCases.filter { $0 != .unknown }, id: \.rawValue) { value in
                            Button(displayName(value.rawValue)) { controller.setCompanion(value) }
                        }
                    } label: {
                        LabeledContent("experience.companion", value: controller.state.companion.map { displayName($0.rawValue) } ?? String(localized: "experience.none"))
                    }
                    Menu {
                        Button("experience.none") { controller.setTimeOfDay(nil) }
                        ForEach(TimeOfDayCode.allCases.filter { $0 != .unknown }, id: \.rawValue) { value in
                            Button(displayName(value.rawValue)) { controller.setTimeOfDay(value) }
                        }
                    } label: {
                        LabeledContent("experience.time", value: controller.state.timeOfDay.map { displayName($0.rawValue) } ?? String(localized: "experience.none"))
                    }
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack {
                            ForEach(VibeCode.allCases.filter { $0 != .unknown }, id: \.rawValue) { value in
                                Button(displayName(value.rawValue)) { controller.toggleVibe(value) }
                                    .buttonStyle(.bordered)
                                    .tint(controller.state.vibes.contains(value) ? .accentColor : .secondary)
                                    .disabled(!controller.state.vibes.contains(value) && controller.state.vibes.count >= 2)
                            }
                        }
                    }
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack {
                            ForEach(PracticalSignalCode.allCases.filter { $0 != .unknown }, id: \.rawValue) { value in
                                Button(displayName(value.rawValue)) { controller.togglePracticalSignal(value) }
                                    .buttonStyle(.bordered)
                                    .tint(controller.state.practicalSignals.contains(value) ? .accentColor : .secondary)
                            }
                        }
                    }
                }

                let keys = ExperienceDimensionCatalog.keys(for: controller.state.primaryExperience)
                if !keys.isEmpty {
                    Section("experience.dimensions") {
                        ForEach(keys, id: \.self) { key in
                            Picker(VisitDimensionCatalog.localizedName(for: key), selection: Binding(
                                get: { controller.state.semanticDimensions[key] },
                                set: { controller.setSemanticDimension(key, value: $0) }
                            )) {
                                Text("experience.none").tag(DimensionStateCode?.none)
                                ForEach(DimensionStateCode.allCases.filter { $0 != .unknown }, id: \.rawValue) {
                                    Text(displayName($0.rawValue)).tag(Optional($0))
                                }
                            }
                        }
                    }
                }

                Section("experience.title") {
                    TextField("experience.title", text: Binding(
                        get: { controller.state.title },
                        set: { controller.setTitle($0) }
                    ))
                    if controller.state.titleSource == .custom {
                        Button("experience.title.generated") { controller.useGeneratedTitle() }
                    } else {
                        Text("experience.title.generated.help").foregroundStyle(.secondary)
                    }
                }

                Section("experience.story") {
                    ReviewEditor(text: controller.state.story) { controller.setReview($0) }
                    TextField("experience.tip", text: Binding(
                        get: { controller.state.tip },
                        set: { controller.setTip($0) }
                    ), axis: .vertical)
                    Text(String(localized: String.LocalizationValue(controller.state.visibility.helperLocalizationKey)))
                        .font(.footnote).foregroundStyle(.secondary)
                }

                Section {
                    VisitMediaStrip(
                        coordinator: controller.mediaCoordinator,
                        disabled: controller.state.publishState == .publishing
                    )
                }

                Section {
                    PrivateMemoryEditor(text: controller.state.privateMemory) { controller.setPrivateMemory($0) }
                } header: {
                    Text("visit.memory")
                } footer: {
                    Text("visit.memory.help")
                }

                Section("visit.visibility") {
                    VisibilityPicker(selection: controller.state.visibility) { controller.setVisibility($0) }
                }

                if let message = publishMessage {
                    Section { Text(message).foregroundStyle(.red) }
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle(String(localized: "visit.record"))
            .navigationBarTitleDisplayMode(.inline)
            .interactiveDismissDisabled(controller.isDirty)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("action.cancel") {
                        if controller.isDirty { showDiscardConfirmation = true } else { dismiss() }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        Task {
                            let visit = await controller.publish()
                            if controller.state.publishState == .success {
                                if let visit {
                                    onPublished(visit)
                                }
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
                    .disabled(!controller.canPublish)
                }
            }
            .alert("visit.discard.title", isPresented: $showDiscardConfirmation) {
                Button("visit.discard", role: .destructive) {
                    Task {
                        await controller.discard()
                        dismiss()
                    }
                }
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

    private func displayName(_ code: String) -> String {
        code.replacingOccurrences(of: "_", with: " ").capitalized
    }
}
