import SwiftUI

struct ScoreSummaryCard: View {
    let title: String
    let score: Double?
    let caption: String?
    let accessibilityText: String
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.caption)
                .foregroundStyle(PhokartaColor.muted(for: colorScheme))
            Text(score.map(ScoreFormatting.display) ?? String(localized: "score.not_rated"))
                .font(.title2.weight(.bold))
                .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                .fixedSize(horizontal: false, vertical: true)
            if let caption {
                Text(caption)
                    .font(.caption)
                    .foregroundStyle(PhokartaColor.muted(for: colorScheme))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(PhokartaSpacing.md)
        .background(PhokartaColor.surface(for: colorScheme), in: RoundedRectangle(cornerRadius: PhokartaRadius.md, style: .continuous))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
    }
}

struct ReviewRowView: View {
    let review: ReviewSummary
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(alignment: .leading, spacing: PhokartaSpacing.sm) {
            HStack(alignment: .firstTextBaseline) {
                Text(review.displayName)
                    .font(.headline)
                    .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                Spacer()
                Text(ScoreFormatting.display(review.overallRating))
                    .font(.headline)
                    .foregroundStyle(PhokartaColor.sage)
            }
            Text(PlaceDateFormatting.mediumDate(from: review.visitedAt))
                .font(.caption)
                .foregroundStyle(PhokartaColor.muted(for: colorScheme))
            if !review.publicReview.isEmpty {
                Text(review.publicReview)
                    .font(.body)
                    .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, PhokartaSpacing.sm)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
    }

    private var accessibilityText: String {
        var parts = [
            review.displayName,
            String(localized: "a11y.review_score \(ScoreFormatting.display(review.overallRating))"),
            PlaceDateFormatting.mediumDate(from: review.visitedAt),
        ]
        if !review.publicReview.isEmpty {
            parts.append(review.publicReview)
        }
        return parts.joined(separator: ", ")
    }
}

struct FriendsPreviewList: View {
    let friends: [FriendPreview]
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(alignment: .leading, spacing: PhokartaSpacing.sm) {
            FeatureSectionHeader(title: String(localized: "place.friends.visited"))
            ForEach(friends.prefix(5)) { friend in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(friend.displayName)
                            .font(.headline)
                            .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                        Text(ScoreFormatting.display(friend.latestScore))
                            .font(.subheadline)
                            .foregroundStyle(PhokartaColor.sage)
                    }
                    Spacer()
                    ScoreBadgeView(value: friend.latestScore, compact: true)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(
                    String(
                        localized: "a11y.friend_visited \(friend.displayName) \(ScoreFormatting.display(friend.latestScore))"
                    )
                )
            }
        }
    }
}
