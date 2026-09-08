import SwiftUI
import MapKit

/// Interactive Apple MapKit discovery screen with filter synchronization,
/// "Search this area" intent tracking, and seamless Place Detail navigation.
struct MapScreen: View {
    @State private var controller: MapController
    @State private var cameraPosition: MapCameraPosition = .region(
        MKCoordinateRegion(
            center: CLLocationCoordinate2D(
                latitude: MapDefaults.centerLatitude,
                longitude: MapDefaults.centerLongitude
            ),
            span: MKCoordinateSpan(latitudeDelta: 0.7, longitudeDelta: 0.9)
        )
    )
    @State private var selectedAnnotationId: UUID? = nil
    @State private var isSheetExpanded: Bool = false
    @State private var path: [AppRoute] = []

    let places: any PlaceServing
    let saved: SavedPlaceStore
    let collections: CollectionStore
    let visits: VisitStore
    let draftRepository: (any VisitDraftRepository)?
    let mutationRepository: (any OfflineMutationRepository)?
    let mediaStore: (any DurableMediaStoring)?
    let syncEngine: MutationSyncEngine?
    let environment: AppEnvironment?
    let currentUserId: UUID?
    @Environment(\.colorScheme) private var colorScheme

    init(
        places: any PlaceServing,
        saved: SavedPlaceStore,
        collections: CollectionStore,
        visits: VisitStore,
        locationService: (any LocationProviding)? = nil,
        draftRepository: (any VisitDraftRepository)? = nil,
        mutationRepository: (any OfflineMutationRepository)? = nil,
        mediaStore: (any DurableMediaStoring)? = nil,
        syncEngine: MutationSyncEngine? = nil,
        environment: AppEnvironment? = nil,
        currentUserId: UUID? = nil
    ) {
        self.places = places
        self.saved = saved
        self.collections = collections
        self.visits = visits
        self.draftRepository = draftRepository
        self.mutationRepository = mutationRepository
        self.mediaStore = mediaStore
        self.syncEngine = syncEngine
        self.environment = environment
        self.currentUserId = currentUserId

        let locService = locationService ?? CoreLocationService()
        _controller = State(
            initialValue: MapController(
                places: places,
                saved: saved,
                visits: visits,
                locationService: locService,
                currentAccountId: currentUserId
            )
        )
    }

    var body: some View {
        NavigationStack(path: $path) {
            ZStack(alignment: .bottom) {
                mapView

                VStack(spacing: 0) {
                    filterBarView

                    if controller.showSearchThisArea {
                        SearchThisAreaButton {
                            controller.searchThisArea()
                        }
                        .padding(.top, 12)
                        .transition(.scale.combined(with: .opacity))
                    }

                    Spacer()
                }

                if controller.isLoading && !controller.showSearchThisArea {
                    VStack {
                        HStack {
                            Spacer()
                            ProgressView()
                                .padding(10)
                                .background(
                                    Circle()
                                        .fill(PhokartaColor.surface(for: colorScheme))
                                        .shadow(color: Color.black.opacity(0.15), radius: 4, y: 2)
                                )
                                .padding(.trailing, 16)
                                .padding(.top, 110)
                        }
                        Spacer()
                    }
                }

                bottomSheetView
            }
            .navigationBarHidden(true)
            .navigationDestination(for: AppRoute.self) { route in
                if let environment, let currentUserId {
                    AppRouteDestinationView(
                        route: route,
                        environment: environment,
                        currentUserId: currentUserId,
                        onNavigate: { path.append($0) }
                    )
                } else if case .placeDetail(let id) = route {
                    PlaceDetailScreen(
                        placeId: id,
                        places: places,
                        saved: saved,
                        collections: collections,
                        visits: visits,
                        draftRepository: draftRepository,
                        mutationRepository: mutationRepository,
                        mediaStore: mediaStore,
                        syncEngine: syncEngine
                    )
                }
            }
        }
        .task {
            controller.bootstrapDefaultAreaIfNeeded()
            if let currentUserId {
                controller.activate(accountId: currentUserId)
            }
        }
        .onChange(of: currentUserId) { _, newId in
            if let newId {
                controller.activate(accountId: newId)
            } else {
                controller.purgeAccountState()
            }
        }
        .onChange(of: controller.cameraRequest != nil) { _, hasRequest in
            if hasRequest, let req = controller.cameraRequest {
                withAnimation(.easeInOut(duration: 0.6)) {
                    cameraPosition = .region(
                        MKCoordinateRegion(
                            center: CLLocationCoordinate2D(latitude: req.latitude, longitude: req.longitude),
                            span: MKCoordinateSpan(latitudeDelta: 0.05, longitudeDelta: 0.05)
                        )
                    )
                }
                controller.consumeCameraRequest()
            }
        }
    }

    private var mapView: some View {
        Map(position: $cameraPosition, selection: $selectedAnnotationId) {
            ForEach(controller.visiblePlaces) { place in
                let isSelected = controller.selectedPlaceId == place.id
                let isSaved = controller.saved.isSaved(place.id)
                let isVisited = controller.visits.isVisited(place.id)
                let friendsVisited = (controller.friendMetrics[place.id]?.friendsVisitedCount ?? 0) > 0

                Annotation(
                    place.name,
                    coordinate: CLLocationCoordinate2D(latitude: place.latitude, longitude: place.longitude)
                ) {
                    PlaceAnnotationView(
                        place: place,
                        isSelected: isSelected,
                        isSaved: isSaved,
                        isVisited: isVisited,
                        friendsVisited: friendsVisited
                    )
                    .onTapGesture {
                        controller.selectPlace(place.id)
                    }
                }
                .tag(place.id)
            }

            if let userLoc = controller.userLocation {
                Annotation("User", coordinate: CLLocationCoordinate2D(latitude: userLoc.latitude, longitude: userLoc.longitude)) {
                    Circle()
                        .fill(Color.blue)
                        .frame(width: 14, height: 14)
                        .overlay(Circle().stroke(Color.white, lineWidth: 2))
                        .shadow(radius: 3)
                }
            }
        }
        .mapStyle(.standard)
        .mapControls {
            MapCompass()
            MapScaleView()
        }
        .onMapCameraChange(frequency: .onEnd) { context in
            let region = context.region
            let north = min(90.0, region.center.latitude + region.span.latitudeDelta / 2.0)
            let south = max(-90.0, region.center.latitude - region.span.latitudeDelta / 2.0)
            let east = min(180.0, region.center.longitude + region.span.longitudeDelta / 2.0)
            let west = max(-180.0, region.center.longitude - region.span.longitudeDelta / 2.0)
            let zoom = log2(360.0 / max(region.span.longitudeDelta, 0.0001))

            let viewport = MapViewport(
                north: north,
                east: east,
                south: south,
                west: west,
                centerLatitude: region.center.latitude,
                centerLongitude: region.center.longitude,
                zoom: zoom
            )
            controller.onCameraChange(viewport: viewport, isUserInteraction: true)
        }
        .onTapGesture {
            controller.clearSelection()
        }
    }

    private var filterBarView: some View {
        MapFilterBar(
            filters: controller.filters,
            hasUserLocation: controller.userLocation != nil,
            onSelectCategory: { controller.selectCategory($0) },
            onToggleHighlyRated: { controller.toggleHighlyRated() },
            onToggleFriendsVisited: { controller.toggleFriendsVisited() },
            onToggleVisited: { controller.toggleVisited() },
            onToggleWantToGo: { controller.toggleWantToGo() },
            onClearFilters: { controller.clearFilters() },
            onLocationTap: { controller.requestCurrentLocation(userInitiated: true) }
        )
    }

    private var bottomSheetView: some View {
        VStack(spacing: 0) {
            MapPlaceSheet(
                places: controller.visiblePlaces,
                selectedPlaceId: controller.selectedPlaceId,
                savedPlaceIds: controller.saved.savedPlaceIDs,
                visitedPlaceIds: controller.visits.visitedPlaceIDs,
                friendMetrics: controller.friendMetrics,
                activeFilterCount: controller.filters.activeCount,
                isLoading: controller.isLoading,
                onSelectPlace: { place in
                    controller.selectPlace(place.id)
                    withAnimation {
                        cameraPosition = .region(
                            MKCoordinateRegion(
                                center: CLLocationCoordinate2D(latitude: place.latitude, longitude: place.longitude),
                                span: MKCoordinateSpan(latitudeDelta: 0.1, longitudeDelta: 0.1)
                            )
                        )
                    }
                },
                onOpenPlace: { placeId in
                    path.append(.placeDetail(placeId))
                },
                onToggleSave: { placeId in
                    controller.toggleSaved(placeId: placeId)
                }
            )
        }
        .frame(height: isSheetExpanded ? 420 : 190)
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .shadow(color: Color.black.opacity(0.12), radius: 8, y: -2)
        .overlay(alignment: .top) {
            Color.clear
                .frame(height: 30)
                .contentShape(Rectangle())
                .onTapGesture {
                    withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                        isSheetExpanded.toggle()
                    }
                }
        }
        .animation(.spring(response: 0.35, dampingFraction: 0.8), value: isSheetExpanded)
    }
}
