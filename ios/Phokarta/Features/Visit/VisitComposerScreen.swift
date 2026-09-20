import SwiftUI

struct VisitComposerScreen: View {
    @State private var controller: VisitComposerController
    @Environment(\.dismiss) private var dismiss
    @Environment(\.locale) private var locale
    @Environment(\.colorScheme) private var colorScheme
    @State private var showDiscardConfirmation = false
    @State private var disclosure = ExperienceComposerDisclosureState()
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
            if controller.state.originAcknowledgementId != nil {
                Label("experience.acknowledgement_origin", systemImage: "checkmark.circle.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.tint)
            }
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
                            Text(ExperienceLocalizedLabels.primary($0, locale: locale) ?? String(localized: "experience.unknown")).tag(Optional($0))
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
                            Text(ExperienceLocalizedLabels.feeling($0, locale: locale)).tag(Optional($0))
                        }
                    }
                }

                Section("experience.context") {
                    Menu {
                        Button("experience.none") { controller.setCompanion(nil) }
                        ForEach(CompanionCode.allCases.filter { $0 != .unknown }, id: \.rawValue) { value in
                            Button(ExperienceLocalizedLabels.companion(value, locale: locale)) { controller.setCompanion(value) }
                        }
                    } label: {
                        LabeledContent("experience.companion", value: controller.state.companion.map { ExperienceLocalizedLabels.companion($0, locale: locale) } ?? String(localized: "experience.none"))
                    }
                    Menu {
                        Button("experience.none") { controller.setTimeOfDay(nil) }
                        ForEach(TimeOfDayCode.allCases.filter { $0 != .unknown }, id: \.rawValue) { value in
                            Button(ExperienceLocalizedLabels.time(value, locale: locale)) { controller.setTimeOfDay(value) }
                        }
                    } label: {
                        LabeledContent("experience.time", value: controller.state.timeOfDay.map { ExperienceLocalizedLabels.time($0, locale: locale) } ?? String(localized: "experience.none"))
                    }
                }

                let keys = ExperienceDimensionCatalog.keys(for: controller.state.primaryExperience)
                Section {
                    DisclosureGroup(isExpanded: $disclosure.enrichExpanded) {
                        Text("experience.vibes").font(.subheadline.weight(.semibold))
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack {
                                ForEach(VibeCode.allCases.filter { $0 != .unknown }, id: \.rawValue) { value in
                                    Button(ExperienceLocalizedLabels.vibe(value, locale: locale)) { controller.toggleVibe(value) }
                                        .buttonStyle(.bordered)
                                        .tint(controller.state.vibes.contains(value) ? PhokartaColor.accent(for: colorScheme) : .secondary)
                                        .disabled(!controller.state.vibes.contains(value) && controller.state.vibes.count >= 2)
                                }
                            }
                        }
                        Text("experience.practical").font(.subheadline.weight(.semibold))
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack {
                                ForEach(PracticalSignalCode.allCases.filter { $0 != .unknown }, id: \.rawValue) { value in
                                    Button(ExperienceLocalizedLabels.practical(value, locale: locale)) { controller.togglePracticalSignal(value) }
                                        .buttonStyle(.bordered)
                                        .tint(controller.state.practicalSignals.contains(value) ? PhokartaColor.accent(for: colorScheme) : .secondary)
                                }
                            }
                        }
                        ForEach(keys, id: \.self) { key in
                            Picker(ExperienceLocalizedLabels.dimensionKey(key, locale: locale), selection: Binding(
                                get: { controller.state.semanticDimensions[key] },
                                set: { controller.setSemanticDimension(key, value: $0) }
                            )) {
                                Text("experience.none").tag(DimensionStateCode?.none)
                                ForEach(DimensionStateCode.allCases.filter { $0 != .unknown }, id: \.rawValue) { value in
                                    Text(ExperienceLocalizedLabels.dimensionState(value, locale: locale)).tag(Optional(value))
                                }
                            }
                            .pickerStyle(.menu)
                        }
                    } label: {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("experience.enrich").font(.headline)
                            Text("experience.enrich.help").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }

                Section {
                    DisclosureGroup(isExpanded: $disclosure.storyExpanded) {
                        Text("experience.photos").font(.subheadline.weight(.semibold))
                        VisitMediaStrip(
                            coordinator: controller.mediaCoordinator,
                            disabled: controller.state.publishState == .publishing
                        )
                        Text("experience.story").font(.subheadline.weight(.semibold))
                        ReviewEditor(text: controller.state.story) { controller.setReview($0) }
                        TextField("experience.tip", text: Binding(
                            get: { controller.state.tip },
                            set: { controller.setTip($0) }
                        ), axis: .vertical)
                        DisclosureGroup(isExpanded: $disclosure.titleExpanded) {
                            TextField("experience.title.customize", text: Binding(
                                get: { controller.state.title },
                                set: { controller.setTitle($0) }
                            ))
                            if controller.state.titleSource == .custom {
                                Button("experience.title.generated") { controller.useGeneratedTitle() }
                            } else {
                                Text("experience.title.generated.help").foregroundStyle(.secondary)
                            }
                        } label: {
                            Text("experience.title.customize")
                        }
                        Text(String(localized: String.LocalizationValue(controller.state.visibility.helperLocalizationKey)))
                            .font(.footnote).foregroundStyle(.secondary)
                    } label: {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("experience.tell_story").font(.headline)
                            Text("experience.tell_story.help").font(.caption).foregroundStyle(.secondary)
                        }
                    }
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
            .navigationTitle(phokartaString("experience.share", locale: locale))
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
                            Text("experience.share.action")
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

}
