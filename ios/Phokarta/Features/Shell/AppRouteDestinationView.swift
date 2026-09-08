import SwiftUI

struct AppRouteDestinationView: View {
    let route: AppRoute
    let environment: AppEnvironment
    let currentUserId: UUID
    let onNavigate: (AppRoute) -> Void
    let onLogout: (() -> Void)?

    init(
        route: AppRoute,
        environment: AppEnvironment,
        currentUserId: UUID,
        onNavigate: @escaping (AppRoute) -> Void,
        onLogout: (() -> Void)? = nil
    ) {
        self.route = route
        self.environment = environment
        self.currentUserId = currentUserId
        self.onNavigate = onNavigate
        self.onLogout = onLogout
    }

    var body: some View {
        switch route {
        case .placeDetail(let id):
            PlaceDetailScreen(
                placeId: id,
                places: environment.places,
                saved: environment.saved,
                collections: environment.collections,
                visits: environment.visits,
                draftRepository: environment.draftRepository,
                mutationRepository: environment.mutationRepository,
                mediaStore: environment.mediaStore,
                syncEngine: environment.syncEngine,
                onSelectUser: { onNavigate(.userProfile($0)) }
            )
        case .userProfile(let id):
            UserProfileScreen(
                userId: id,
                isOwnProfile: id == currentUserId,
                service: environment.social,
                store: environment.socialState,
                onSelectPlace: { onNavigate(.placeDetail($0)) },
                onSelectUser: { onNavigate(.userProfile($0)) },
                onFollowers: { onNavigate(.socialList(.followers)) },
                onFollowing: { onNavigate(.socialList(.following)) },
                onFriends: { onNavigate(.socialList(.friends)) },
                onUserSearch: { onNavigate(.userSearch) },
                onLogout: id == currentUserId ? onLogout : nil
            )
        case .socialList(let kind):
            SocialListScreen(
                kind: kind,
                service: environment.social,
                store: environment.socialState,
                onSelectUser: { onNavigate(.userProfile($0)) }
            )
        case .userSearch:
            UserSearchScreen(
                service: environment.social,
                store: environment.socialState,
                onSelectUser: { onNavigate(.userProfile($0)) }
            )
        }
    }
}
