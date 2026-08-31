import SwiftUI

struct PlaceDetailScreen: View {
    @State private var controller: PlaceDetailController
    @State private var showingCollections = false
    @Environment(\.colorScheme) private var colorScheme
    private let saved: SavedPlaceStore
    private let collections: CollectionStore

    init(
        placeId: UUID,
        places: any PlaceServing,
        saved: SavedPlaceStore,
        collections: CollectionStore
    ) {
        self.saved = saved
        self.collections = collections
        _controller = State(initialValue: PlaceDetailController(placeId: placeId, places: places))
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
        }
        .onDisappear {
            controller.cancel()
        }
        .sheet(isPresented: $showingCollections) {
            if let place = controller.content?.place {
                CollectionPickerSheet(store: collections, place: place)
            }
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
                    FriendsPreviewList(friends: friends.friends)
                }

                reviews(content)
                photos(content.place)
            }
            .padding(PhokartaSpacing.md)
        }
        .refreshable {
            await controller.refresh()
        }
    }

    private func locationLine(_ place: PlaceDetail) -> String {
        let price = String(repeating: "₺", count: max(place.priceLevel, 0))
        if price.isEmpty {
            return place.city
        }
        return "\(price) · \(place.city)"
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

            if content.personal != nil {
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
                    score: content.personal?.latestScore,
                    caption: content.personal.map { String(localized: "place.your_visits \(String($0.visitCount))") },
                    accessibilityText: personalA11y(content.personal)
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
                    Text(ScoreFormatting.display(dimension.average))
                        .font(.body.weight(.semibold))
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel("\(dimension.localizedName), \(ScoreFormatting.display(dimension.average))")
            }
        }
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
                    ReviewRowView(review: review)
                }
            }
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
