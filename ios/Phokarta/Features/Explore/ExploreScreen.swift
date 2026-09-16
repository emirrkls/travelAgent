import SwiftUI

struct ExploreScreen: View {
    @State private var controller: ExperienceExploreController
    @State private var path: [AppRoute] = []
    let environment: AppEnvironment
    let currentUserId: UUID
    let onOpenMap: () -> Void
    @Environment(\.colorScheme) private var colorScheme

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
                LazyVStack(spacing: PhokartaSpacing.md) {
                    Text("experience.discover.prompt")
                        .font(.title2.bold())
                        .frame(maxWidth: .infinity, alignment: .leading)
                    lensBar
                    discoveryBar
                    if controller.needsNearbyLocation {
                        nearbyFallback
                    } else if controller.isLoading && controller.items.isEmpty {
                        ProgressView().padding(.top, 64)
                    } else if controller.items.isEmpty {
                        FeatureEmptyState(
                            title: controller.error?.localizedMessage ?? String(localized: "experience.empty"),
                            message: String(localized: "experience.empty.help"),
                            retryTitle: controller.error == nil ? nil : String(localized: "action.try_again"),
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
                                onOpen: { path.append(.experienceDetail(item.id)) },
                                onAuthor: { path.append(.userProfile(item.author.id)) },
                                onPlace: { path.append(.placeDetail(item.place.id)) },
                                onRelationship: { controller.toggleRelationship(authorId: item.author.id) }
                            )
                            .onAppear { controller.loadMoreIfNeeded(current: item) }
                        }
                        if controller.isLoadingMore { ProgressView().padding() }
                    }
                }
                .padding(.horizontal, PhokartaSpacing.md)
                .padding(.bottom, PhokartaSpacing.xl)
            }
            .background(PhokartaColor.background(for: colorScheme))
            .navigationTitle(String(localized: "experience.discover.title"))
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
                            .padding(.horizontal, 14)
                            .padding(.vertical, 9)
                            .background(
                                controller.lens == lens ? PhokartaColor.accent(for: colorScheme) : PhokartaColor.mist,
                                in: Capsule()
                            )
                            .foregroundStyle(controller.lens == lens ? Color.white : PhokartaColor.ink)
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
    let onOpen: () -> Void
    let onAuthor: () -> Void
    let onPlace: () -> Void
    let onRelationship: () -> Void
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isOpening = false

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
                    ZStack {
                        PhokartaColor.mist
                        Text(primaryLabel).font(.headline)
                    }
                    .frame(height: 92)
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
                Button(action: open) {
                    Text(experience.title)
                        .font(.title3.bold())
                        .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                        .multilineTextAlignment(.leading)
                }
                .buttonStyle(.plain)
                Button(action: onPlace) {
                    Text("\(experience.place.name) · \(experience.place.city)")
                        .font(.subheadline.weight(.semibold)).foregroundStyle(.tint)
                }
                .buttonStyle(.plain)
                if let story = experience.storyPreview, !story.isEmpty {
                    Text(story).font(.body).lineLimit(4)
                }
                if let tip = experience.tipPreview, !tip.isEmpty {
                    Text("\(String(localized: "experience.tip")) · \(tip)")
                        .font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                }
                HStack {
                    Text(feelingSymbol).accessibilityLabel(humanized(experience.feeling.rawValue))
                    if let vibe = experience.vibes.first {
                        Text(humanized(vibe.rawValue)).font(.caption)
                            .padding(.horizontal, 10).padding(.vertical, 5)
                            .background(PhokartaColor.mist, in: Capsule())
                    }
                    if experience.classification == .legacyCompatibility {
                        Text("experience.legacy").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .padding(PhokartaSpacing.md)
        }
        .background(PhokartaColor.surface(for: colorScheme), in: RoundedRectangle(cornerRadius: PhokartaRadius.xl))
        .clipShape(RoundedRectangle(cornerRadius: PhokartaRadius.xl))
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
            Button(relationshipTitle(relationship.state), action: onRelationship)
                .buttonStyle(.bordered)
                .disabled(relationshipBusy || relationship.state == .friends)
        }
    }

    private var primaryLabel: String {
        experience.primaryExperience.rawLabel ?? humanized(experience.primaryExperience.code.rawValue)
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

    private var feelingSymbol: String {
        switch experience.feeling {
        case .bayildim: "😍"
        case .guzeldi: "😊"
        case .ehIste: "😐"
        case .beklentimiKarsilamadi: "🙁"
        case .birDahaTercihEtmem: "😞"
        case .unknown: "•"
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

func humanized(_ raw: String) -> String {
    raw.lowercased().split(separator: "_").map { $0.capitalized }.joined(separator: " ")
}
