import SwiftUI

struct MapFilterBar: View {
    let filters: MapFilters
    let hasUserLocation: Bool
    let onSelectCategory: (PlaceCategory?) -> Void
    let onToggleHighlyRated: () -> Void
    let onToggleFriendsVisited: () -> Void
    let onToggleVisited: () -> Void
    let onToggleWantToGo: () -> Void
    let onClearFilters: () -> Void
    let onLocationTap: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("map.discovery")
                        .font(.title2.weight(.bold))
                        .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                    Text("map.places_through_people")
                        .font(.subheadline)
                        .foregroundStyle(PhokartaColor.muted(for: colorScheme))
                }

                Spacer(minLength: 8)

                if filters.activeCount > 0 {
                    Button(action: onClearFilters) {
                        Image(systemName: "line.3.horizontal.decrease.circle.fill")
                            .font(.title3)
                            .foregroundStyle(PhokartaColor.accent(for: colorScheme))
                    }
                    .accessibilityLabel(String(localized: "a11y.clear_map_filters"))
                }

                Button(action: onLocationTap) {
                    Image(systemName: hasUserLocation ? "location.fill" : "location")
                        .font(.title3)
                        .foregroundStyle(PhokartaColor.accent(for: colorScheme))
                        .padding(6)
                        .background(
                            Circle()
                                .fill(PhokartaColor.surface(for: colorScheme))
                                .shadow(color: Color.black.opacity(0.1), radius: 3, y: 1)
                        )
                }
                .accessibilityLabel(String(localized: hasUserLocation ? "a11y.center_on_location" : "a11y.use_my_location"))
            }
            .padding(.horizontal, PhokartaSpacing.md)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    categoryMenu

                    FilterChipButton(
                        title: String(localized: "map.filter.rated_9_plus"),
                        isSelected: filters.highlyRatedOnly,
                        action: onToggleHighlyRated
                    )

                    FilterChipButton(
                        title: String(localized: "map.filter.friends_visited"),
                        systemImage: "person.2.fill",
                        isSelected: filters.friendsVisitedOnly,
                        action: onToggleFriendsVisited
                    )

                    FilterChipButton(
                        title: String(localized: "map.filter.visited"),
                        systemImage: "checkmark.circle.fill",
                        isSelected: filters.visitedOnly,
                        action: onToggleVisited
                    )

                    FilterChipButton(
                        title: String(localized: "map.filter.want_to_go"),
                        systemImage: "bookmark.fill",
                        isSelected: filters.wantToGoOnly,
                        action: onToggleWantToGo
                    )
                }
                .padding(.horizontal, PhokartaSpacing.md)
            }
        }
        .padding(.vertical, 10)
        .background(
            PhokartaColor.surface(for: colorScheme)
                .opacity(0.96)
                .shadow(color: Color.black.opacity(0.08), radius: 4, y: 2)
        )
    }

    private var categoryMenu: some View {
        Menu {
            Button {
                onSelectCategory(nil)
            } label: {
                if filters.category == nil {
                    Label("map.filter.all_categories", systemImage: "checkmark")
                } else {
                    Text("map.filter.all_categories")
                }
            }

            Divider()

            ForEach(PlaceCategory.filterCases, id: \.self) { cat in
                Button {
                    onSelectCategory(cat)
                } label: {
                    if filters.category == cat {
                        Label(cat.localizedName, systemImage: "checkmark")
                    } else {
                        Label(cat.localizedName, systemImage: cat.systemImageName)
                    }
                }
            }
        } label: {
            HStack(spacing: 5) {
                if let cat = filters.category {
                    Image(systemName: cat.systemImageName)
                        .font(.caption)
                    Text(cat.localizedName)
                } else {
                    Text("map.filter.all_categories")
                }
                Image(systemName: "chevron.down")
                    .font(.system(size: 10, weight: .bold))
            }
            .font(.subheadline.weight(.medium))
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(
                Capsule()
                    .fill(filters.category != nil ? PhokartaColor.accent(for: colorScheme).opacity(0.15) : PhokartaColor.background(for: colorScheme))
            )
            .overlay(
                Capsule()
                    .stroke(filters.category != nil ? PhokartaColor.accent(for: colorScheme) : Color.secondary.opacity(0.3), lineWidth: 1)
            )
            .foregroundStyle(filters.category != nil ? PhokartaColor.accent(for: colorScheme) : PhokartaColor.ink(for: colorScheme))
        }
    }
}

private struct FilterChipButton: View {
    let title: String
    var systemImage: String? = nil
    let isSelected: Bool
    let action: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        Button(action: action) {
            HStack(spacing: 5) {
                if let systemImage {
                    Image(systemName: systemImage)
                        .font(.caption)
                }
                Text(title)
            }
            .font(.subheadline.weight(.medium))
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(
                Capsule()
                    .fill(isSelected ? PhokartaColor.accent(for: colorScheme) : PhokartaColor.background(for: colorScheme))
            )
            .overlay(
                Capsule()
                    .stroke(isSelected ? PhokartaColor.accent(for: colorScheme) : Color.secondary.opacity(0.3), lineWidth: 1)
            )
            .foregroundStyle(isSelected ? Color.white : PhokartaColor.ink(for: colorScheme))
        }
        .buttonStyle(.plain)
    }
}
