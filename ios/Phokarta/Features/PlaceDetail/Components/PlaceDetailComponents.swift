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
    var onSelectAuthor: ((UUID) -> Void)? = nil
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(alignment: .leading, spacing: PhokartaSpacing.xs) {
            VStack(alignment: .leading, spacing: PhokartaSpacing.xs) {
                Button {
                    onSelectAuthor?(review.userId)
                } label: {
                    HStack(alignment: .firstTextBaseline) {
                        Text(review.displayName)
                            .font(.headline)
                            .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                        Spacer()
                        Text(ScoreFormatting.display(review.overallRating))
                            .font(.headline)
                            .foregroundStyle(PhokartaColor.sage)
                    }
                }
                .buttonStyle(.plain)
                .disabled(onSelectAuthor == nil)

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
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(accessibilityText)

            if !review.media.isEmpty {
                VisitMediaGallery(media: review.media)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, PhokartaSpacing.sm)
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

/// Displays the short-lived signed read URLs returned with a canonical Visit.
/// `AsyncImage` talks directly to object storage and therefore never receives
/// the backend API client's Bearer authorization header.
struct VisitMediaGallery: View {
    let media: [VisitMediaDTO]
    @State private var selectedMedia: VisitMediaDTO?

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: PhokartaSpacing.sm) {
                ForEach(Self.ordered(media)) { attachment in
                    Button {
                        selectedMedia = attachment
                    } label: {
                        PlaceImageView(path: attachment.accessUrl?.absoluteString)
                            .frame(width: 120, height: 90)
                            .clipShape(
                                RoundedRectangle(
                                    cornerRadius: PhokartaRadius.md,
                                    style: .continuous
                                )
                            )
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(String(localized: "visit.media.photo"))
                }
            }
        }
        .sheet(item: $selectedMedia) { attachment in
            VisitMediaPreview(attachment: attachment)
        }
    }

    static func ordered(_ media: [VisitMediaDTO]) -> [VisitMediaDTO] {
        media.sorted {
            if $0.sortOrder == $1.sortOrder {
                return $0.id.uuidString < $1.id.uuidString
            }
            return $0.sortOrder < $1.sortOrder
        }
    }
}

private struct VisitMediaPreview: View {
    let attachment: VisitMediaDTO
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            PlaceImageView(
                path: attachment.accessUrl?.absoluteString,
                contentMode: .fit
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.black)
            .navigationTitle(String(localized: "visit.media.photo"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("action.done") {
                        dismiss()
                    }
                }
            }
        }
    }
}

struct FriendsPreviewList: View {
    let friends: [FriendPreview]
    var onSelectFriend: ((UUID) -> Void)? = nil
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(alignment: .leading, spacing: PhokartaSpacing.sm) {
            FeatureSectionHeader(title: String(localized: "place.friends.visited"))
            ForEach(friends.prefix(5)) { friend in
                Button {
                    onSelectFriend?(friend.userId)
                } label: {
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
                }
                .buttonStyle(.plain)
                .disabled(onSelectFriend == nil)
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
