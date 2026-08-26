import SwiftUI

struct ExplorePlaceCard: View {
    let item: ExplorePlaceItem
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        HStack(alignment: .top, spacing: PhokartaSpacing.md) {
            PlaceImageView(path: item.summary.coverImage)
                .frame(width: 96, height: 96)
                .clipShape(RoundedRectangle(cornerRadius: PhokartaRadius.md, style: .continuous))

            VStack(alignment: .leading, spacing: 6) {
                Text(item.summary.name)
                    .font(.headline)
                    .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                    .fixedSize(horizontal: false, vertical: true)

                Text("\(item.summary.category.localizedName) · \(item.summary.city)")
                    .font(.subheadline)
                    .foregroundStyle(PhokartaColor.muted(for: colorScheme))
                    .fixedSize(horizontal: false, vertical: true)

                HStack(alignment: .firstTextBaseline, spacing: PhokartaSpacing.sm) {
                    scorePair(
                        label: String(localized: "score.community"),
                        value: item.summary.communityScore
                    )
                    if item.friendsVisitedCount > 0 {
                        scorePair(
                            label: String(localized: "score.friends"),
                            value: item.friendAverageScore
                        )
                    }
                }

                HStack(spacing: PhokartaSpacing.sm) {
                    if item.isVisited {
                        readonlyChip(String(localized: "place.visited"))
                    }
                    if item.isSaved {
                        readonlyChip(String(localized: "place.saved"))
                    }
                    if let personal = item.personalScore {
                        scorePair(
                            label: String(localized: "score.you"),
                            value: personal
                        )
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, PhokartaSpacing.sm)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
    }

    private func scorePair(label: String, value: Double?) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption)
                .foregroundStyle(PhokartaColor.muted(for: colorScheme))
            Text(value.map(ScoreFormatting.display) ?? String(localized: "score.not_rated"))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(PhokartaColor.ink(for: colorScheme))
        }
    }

    private func readonlyChip(_ title: String) -> some View {
        Text(title)
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .foregroundStyle(PhokartaColor.sage)
            .background(PhokartaColor.mist, in: Capsule())
            .accessibilityHidden(true)
    }

    private var accessibilityText: String {
        var parts = [item.summary.name, item.summary.category.localizedName, item.summary.city]
        if let score = item.summary.communityScore {
            parts.append(String(localized: "a11y.community_score \(ScoreFormatting.display(score))"))
        } else {
            parts.append(String(localized: "a11y.community_not_rated"))
        }
        if item.friendsVisitedCount > 0 {
            if let friends = item.friendAverageScore {
                parts.append(String(localized: "a11y.friends_score \(ScoreFormatting.display(friends))"))
            } else {
                parts.append(String(localized: "score.friends.none"))
            }
        }
        if item.isVisited {
            parts.append(String(localized: "place.visited"))
        }
        if item.isSaved {
            parts.append(String(localized: "place.saved"))
        }
        if let personal = item.personalScore {
            parts.append(String(localized: "a11y.personal_score \(ScoreFormatting.display(personal))"))
        }
        return parts.joined(separator: ", ")
    }
}
