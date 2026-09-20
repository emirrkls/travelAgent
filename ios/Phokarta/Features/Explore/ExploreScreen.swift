import SwiftUI

struct ExploreScreen: View {
    @State private var controller: ExperienceExploreController
    @State private var path: [AppRoute] = []
    let environment: AppEnvironment
    let currentUserId: UUID
    let onOpenMap: () -> Void
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.locale) private var locale

    init(
        environment: AppEnvironment,
        currentUserId: UUID,
        onOpenMap: @escaping () -> Void
    ) {
        self.environment = environment
        self.currentUserId = currentUserId
        self.onOpenMap = onOpenMap
        _controller = State(initialValue: ExperienceExploreController(
            service: environment.experiences,
            privacy: environment.privacy
        ))
    }

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                LazyVStack(spacing: 12) {
                    Text("experience.discover.prompt")
                        .font(.headline)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    lensBar
                    discoveryBar
                    if controller.needsNearbyLocation {
                        nearbyFallback
                    } else if controller.isLoading && controller.items.isEmpty {
                        ProgressView().padding(.top, 64)
                    } else if controller.items.isEmpty {
                        FeatureEmptyState(
                            title: controller.error?.localizedMessage ?? phokartaString(controller.lens == .following ? "experience.empty.following" : "experience.empty", locale: locale),
                            message: phokartaString(controller.lens == .following ? "experience.empty.following.help" : "experience.empty.help", locale: locale),
                            retryTitle: controller.error == nil ? nil : phokartaString("action.try_again", locale: locale),
                            retry: controller.error == nil ? nil : { controller.reload() }
                        )
                        .padding(.top, 44)
                    } else {
                        if let error = controller.error {
                            Text(error.localizedMessage)
                                .font(.footnote)
                                .foregroundStyle(.red)
                        }
                        ForEach(controller.items) { item in
                            ExperienceCardView(
                                experience: item,
                                relationshipBusy: controller.relationshipBusy.contains(item.author.id),
                                planBusy: controller.planBusy.contains(item.id),
                                acknowledgementBusy: controller.acknowledgementBusy.contains(item.id),
                                onOpen: { path.append(.experienceDetail(item.id)) },
                                onAuthor: { path.append(.userProfile(item.author.id)) },
                                onPlace: { path.append(.placeDetail(item.place.id)) },
                                onRelationship: { controller.toggleRelationship(authorId: item.author.id) },
                                onPlan: { controller.togglePlan(experienceId: item.id) },
                                onAcknowledge: { controller.acknowledge(experienceId: item.id) }
                            )
                            .onAppear { controller.loadMoreIfNeeded(current: item) }
                        }
                        if controller.isLoadingMore { ProgressView().padding() }
                    }
                }
                .padding(.horizontal, PhokartaSpacing.md)
                .padding(.bottom, PhokartaSpacing.lg)
            }
            .background(PhokartaColor.background(for: colorScheme))
            .navigationTitle(phokartaString("experience.discover.title", locale: locale))
            .searchable(
                text: Binding(get: { controller.query }, set: { controller.setQuery($0) }),
                prompt: Text("experience.search")
            )
            .refreshable { controller.reload() }
            .navigationDestination(for: AppRoute.self) { route in
                AppRouteDestinationView(
                    route: route,
                    environment: environment,
                    currentUserId: currentUserId,
                    onNavigate: { path.append($0) }
                )
            }
        }
        .task { controller.start() }
    }

    private var lensBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: PhokartaSpacing.sm) {
                ForEach(ExperienceFeedLens.allCases, id: \.rawValue) { lens in
                    Button {
                        controller.selectLens(lens)
                    } label: {
                        Text(String(localized: String.LocalizationValue(lens.localizationKey)))
                            .font(.subheadline.weight(.semibold))
                            .padding(.horizontal, 13)
                            .padding(.vertical, 9)
                            .background(
                                controller.lens == lens ? PhokartaColor.selected(for: colorScheme) : PhokartaColor.surface(for: colorScheme),
                                in: RoundedRectangle(cornerRadius: 10)
                            )
                            .overlay(RoundedRectangle(cornerRadius: 10).stroke(
                                controller.lens == lens ? PhokartaColor.accent(for: colorScheme) : PhokartaColor.border(for: colorScheme),
                                lineWidth: 1
                            ))
                            .foregroundStyle(controller.lens == lens ? PhokartaColor.ink(for: colorScheme) : PhokartaColor.muted(for: colorScheme))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.vertical, PhokartaSpacing.sm)
        }
    }

    private var nearbyFallback: some View {
        VStack(spacing: PhokartaSpacing.md) {
            Image(systemName: "location.circle").font(.system(size: 44)).foregroundStyle(.tint)
            Text("experience.nearby.title").font(.title3.bold())
            Text("experience.nearby.help").foregroundStyle(.secondary).multilineTextAlignment(.center)
            Button("experience.nearby.use_location") { controller.requestNearbyLocation() }
                .buttonStyle(.borderedProminent)
            Button("experience.nearby.open_map", action: onOpenMap)
                .buttonStyle(.bordered)
        }
        .padding(.top, 54)
    }

    private var discoveryBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: PhokartaSpacing.sm) {
                ForEach(ExperienceDiscoveryFilter.allCases, id: \.rawValue) { filter in
                    Button {
                        controller.selectDiscoveryFilter(filter)
                    } label: {
                        Text(String(localized: String.LocalizationValue(filter.localizationKey)))
                            .font(.caption.weight(.semibold))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 7)
                            .background(
                                controller.discoveryFilter == filter
                                    ? PhokartaColor.accent(for: colorScheme).opacity(0.18)
                                    : PhokartaColor.surface(for: colorScheme),
                                in: Capsule()
                            )
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(controller.discoveryFilter == filter ? .isSelected : [])
                }
            }
        }
    }
}

struct ExperienceCardView: View {
    let experience: ExperienceSummaryV2
    let relationshipBusy: Bool
    let planBusy: Bool
    let acknowledgementBusy: Bool
    let onOpen: () -> Void
    let onAuthor: () -> Void
    let onPlace: () -> Void
    let onRelationship: () -> Void
    let onPlan: (() -> Void)?
    let onAcknowledge: (() -> Void)?
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isOpening = false

    init(
        experience: ExperienceSummaryV2,
        relationshipBusy: Bool,
        planBusy: Bool = false,
        acknowledgementBusy: Bool = false,
        onOpen: @escaping () -> Void,
        onAuthor: @escaping () -> Void,
        onPlace: @escaping () -> Void,
        onRelationship: @escaping () -> Void,
        onPlan: (() -> Void)? = nil,
        onAcknowledge: (() -> Void)? = nil
    ) {
        self.experience = experience
        self.relationshipBusy = relationshipBusy
        self.planBusy = planBusy
        self.acknowledgementBusy = acknowledgementBusy
        self.onOpen = onOpen
        self.onAuthor = onAuthor
        self.onPlace = onPlace
        self.onRelationship = onRelationship
        self.onPlan = onPlan
        self.onAcknowledge = onAcknowledge
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button(action: open) {
                if let preview = experience.mediaPreview, let url = URL(string: preview.url) {
                    ZStack(alignment: .bottomTrailing) {
                        AsyncImage(url: url) { image in
                            image.resizable().scaledToFill()
                        } placeholder: {
                            Rectangle().fill(PhokartaColor.mist)
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 220)
                        .clipped()
                        if experience.mediaCount > 1 {
                            Text(String.localizedStringWithFormat(
                                String(localized: "experience.media_count"),
                                experience.mediaCount
                            ))
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(.black.opacity(0.7), in: Capsule())
                            .padding(12)
                        }
                    }
                } else {
                    ZStack(alignment: .bottomLeading) {
                        PhokartaColor.softSurface(for: colorScheme)
                        Image(systemName: "safari.fill")
                            .font(.system(size: 30))
                            .foregroundStyle(PhokartaColor.accent(for: colorScheme).opacity(0.45))
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                            .padding(16)
                        VStack(alignment: .leading, spacing: 3) {
                            if let primaryLabel { Text(primaryLabel).font(.caption.weight(.semibold)).foregroundStyle(.tint) }
                            if showsTitle { Text(experience.title).font(.title3.bold()) }
                            Text(experience.place.name).font(.subheadline).foregroundStyle(.secondary)
                        }
                        .padding(16)
                    }
                    .frame(height: 130)
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String.localizedStringWithFormat(
                String(localized: "experience.open"),
                experience.title
            ))
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 10) {
                    AsyncImage(url: experience.author.avatarUrl.flatMap(URL.init(string:))) { image in
                        image.resizable().scaledToFill()
                    } placeholder: {
                        Image(systemName: "person.crop.circle.fill").resizable().foregroundStyle(.secondary)
                    }
                    .frame(width: 38, height: 38).clipShape(Circle())
                    Button(action: onAuthor) {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(experience.author.displayName).font(.subheadline.bold())
                            Text(experience.experiencedAt).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .buttonStyle(.plain)
                    Spacer()
                    relationshipButton
                }
                if experience.mediaPreview != nil && showsTitle {
                    Button(action: open) {
                        Text(experience.title)
                            .font(.title3.bold())
                            .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                            .multilineTextAlignment(.leading)
                    }
                    .buttonStyle(.plain)
                }
                if let story = experience.storyPreview, !story.isEmpty {
                    Text(story).font(.body).lineLimit(4)
                }
                if let tip = experience.tipPreview, !tip.isEmpty {
                    Text("\(String(localized: "experience.tip")) · \(tip)")
                        .font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                }
                HStack {
                    Text(ExperienceLocalizedLabels.feeling(experience.feeling, locale: locale))
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 10).padding(.vertical, 6)
                        .background(PhokartaColor.selected(for: colorScheme), in: Capsule())
                    ForEach(contextLabels.prefix(2), id: \.self) { label in
                        Text(label).font(.caption)
                            .padding(.horizontal, 10).padding(.vertical, 5)
                            .background(PhokartaColor.softSurface(for: colorScheme), in: Capsule())
                    }
                }
                if let onPlan {
                    HStack(spacing: PhokartaSpacing.sm) {
                    Button(action: onPlan) {
                        Label(
                            String(localized: (experience.plannedByViewer ?? false) ? "experience.planned" : "experience.plan"),
                            systemImage: (experience.plannedByViewer ?? false) ? "bookmark.fill" : "bookmark"
                        )
                    }
                    .buttonStyle(.bordered)
                    .disabled(planBusy)
                    if let onAcknowledge, experience.author.relationship != nil {
                        Button(action: onAcknowledge) {
                            Label(
                                String(localized: (experience.acknowledgedByViewer ?? false) ? "experience.acknowledged" : "experience.acknowledge"),
                                systemImage: (experience.acknowledgedByViewer ?? false) ? "checkmark.circle.fill" : "checkmark.circle"
                            )
                        }
                        .buttonStyle(.bordered)
                        .disabled(acknowledgementBusy || (experience.acknowledgedByViewer ?? false))
                    }
                    }
                }
                if (experience.acknowledgementCount ?? 0) > 0 {
                    Text(String(localized: "experience.acknowledgement_count \(experience.acknowledgementCount ?? 0)"))
                        .font(.caption).foregroundStyle(.secondary)
                }
                Button(action: onPlace) {
                    Label("\(experience.place.name) · \(experience.place.city)", systemImage: "mappin.and.ellipse")
                        .font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                Divider()
                Button(action: open) {
                    HStack {
                        Spacer()
                        Text("experience.view_details").font(.subheadline.weight(.semibold))
                        Image(systemName: "arrow.right")
                    }
                }
                .buttonStyle(.plain)
            }
            .padding(PhokartaSpacing.md)
        }
        .background(PhokartaColor.surface(for: colorScheme), in: RoundedRectangle(cornerRadius: PhokartaRadius.lg))
        .clipShape(RoundedRectangle(cornerRadius: PhokartaRadius.lg))
        .overlay(RoundedRectangle(cornerRadius: PhokartaRadius.lg).stroke(PhokartaColor.border(for: colorScheme), lineWidth: 1))
        .scaleEffect(isOpening && !reduceMotion ? 1.015 : 1)
        .shadow(
            color: .black.opacity(colorScheme == .dark ? 0 : (isOpening ? 0.16 : 0.08)),
            radius: isOpening && !reduceMotion ? 16 : 10,
            y: isOpening && !reduceMotion ? 6 : 3
        )
        .animation(reduceMotion ? nil : .easeOut(duration: 0.14), value: isOpening)
    }

    @ViewBuilder private var relationshipButton: some View {
        if let relationship = experience.author.relationship,
           relationship.state != .unavailable, relationship.state != .unknown {
            if relationship.state == .friends {
                Text(relationshipTitle(relationship.state))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(colorScheme == .dark ? Color(red: 204 / 255, green: 245 / 255, blue: 251 / 255) : PhokartaColor.ink(for: colorScheme))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 7)
                    .background(PhokartaColor.selected(for: colorScheme), in: Capsule())
                    .overlay(Capsule().stroke(PhokartaColor.accent(for: colorScheme).opacity(0.35), lineWidth: 1))
            } else {
                Button(relationshipTitle(relationship.state), action: onRelationship)
                    .buttonStyle(.bordered)
                    .disabled(relationshipBusy)
            }
        }
    }

    private var primaryLabel: String? {
        experience.primaryExperience.rawLabel ?? ExperienceLocalizedLabels.primary(experience.primaryExperience.code, locale: locale)
    }

    private var showsTitle: Bool {
        ExperienceLocalizedLabels.shouldShowTitle(experience.title, placeName: experience.place.name, classification: experience.classification)
    }

    private var contextLabels: [String] {
        var values: [String] = []
        if let companion = experience.companion { values.append(ExperienceLocalizedLabels.companion(companion, locale: locale)) }
        if let time = experience.timeOfDay { values.append(ExperienceLocalizedLabels.time(time, locale: locale)) }
        values.append(contentsOf: experience.vibes.map { ExperienceLocalizedLabels.vibe($0, locale: locale) })
        return values
    }

    private func open() {
        if !reduceMotion { isOpening = true }
        onOpen()
        guard !reduceMotion else { return }
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(180))
            isOpening = false
        }
    }

    private func relationshipTitle(_ state: RelationshipActionState) -> LocalizedStringKey {
        switch state {
        case .none: "social.follow"
        case .requestPending: "experience.relationship.requested"
        case .following: "experience.relationship.following"
        case .friends: "experience.relationship.friends"
        default: ""
        }
    }
}
