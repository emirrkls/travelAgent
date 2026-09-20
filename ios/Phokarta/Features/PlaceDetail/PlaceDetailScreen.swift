import SwiftUI

struct PlaceDetailScreen: View {
    @State private var controller: PlaceDetailController
    @State private var showingCollections = false
    @State private var showingVisitComposer = false
    @State private var pendingVisits: [PendingVisit] = []
    @State private var selectedPending: PendingVisit?
    @State private var replaceDraftTarget: PendingVisit?
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.locale) private var locale
    private let saved: SavedPlaceStore
    private let collections: CollectionStore
    private let visits: VisitStore
    private let draftRepository: (any VisitDraftRepository)?
    private let mutationRepository: (any OfflineMutationRepository)?
    private let mediaStore: (any DurableMediaStoring)?
    private let syncEngine: MutationSyncEngine?
    private let onSelectUser: ((UUID) -> Void)?
    private let onSelectExperience: ((UUID) -> Void)?
    private let openComposerOnLoad: Bool

    init(
        placeId: UUID,
        places: any PlaceServing,
        saved: SavedPlaceStore,
        collections: CollectionStore,
        visits: VisitStore,
        draftRepository: (any VisitDraftRepository)? = nil,
        mutationRepository: (any OfflineMutationRepository)? = nil,
        mediaStore: (any DurableMediaStoring)? = nil,
        syncEngine: MutationSyncEngine? = nil,
        onSelectUser: ((UUID) -> Void)? = nil,
        experienceService: (any ExperienceDiscoveryServing)? = nil,
        privacyService: (any PrivacyV2Serving)? = nil,
        onSelectExperience: ((UUID) -> Void)? = nil,
        openComposerOnLoad: Bool = false
    ) {
        self.saved = saved
        self.collections = collections
        self.visits = visits
        self.draftRepository = draftRepository
        self.mutationRepository = mutationRepository
        self.mediaStore = mediaStore
        self.syncEngine = syncEngine
        self.onSelectUser = onSelectUser
        self.onSelectExperience = onSelectExperience
        self.openComposerOnLoad = openComposerOnLoad
        _controller = State(initialValue: PlaceDetailController(
            placeId: placeId,
            places: places,
            experiences: experienceService,
            privacy: privacyService
        ))
    }

    var body: some View {
        Group {
            switch controller.phase {
            case .idle, .loading:
                loadingView
            case .unavailable:
                FeatureEmptyState(
                    title: String(localized: "place.unavailable"),
                    retryTitle: String(localized: "explore.retry"),
                    retry: { controller.retry() }
                )
            case .error(let kind):
                FeatureEmptyState(
                    title: kind.localizedMessage,
                    retryTitle: kind.showsRetry ? String(localized: "explore.retry") : nil,
                    retry: kind.showsRetry ? { controller.retry() } : nil
                )
            case .content:
                if let content = controller.content {
                    contentView(content)
                } else {
                    loadingView
                }
            }
        }
        .background(PhokartaColor.background(for: colorScheme))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            controller.startIfNeeded()
            await reloadPendingVisits()
        }
        .onChange(of: controller.content?.place.id, initial: true) { _, placeId in
            if openComposerOnLoad && placeId != nil {
                showingVisitComposer = true
            }
        }
        .onDisappear {
            controller.cancel()
        }
        .sheet(isPresented: $showingCollections) {
            if let place = controller.content?.place {
                CollectionPickerSheet(store: collections, place: place)
            }
        }
        .sheet(isPresented: $showingVisitComposer) {
            if let place = controller.content?.place {
                VisitComposerScreen(
                    place: place,
                    store: visits,
                    draftRepository: draftRepository,
                    mutationRepository: mutationRepository,
                    mediaStore: mediaStore,
                    syncEngine: syncEngine
                ) { _ in
                    controller.retry()
                    Task { await reloadPendingVisits() }
                }
            }
        }
        .sheet(item: $selectedPending) { pending in
            if let place = controller.content?.place {
                PendingVisitDetailSheet(
                    place: place,
                    pending: pending,
                    onDismiss: { selectedPending = nil },
                    onRetry: {
                        if let mutationRepository, let userId = visits.accountID {
                            Task {
                                try? await mutationRepository.retry(mutationId: pending.mutationId, userId: userId)
                                _ = await syncEngine?.drain()
                                await reloadPendingVisits()
                            }
                        }
                    },
                    onEditAndRetry: {
                        if let mutationRepository, let userId = visits.accountID {
                            Task {
                                let result = try? await mutationRepository.recoverFailedVisitForEditing(
                                    mutationId: pending.mutationId,
                                    userId: userId,
                                    replaceExisting: false
                                )
                                if result == .existingDraftConflict {
                                    replaceDraftTarget = pending
                                } else if result == .success {
                                    selectedPending = nil
                                    await reloadPendingVisits()
                                    showingVisitComposer = true
                                }
                            }
                        }
                    },
                    onRemove: {
                        if let mutationRepository, let userId = visits.accountID {
                            Task {
                                _ = try? await mutationRepository.removeFailedVisit(
                                    mutationId: pending.mutationId,
                                    userId: userId
                                )
                                await reloadPendingVisits()
                            }
                        }
                    },
                    onAcceptPolicy: {
                        if let mutationRepository, let userId = visits.accountID {
                            Task {
                                try? await mutationRepository.retry(mutationId: pending.mutationId, userId: userId)
                                _ = await syncEngine?.drain()
                                await reloadPendingVisits()
                            }
                        }
                    }
                )
            }
        }
        .alert(String(localized: "sync.replace_draft_title"), isPresented: Binding(
            get: { replaceDraftTarget != nil },
            set: { if !$0 { replaceDraftTarget = nil } }
        )) {
            Button(String(localized: "collections.cancel"), role: .cancel) {
                replaceDraftTarget = nil
            }
            Button(String(localized: "sync.replace_draft_confirm"), role: .destructive) {
                if let target = replaceDraftTarget, let mutationRepository, let userId = visits.accountID {
                    Task {
                        _ = try? await mutationRepository.recoverFailedVisitForEditing(
                            mutationId: target.mutationId,
                            userId: userId,
                            replaceExisting: true
                        )
                        replaceDraftTarget = nil
                        selectedPending = nil
                        await reloadPendingVisits()
                        showingVisitComposer = true
                    }
                }
            }
        } message: {
            Text("sync.replace_draft_message")
        }
    }

    private var loadingView: some View {
        VStack(spacing: PhokartaSpacing.md) {
            ProgressView()
                .accessibilityLabel(String(localized: "place.loading"))
            Text("place.loading")
                .font(.body)
                .foregroundStyle(PhokartaColor.muted(for: colorScheme))
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func contentView(_ content: PlaceDetailContent) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: PhokartaSpacing.lg) {
                PlaceImageView(path: content.place.coverImage)
                    .frame(maxWidth: .infinity)
                    .frame(height: 240)
                    .clipShape(RoundedRectangle(cornerRadius: PhokartaRadius.lg, style: .continuous))

                VStack(alignment: .leading, spacing: PhokartaSpacing.sm) {
                    Text(content.place.category.localizedName.uppercased())
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(PhokartaColor.sage)
                    Text(content.place.name)
                        .font(.largeTitle.bold())
                        .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                        .fixedSize(horizontal: false, vertical: true)
                    Text(locationLine(content.place))
                        .font(.subheadline)
                        .foregroundStyle(PhokartaColor.muted(for: colorScheme))
                        .fixedSize(horizontal: false, vertical: true)
                    if !content.place.address.isEmpty {
                        Text(content.place.address)
                            .font(.body)
                            .foregroundStyle(PhokartaColor.muted(for: colorScheme))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    statusChips(content)
                    if let error = saved.error(for: content.place.id) {
                        Text(error.localizedMutationMessage(
                            fallbackKey: saved.isSaved(content.place.id)
                                ? "saved.unable_remove" : "saved.unable_save"
                        ))
                            .font(.footnote)
                            .foregroundStyle(.red)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }

                if let refreshError = controller.refreshError {
                    FeatureEmptyState(
                        title: refreshError.localizedMessage,
                        retryTitle: refreshError.showsRetry ? String(localized: "explore.retry") : nil,
                        retry: refreshError.showsRetry ? { controller.retry() } : nil
                    )
                }

                experienceSection
                scores(content)

                if !content.place.description.isEmpty {
                    VStack(alignment: .leading, spacing: PhokartaSpacing.sm) {
                        FeatureSectionHeader(title: String(localized: "place.why"))
                        Text(content.place.description)
                            .font(.body)
                            .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }

                if !content.place.dimensionScores.isEmpty {
                    dimensions(content.place.dimensionScores)
                }

                if let friends = content.friends, !friends.friends.isEmpty {
                    FriendsPreviewList(friends: friends.friends, onSelectFriend: onSelectUser)
                }

                reviews(content)
                ownerVisits(content.place.id)
                photos(content.place)
            }
            .padding(PhokartaSpacing.md)
        }
        .refreshable {
            await controller.refresh()
        }
    }

    private var experienceSection: some View {
        VStack(alignment: .leading, spacing: PhokartaSpacing.md) {
            Text("place.experiences.title").font(.title2.bold())
            if let aggregate = controller.experienceAggregate {
                Text(String.localizedStringWithFormat(phokartaString("place.experiences.count", locale: locale), aggregate.visibleExperienceCount))
                    .font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                Text(String.localizedStringWithFormat(phokartaString("place.experiences.evaluations", locale: locale), aggregate.communityContributionCount))
                    .font(.caption).foregroundStyle(.secondary)
                if let primary = aggregate.primaryExperiences, !primary.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 10) {
                            Button("filter.all") { controller.selectPrimary(nil) }
                                .buttonStyle(.bordered)
                                .lineLimit(1)
                            ForEach(primary.filter { $0.code != .unknownLegacy && $0.code != .unknown }, id: \.code.rawValue) { item in
                                Button("\(ExperienceLocalizedLabels.primary(item.code, locale: locale) ?? phokartaString("experience.unknown", locale: locale)) · \(item.visibleExperienceCount)") {
                                    controller.selectPrimary(item.code)
                                }
                                .buttonStyle(.bordered)
                                .lineLimit(1)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }
                experienceInsights(aggregate)
            }
            if controller.experiencesLoading && controller.experiences.isEmpty {
                ProgressView().frame(maxWidth: .infinity)
            } else if controller.experiences.isEmpty {
                Text("experience.empty").foregroundStyle(.secondary)
            } else {
                ForEach(controller.experiences) { experience in
                    ExperienceCardView(
                        experience: experience,
                        relationshipBusy: controller.relationshipBusy.contains(experience.author.id),
                        planBusy: controller.planBusy.contains(experience.id),
                        acknowledgementBusy: controller.acknowledgementBusy.contains(experience.id),
                        onOpen: { onSelectExperience?(experience.id) },
                        onAuthor: { onSelectUser?(experience.author.id) },
                        onPlace: {},
                        onRelationship: { controller.toggleExperienceRelationship(authorId: experience.author.id) },
                        onPlan: { controller.togglePlannedExperience(id: experience.id) },
                        onAcknowledge: { controller.acknowledgeExperience(id: experience.id) }
                    )
                }
                if controller.experiencesHasMore {
                    Button("experience.load_more", action: controller.loadMoreExperiences)
                        .frame(maxWidth: .infinity)
                }
            }
            if let error = controller.experiencesError {
                Text(error.localizedMessage).font(.footnote).foregroundStyle(.red)
            }
        }
    }

    private func locationLine(_ place: PlaceDetail) -> String {
        place.city
    }

    private func statusChips(_ content: PlaceDetailContent) -> some View {
        HStack(spacing: PhokartaSpacing.sm) {
            Button {
                saved.toggle(content.place.id)
            } label: {
                HStack(spacing: 6) {
                    if saved.isBusy(content.place.id) {
                        ProgressView().controlSize(.small)
                    } else {
                        Image(systemName: saved.isSaved(content.place.id) ? "bookmark.fill" : "bookmark")
                    }
                    Text(String(localized: String.LocalizationValue(
                        saved.isSaved(content.place.id) ? "saved.remove" : "saved.save"
                    )))
                }
                .font(.caption.weight(.semibold))
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(PhokartaColor.mist, in: Capsule())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(localized: String.LocalizationValue(
                saved.isSaved(content.place.id) ? "saved.remove" : "saved.save"
            )))

            Button { showingCollections = true } label: {
                Label("collections.add", systemImage: "square.stack.badge.plus")
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(PhokartaColor.mist, in: Capsule())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(localized: "collections.add"))

            Button { showingVisitComposer = true } label: {
                Label("experience.share", systemImage: "plus.circle")
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(PhokartaColor.mist, in: Capsule())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(localized: "experience.share"))

            if visits.latest(for: content.place.id) != nil || content.personal != nil {
                Text("place.visited")
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(PhokartaColor.mist, in: Capsule())
                    .accessibilityLabel(String(localized: "a11y.visited.readonly"))
            }
        }
    }

    private func scores(_ content: PlaceDetailContent) -> some View {
        let ownerRows = visits.visits(for: content.place.id)
        let personal = ownerRows.first.map {
            PersonalVisitSummary(visitCount: ownerRows.count, latestScore: $0.overallRating, latestVisitedAt: $0.visitedAt)
        } ?? content.personal
        let friendsScore = content.friends?.averageScore
        let friendsCount = content.friends?.friendsVisitedCount ?? 0
        let friendsCaption: String? = friendsCount == 0
            ? String(localized: "score.friends.none")
            : String(localized: "place.friends.visited_count \(String(friendsCount))")
        let communityCaption = content.communityRatingCount == 0
            ? nil
            : String(localized: "place.rating_count \(String(content.communityRatingCount))")

        let communityA11y: String = {
            if let score = content.communityScore {
                return String(localized: "a11y.community_score \(ScoreFormatting.display(score))")
            }
            return String(localized: "a11y.community_not_rated")
        }()
        let friendsA11y: String = {
            if let score = friendsScore {
                return String(localized: "a11y.friends_score \(ScoreFormatting.display(score))")
            }
            return String(localized: "score.friends.none")
        }()

        return VStack(alignment: .leading, spacing: PhokartaSpacing.sm) {
            FeatureSectionHeader(title: String(localized: "place.scores"))
            HStack(alignment: .top, spacing: PhokartaSpacing.sm) {
                ScoreSummaryCard(
                    title: String(localized: "score.community"),
                    score: content.communityScore,
                    caption: communityCaption,
                    accessibilityText: communityA11y
                )
                ScoreSummaryCard(
                    title: String(localized: "score.friends"),
                    score: friendsScore,
                    caption: friendsCaption,
                    accessibilityText: friendsA11y
                )
                ScoreSummaryCard(
                    title: String(localized: "score.you"),
                    score: personal?.latestScore,
                    caption: personal.map { String(localized: "place.your_visits \(String($0.visitCount))") },
                    accessibilityText: personalA11y(personal)
                )
            }
        }
    }

    private func personalA11y(_ personal: PersonalVisitSummary?) -> String {
        if let personal {
            return String(localized: "a11y.personal_score \(ScoreFormatting.display(personal.latestScore))")
        }
        return String(localized: "a11y.personal_not_rated")
    }

    private func dimensions(_ values: [DimensionAggregate]) -> some View {
        VStack(alignment: .leading, spacing: PhokartaSpacing.sm) {
            ForEach(values) { dimension in
                HStack(alignment: .firstTextBaseline) {
                    Text(dimension.localizedName)
                        .font(.body)
                        .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                    Spacer()
                    ProgressView(value: min(max(dimension.average / 10, 0), 1))
                        .frame(width: 100)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel("\(dimension.localizedName), \(String(localized: "experience.past_ratings"))")
            }
        }
    }

    @ViewBuilder
    private func experienceInsights(_ aggregate: PlaceAggregateV2) -> some View {
        let feelings = aggregate.feelings.filter { $0.contributionCount > 0 }
        let total = max(feelings.reduce(Int64(0)) { $0 + $1.contributionCount }, 1)
        if !feelings.isEmpty {
            insightCard(title: phokartaString("place.experiences.feeling", locale: locale)) {
                ForEach(feelings, id: \.code.rawValue) { feeling in
                    HStack {
                        Text(ExperienceLocalizedLabels.feeling(feeling.code, locale: locale))
                        Spacer()
                        Text("\(feeling.contributionCount * 100 / total)%").fontWeight(.semibold)
                    }
                }
            }
        }
        let dimensions = aggregate.dimensions.filter { $0.contributionCount > 0 }
        if !dimensions.isEmpty {
            insightCard(title: phokartaString("experience.dimensions", locale: locale)) {
                ForEach(dimensions, id: \.key) { dimension in
                    VStack(alignment: .leading, spacing: 5) {
                        HStack {
                            Text(ExperienceLocalizedLabels.dimensionKey(dimension.key, locale: locale)).foregroundStyle(.secondary)
                            Spacer()
                            if let state = dimension.semanticDistribution.max(by: { $0.contributionCount < $1.contributionCount })?.state {
                                Text(ExperienceLocalizedLabels.dimensionState(state, locale: locale)).fontWeight(.semibold)
                            } else {
                                Text("experience.past_ratings").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        if dimension.semanticDistribution.isEmpty, let average = dimension.numericAverage {
                            ProgressView(value: min(max(average / 10, 0), 1))
                        }
                    }
                }
            }
        }
    }

    private func insightCard<Content: View>(title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.headline)
            content()
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(PhokartaColor.softSurface(for: colorScheme), in: RoundedRectangle(cornerRadius: PhokartaRadius.lg))
        .overlay(RoundedRectangle(cornerRadius: PhokartaRadius.lg).stroke(PhokartaColor.border(for: colorScheme), lineWidth: 1))
    }

    private func reviews(_ content: PlaceDetailContent) -> some View {
        VStack(alignment: .leading, spacing: PhokartaSpacing.md) {
            FeatureSectionHeader(title: String(localized: "place.reviews"))
            Picker("place.reviews", selection: Binding(
                get: { controller.reviewScope },
                set: { controller.selectReviewScope($0) }
            )) {
                Text("place.reviews.community").tag(PlaceDetailReviewScope.community)
                Text("place.reviews.friends").tag(PlaceDetailReviewScope.friends)
            }
            .pickerStyle(.segmented)
            .accessibilityLabel(String(localized: "place.reviews"))

            let rows = controller.reviewScope == .friends ? content.friendReviews : content.communityReviews
            if rows.isEmpty {
                Text(controller.reviewScope == .friends
                     ? String(localized: "place.reviews.empty.friends")
                     : String(localized: "place.reviews.empty.community"))
                    .font(.body)
                    .foregroundStyle(PhokartaColor.muted(for: colorScheme))
            } else {
                ForEach(rows) { review in
                    ReviewRowView(review: review, onSelectAuthor: onSelectUser)
                }
            }
        }
    }

    private func ownerVisits(_ placeID: UUID) -> some View {
        let rows = visits.visits(for: placeID)
        return Group {
            if !rows.isEmpty || !pendingVisits.isEmpty {
                VStack(alignment: .leading, spacing: PhokartaSpacing.md) {
                    FeatureSectionHeader(title: String(localized: "visit.your_visits"))

                    ForEach(pendingVisits) { pending in
                        Button {
                            selectedPending = pending
                        } label: {
                            VStack(alignment: .leading, spacing: PhokartaSpacing.xs) {
                                HStack {
                                    Text(ScoreFormatting.display(pending.overallRating)).font(.title3.bold())
                                    Text(PlaceDateFormatting.mediumDate(from: pending.visitedAt)).foregroundStyle(.secondary)
                                    Spacer()
                                    HStack(spacing: 4) {
                                        Image(systemName: pending.failed ? "exclamationmark.triangle.fill" : "arrow.triangle.2.circlepath")
                                            .foregroundStyle(pending.failed ? .red : PhokartaColor.sage)
                                        Text(String(localized: String.LocalizationValue(pending.failed ? "sync.status.failed" : "sync.status.pending")))
                                            .font(.caption.weight(.medium))
                                            .foregroundStyle(pending.failed ? .red : .secondary)
                                    }
                                }
                                if !pending.review.isEmpty {
                                    Text(pending.review).fixedSize(horizontal: false, vertical: true)
                                }
                                if !pending.personalNote.isEmpty {
                                    Label {
                                        Text(pending.personalNote).fixedSize(horizontal: false, vertical: true)
                                    } icon: {
                                        Image(systemName: "lock.fill")
                                    }
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                                }
                            }
                            .padding(PhokartaSpacing.md)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(
                                PhokartaColor.surface(for: colorScheme),
                                in: RoundedRectangle(cornerRadius: PhokartaRadius.md)
                            )
                            .overlay(
                                RoundedRectangle(cornerRadius: PhokartaRadius.md)
                                    .stroke(pending.failed ? Color.red.opacity(0.3) : Color.clear, lineWidth: 1)
                            )
                        }
                        .buttonStyle(.plain)
                    }

                    ForEach(rows) { visit in
                        VStack(alignment: .leading, spacing: PhokartaSpacing.xs) {
                            HStack {
                                Text(ScoreFormatting.display(visit.overallRating)).font(.title3.bold())
                                Text(PlaceDateFormatting.mediumDate(from: visit.visitedAt)).foregroundStyle(.secondary)
                                Spacer()
                                Text(String(localized: String.LocalizationValue(visit.visibility.localizationKey)))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            if !visit.publicReview.isEmpty {
                                Text(visit.publicReview).fixedSize(horizontal: false, vertical: true)
                            }
                            if !visit.privateMemory.isEmpty {
                                Label {
                                    Text(visit.privateMemory).fixedSize(horizontal: false, vertical: true)
                                } icon: {
                                    Image(systemName: "lock.fill")
                                }
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .accessibilityLabel("\(String(localized: "visit.memory.only_you")): \(visit.privateMemory)")
                            }
                            if !visit.media.isEmpty {
                                VisitMediaGallery(media: visit.media)
                            }
                        }
                        .padding(PhokartaSpacing.md)
                        .background(PhokartaColor.surface(for: colorScheme), in: RoundedRectangle(cornerRadius: PhokartaRadius.md))
                    }
                }
            }
        }
    }

    private func reloadPendingVisits() async {
        guard let mutationRepository, let userId = visits.accountID else { return }
        if let place = controller.content?.place {
            pendingVisits = (try? await mutationRepository.getPendingVisits(placeId: place.id, userId: userId)) ?? []
        }
    }

    private func photos(_ place: PlaceDetail) -> some View {
        let urls = ([place.coverImage] + place.photos).filter { !$0.isEmpty }
        return VStack(alignment: .leading, spacing: PhokartaSpacing.sm) {
            FeatureSectionHeader(title: String(localized: "place.photos"))
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: PhokartaSpacing.sm) {
                    ForEach(Array(urls.enumerated()), id: \.offset) { _, path in
                        PlaceImageView(path: path)
                            .frame(width: 150, height: 110)
                            .clipShape(RoundedRectangle(cornerRadius: PhokartaRadius.md, style: .continuous))
                    }
                }
            }
        }
    }
}

struct CollectionPickerSheet: View {
    let store: CollectionStore
    let place: PlaceDetail
    @Environment(\.dismiss) private var dismiss
    @State private var error: AppError?
    @State private var showingCreate = false
    @State private var isCreating = false

    var body: some View {
        NavigationStack {
            List {
                if store.summaries.isEmpty {
                    FeatureEmptyState(title: String(localized: "collections.empty"))
                } else {
                    ForEach(store.summaries) { collection in
                        let added = store.contains(placeID: place.id, in: collection.id)
                        let busy = store.isBusy(placeID: place.id, collectionID: collection.id)
                        Button {
                            guard !busy else { return }
                            Task { await toggle(collectionID: collection.id, removing: added) }
                        } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(collection.title)
                                        .fixedSize(horizontal: false, vertical: true)
                                    Text(String(localized: String.LocalizationValue(collection.visibility.localizationKey)))
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                if busy {
                                    ProgressView()
                                } else if added {
                                    Label("collections.added", systemImage: "checkmark")
                                        .labelStyle(.iconOnly)
                                }
                            }
                        }
                        .disabled(busy)
                        .accessibilityLabel("\(collection.title), \(String(localized: String.LocalizationValue(added ? "collections.added" : "collections.not_added")))")
                    }
                }
                if let error {
                    Text(error.localizedMutationMessage(fallbackKey: "collections.unable_add"))
                        .foregroundStyle(.red)
                }
            }
            .navigationTitle(String(localized: "collections.add"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("action.done") { dismiss() }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button { showingCreate = true } label: {
                        Label("collections.new", systemImage: "plus")
                    }
                }
            }
            .task {
                do {
                    try await store.refreshList()
                    for collection in store.summaries where store.details[collection.id] == nil {
                        try? await store.refreshDetail(id: collection.id)
                    }
                }
                catch let value as AppError { error = value }
                catch is CancellationError { return }
                catch { self.error = .server }
            }
            .sheet(isPresented: $showingCreate) {
                CreateCollectionSheet(isSubmitting: isCreating, error: error, coverImage: place.coverImage) { request in
                    await createAndAdd(request)
                }
            }
        }
    }

    private func toggle(collectionID: UUID, removing: Bool) async {
        error = nil
        do {
            if removing {
                try await store.remove(placeID: place.id, from: collectionID)
            } else {
                try await store.add(placeID: place.id, to: collectionID)
            }
        } catch let value as AppError {
            error = value
        } catch is CancellationError {
            return
        } catch {
            self.error = .server
        }
    }

    private func createAndAdd(_ request: CreateCollectionRequestDTO) async {
        guard !isCreating else { return }
        isCreating = true
        error = nil
        defer { isCreating = false }
        do {
            let created = try await store.create(request)
            try await store.add(placeID: place.id, to: created.id)
            showingCreate = false
        } catch let value as AppError {
            error = value
        } catch is CancellationError {
            return
        } catch {
            self.error = .server
        }
    }
}

#Preview("Place detail scores") {
    ScoreSummaryCard(
        title: "Community",
        score: 8.7,
        caption: "12 ratings",
        accessibilityText: "Community score 8.7 out of 10"
    )
    .padding()
}
