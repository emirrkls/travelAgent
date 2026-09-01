import SwiftUI

struct ExploreScreen: View {
    @State private var controller: ExploreController
    @State private var path: [AppRoute] = []
    @Environment(\.colorScheme) private var colorScheme
    private let places: any PlaceServing
    private let saved: SavedPlaceStore
    private let collections: CollectionStore
    private let visits: VisitStore

    init(places: any PlaceServing, saved: SavedPlaceStore, collections: CollectionStore, visits: VisitStore) {
        self.places = places
        self.saved = saved
        self.collections = collections
        self.visits = visits
        _controller = State(initialValue: ExploreController(places: places))
    }

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                switch controller.phase {
                case .idle, .loading:
                    loadingView
                case .content:
                    resultsList
                case .empty(let reason):
                    emptyView(reason)
                case .error(let kind):
                    errorView(kind)
                }
            }
            .background(PhokartaColor.background(for: colorScheme))
            .navigationTitle(String(localized: "explore.title"))
            .navigationBarTitleDisplayMode(.large)
            .searchable(
                text: Binding(
                    get: { controller.query },
                    set: { controller.setQuery($0) }
                ),
                prompt: Text("explore.search")
            )
            .safeAreaInset(edge: .top, spacing: 0) {
                categoryBar
            }
            .refreshable {
                await controller.refresh()
            }
            .navigationDestination(for: AppRoute.self) { route in
                switch route {
                case .placeDetail(let id):
                    PlaceDetailScreen(placeId: id, places: places, saved: saved, collections: collections, visits: visits)
                }
            }
        }
        .tint(PhokartaColor.accent(for: colorScheme))
        .task {
            controller.startIfNeeded()
        }
        .onDisappear {
            controller.cancel()
        }
    }

    private var categoryBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: PhokartaSpacing.sm) {
                CategoryChipView(
                    title: String(localized: "filter.all"),
                    isSelected: controller.selectedCategory == nil
                ) {
                    if controller.selectedCategory != nil {
                        controller.selectCategory(nil)
                    }
                }
                ForEach(PlaceCategory.filterCases, id: \.self) { category in
                    CategoryChipView(
                        title: category.localizedName,
                        isSelected: controller.selectedCategory == category
                    ) {
                        controller.selectCategory(category)
                    }
                }
            }
            .padding(.horizontal, PhokartaSpacing.md)
            .padding(.vertical, PhokartaSpacing.sm)
        }
        .background(PhokartaColor.background(for: colorScheme))
    }

    private var resultsList: some View {
        List {
            if let refreshError = controller.refreshError {
                Section {
                    FeatureEmptyState(
                        title: refreshError.localizedMessage,
                        retryTitle: refreshError.showsRetry ? String(localized: "explore.retry") : nil,
                        retry: refreshError.showsRetry ? { controller.retry() } : nil
                    )
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                }
            }
            ForEach(controller.places) { item in
                Button {
                    path.append(.placeDetail(item.id))
                } label: {
                    ExplorePlaceCard(item: liveItem(item))
                }
                .buttonStyle(.plain)
                .listRowBackground(PhokartaColor.surface(for: colorScheme))
                .onAppear {
                    controller.loadNextPageIfNeeded(current: item)
                }
            }
            if controller.isLoadingMore {
                HStack {
                    Spacer()
                    ProgressView()
                        .accessibilityLabel(String(localized: "explore.loading"))
                    Spacer()
                }
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }

    private func liveItem(_ item: ExplorePlaceItem) -> ExplorePlaceItem {
        var value = item
        value.isSaved = saved.isSaved(item.id)
        if let visit = visits.latest(for: item.id) {
            value.isVisited = true
            value.personalScore = visit.overallRating
        }
        return value
    }

    private var loadingView: some View {
        VStack(spacing: PhokartaSpacing.md) {
            ProgressView()
                .accessibilityLabel(String(localized: "explore.loading"))
            Text("explore.loading")
                .font(.body)
                .foregroundStyle(PhokartaColor.muted(for: colorScheme))
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func emptyView(_ reason: ExploreEmptyReason) -> some View {
        let title: String
        let message: String?
        switch reason {
        case .catalog:
            title = String(localized: "explore.empty.catalog")
            message = nil
        case .search:
            title = String(localized: "explore.empty.search")
            message = String(localized: "explore.empty.search.hint")
        case .category:
            title = String(localized: "explore.empty.category")
            message = nil
        }
        return FeatureEmptyState(title: title, message: message)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func errorView(_ kind: ExploreErrorKind) -> some View {
        FeatureEmptyState(
            title: kind.localizedMessage,
            retryTitle: kind.showsRetry ? String(localized: "explore.retry") : nil,
            retry: kind.showsRetry ? { controller.retry() } : nil
        )
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .combine)
    }
}

#Preview("Explore content") {
    let summary = PlaceSummary(
        id: UUID(uuidString: "20000000-0000-0000-0000-000000000001")!,
        name: "Sarnıç Cove",
        category: .beach,
        coverImage: "",
        city: "Bodrum",
        region: "Muğla",
        country: "Türkiye",
        latitude: 37.0,
        longitude: 27.4,
        priceLevel: 2,
        communityScore: 8.7,
        ratingCount: 12
    )
    ExplorePlaceCard(item: ExplorePlaceItem(summary: summary, isSaved: true, isVisited: true, personalScore: 9.0))
        .padding()
}
