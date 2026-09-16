import Foundation

enum AppRoute: Hashable, Sendable {
    case placeDetail(UUID)
    case experienceDetail(UUID)
    case userProfile(UUID)
    case socialList(SocialListKind)
    case userSearch
    case settings
}
