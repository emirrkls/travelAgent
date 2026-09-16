import SwiftUI

struct AddExperienceTab: View {
    @State private var controller: ExploreController
    @State private var selectedPlace: PlaceDetail?
    @State private var isOpening = false
    let environment: AppEnvironment
    @Environment(\.colorScheme) private var colorScheme

    init(environment: AppEnvironment) {
        self.environment = environment
        _controller = State(initialValue: ExploreController(places: environment.places))
    }

    var body: some View {
        NavigationStack {
            Group {
                if controller.places.isEmpty && isOpening {
                    ProgressView()
                } else {
                    List(controller.places) { item in
                        Button {
                            open(item.id)
                        } label: {
                            ExplorePlaceCard(item: item)
                        }
                        .buttonStyle(.plain)
                        .onAppear { controller.loadNextPageIfNeeded(current: item) }
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                }
            }
            .background(PhokartaColor.background(for: colorScheme))
            .navigationTitle(String(localized: "experience.add.title"))
            .searchable(
                text: Binding(get: { controller.query }, set: { controller.setQuery($0) }),
                prompt: Text("experience.add.search_place")
            )
        }
        .task { controller.startIfNeeded() }
        .sheet(item: $selectedPlace) { place in
            VisitComposerScreen(
                place: place,
                store: environment.visits,
                draftRepository: environment.draftRepository,
                mutationRepository: environment.mutationRepository,
                mediaStore: environment.mediaStore,
                syncEngine: environment.syncEngine
            ) { _ in
                selectedPlace = nil
            }
        }
    }

    private func open(_ id: UUID) {
        guard !isOpening else { return }
        isOpening = true
        Task {
            defer { isOpening = false }
            selectedPlace = try? await environment.places.placeDetail(id: id)
        }
    }
}
