import SwiftUI

struct PlaceAnnotationView: View {
    let place: PlaceSummary
    let isSelected: Bool
    let isSaved: Bool
    let isVisited: Bool
    let friendsVisited: Bool
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        HStack(spacing: 5) {
            Image(systemName: place.category.systemImageName)
                .font(.system(size: isSelected ? 14 : 12, weight: .semibold))

            Text(place.communityScore.map(ScoreFormatting.display) ?? "—")
                .font(.system(size: isSelected ? 13 : 11, weight: .bold))

            if isSaved || isVisited || friendsVisited {
                HStack(spacing: 2) {
                    if isSaved {
                        Image(systemName: "bookmark.fill")
                            .font(.system(size: isSelected ? 9 : 8))
                            .foregroundStyle(isSelected ? Color.white : PhokartaColor.accent(for: colorScheme))
                    }
                    if isVisited {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.system(size: isSelected ? 9 : 8))
                            .foregroundStyle(isSelected ? Color.white : Color.green)
                    }
                    if friendsVisited {
                        Image(systemName: "person.2.fill")
                            .font(.system(size: isSelected ? 9 : 8))
                            .foregroundStyle(isSelected ? Color.white : Color.blue)
                    }
                }
            }
        }
        .padding(.horizontal, isSelected ? 10 : 7)
        .padding(.vertical, isSelected ? 7 : 5)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(isSelected ? PhokartaColor.accent(for: colorScheme) : PhokartaColor.surface(for: colorScheme))
                .shadow(color: Color.black.opacity(isSelected ? 0.25 : 0.12), radius: isSelected ? 6 : 3, y: 2)
        )
        .foregroundStyle(isSelected ? Color.white : PhokartaColor.ink(for: colorScheme))
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(isSelected ? Color.white : Color.clear, lineWidth: 1.5)
        )
        .scaleEffect(isSelected ? 1.08 : 1.0)
        .animation(.spring(response: 0.3, dampingFraction: 0.7), value: isSelected)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityLabel)
    }

    private var accessibilityLabel: String {
        var parts: [String] = [place.name, place.category.localizedName]
        if let score = place.communityScore {
            parts.append(String(localized: "a11y.community_score \(ScoreFormatting.display(score))"))
        } else {
            parts.append(String(localized: "a11y.community_not_rated"))
        }
        if isSaved { parts.append(String(localized: "place.saved")) }
        if isVisited { parts.append(String(localized: "place.visited")) }
        if friendsVisited { parts.append(String(localized: "map.filter.friends_visited")) }
        return parts.joined(separator: ", ")
    }
}

extension PlaceCategory {
    var systemImageName: String {
        switch self {
        case .beach: return "sun.max"
        case .restaurant: return "fork.knife"
        case .cafe: return "cup.and.saucer"
        case .hotel: return "bed.double"
        case .bar: return "wineglass"
        case .nightlife: return "moon.stars"
        case .attraction: return "sparkles"
        case .activity: return "figure.walk"
        case .nature: return "leaf"
        case .unknown: return "mappin"
        }
    }
}
