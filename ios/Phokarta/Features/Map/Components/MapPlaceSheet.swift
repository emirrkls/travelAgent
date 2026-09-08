import SwiftUI

struct MapPlaceSheet: View {
    let places: [PlaceSummary]
    let selectedPlaceId: UUID?
    let savedPlaceIds: Set<UUID>
    let visitedPlaceIds: Set<UUID>
    let friendMetrics: [UUID: FriendPlaceMetrics]
    let activeFilterCount: Int
    let isLoading: Bool
    let onSelectPlace: (PlaceSummary) -> Void
    let onOpenPlace: (UUID) -> Void
    let onToggleSave: (UUID) -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(spacing: 0) {
            dragHandle

            headerView

            if places.isEmpty {
                emptyStateView
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 10) {
                            ForEach(places) { place in
                                let isSelected = selectedPlaceId == place.id
                                let isSaved = savedPlaceIds.contains(place.id)
                                let isVisited = visitedPlaceIds.contains(place.id)
                                let signal = mapFriendSignal(metrics: friendMetrics[place.id])

                                MapPlaceRow(
                                    place: place,
                                    isSelected: isSelected,
                                    isSaved: isSaved,
                                    isVisited: isVisited,
                                    friendsVisitedCount: signal.friendsVisitedCount,
                                    friendAverageScore: signal.friendAverageScore,
                                    onTap: {
                                        if isSelected {
                                            onOpenPlace(place.id)
                                        } else {
                                            onSelectPlace(place)
                                        }
                                    },
                                    onToggleSave: {
                                        onToggleSave(place.id)
                                    }
                                )
                                .id(place.id)
                            }
                        }
                        .padding(.horizontal, PhokartaSpacing.md)
                        .padding(.bottom, 24)
                    }
                    .onChange(of: selectedPlaceId) { _, newId in
                        if let newId {
                            withAnimation {
                                proxy.scrollTo(newId, anchor: .center)
                            }
                        }
                    }
                }
            }
        }
        .background(PhokartaColor.surface(for: colorScheme))
    }

    private var dragHandle: some View {
        Capsule()
            .fill(Color.secondary.opacity(0.3))
            .frame(width: 36, height: 4)
            .padding(.top, 8)
            .padding(.bottom, 6)
    }

    private var headerView: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text("map.places_in_this_area")
                    .font(.headline)
                    .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                Text(activeFilterCount == 0 ? String(localized: "map.move_or_choose") : String(localized: "map.active_filters \(activeFilterCount)"))
                    .font(.caption)
                    .foregroundStyle(PhokartaColor.muted(for: colorScheme))
            }

            Spacer()

            Text("map.spots_count \(places.count)")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(PhokartaColor.accent(for: colorScheme))
        }
        .padding(.horizontal, PhokartaSpacing.md)
        .padding(.vertical, 8)
    }

    private var emptyStateView: some View {
        VStack(spacing: 8) {
            Spacer(minLength: 16)
            if isLoading {
                ProgressView()
                    .padding(.bottom, 8)
                Text("map.loading_places")
                    .font(.subheadline)
                    .foregroundStyle(PhokartaColor.muted(for: colorScheme))
            } else {
                Text("map.no_places_match")
                    .font(.headline)
                    .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                Text("map.pan_or_adjust")
                    .font(.subheadline)
                    .foregroundStyle(PhokartaColor.muted(for: colorScheme))
            }
            Spacer(minLength: 16)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct MapPlaceRow: View {
    let place: PlaceSummary
    let isSelected: Bool
    let isSaved: Bool
    let isVisited: Bool
    let friendsVisitedCount: Int64
    let friendAverageScore: Double?
    let onTap: () -> Void
    let onToggleSave: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                PlaceImageView(path: place.coverImage)
                    .frame(width: 76, height: 76)
                    .clipShape(RoundedRectangle(cornerRadius: PhokartaRadius.md, style: .continuous))

                VStack(alignment: .leading, spacing: 4) {
                    Text(place.name)
                        .font(.headline)
                        .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                        .lineLimit(1)

                    Text("\(place.category.localizedName) · \(place.city)")
                        .font(.subheadline)
                        .foregroundStyle(PhokartaColor.muted(for: colorScheme))
                        .lineLimit(1)

                    if isVisited {
                        Text("map.filter.visited")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(Color.green)
                    } else if isSaved {
                        Text("map.filter.want_to_go")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(PhokartaColor.accent(for: colorScheme))
                    } else if isSelected {
                        Text("map.tap_again_to_open")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(PhokartaColor.accent(for: colorScheme))
                    }

                    if friendsVisitedCount > 0 {
                        HStack(spacing: 6) {
                            if let score = friendAverageScore {
                                Text(ScoreFormatting.display(score))
                                    .font(.caption.weight(.bold))
                                    .foregroundStyle(Color.blue)
                            }
                            Text(friendsVisitedLabel)
                                .font(.caption)
                                .foregroundStyle(Color.blue)
                        }
                    }
                }

                Spacer(minLength: 4)

                VStack(alignment: .trailing, spacing: 8) {
                    if let score = place.communityScore {
                        Text(ScoreFormatting.display(score))
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(PhokartaColor.accent(for: colorScheme))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 3)
                            .background(
                                RoundedRectangle(cornerRadius: 6, style: .continuous)
                                    .fill(PhokartaColor.accent(for: colorScheme).opacity(0.12))
                            )
                    }

                    Button(action: onToggleSave) {
                        Image(systemName: isSaved ? "bookmark.fill" : "bookmark")
                            .font(.body)
                            .foregroundStyle(PhokartaColor.accent(for: colorScheme))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(String(localized: isSaved ? "saved.remove" : "saved.add"))
                }
            }
            .padding(10)
            .background(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(isSelected ? PhokartaColor.accent(for: colorScheme).opacity(0.12) : PhokartaColor.surface(for: colorScheme))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(isSelected ? PhokartaColor.accent(for: colorScheme) : Color.secondary.opacity(0.15), lineWidth: isSelected ? 1.5 : 1)
            )
        }
        .buttonStyle(.plain)
    }

    private var friendsVisitedLabel: String {
        "\(friendsVisitedCount) " + String(localized: "map.filter.friends_visited")
    }
}
