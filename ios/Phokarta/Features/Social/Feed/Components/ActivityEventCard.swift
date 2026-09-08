import SwiftUI

struct ActivityEventCard: View {
    let event: ActivityEvent
    let currentUserId: UUID?
    let isExpanded: Bool
    let onToggleExpand: () -> Void
    let onOpenPlace: (UUID) -> Void
    let onOpenAuthor: (UUID) -> Void
    var onReportVisit: ((UUID, String) -> Void)? = nil
    @Environment(\.colorScheme) private var colorScheme

    init(
        event: ActivityEvent,
        currentUserId: UUID?,
        isExpanded: Bool,
        onToggleExpand: @escaping () -> Void,
        onOpenPlace: @escaping (UUID) -> Void,
        onOpenAuthor: @escaping (UUID) -> Void,
        onReportVisit: ((UUID, String) -> Void)? = nil
    ) {
        self.event = event
        self.currentUserId = currentUserId
        self.isExpanded = isExpanded
        self.onToggleExpand = onToggleExpand
        self.onOpenPlace = onOpenPlace
        self.onOpenAuthor = onOpenAuthor
        self.onReportVisit = onReportVisit
    }

    private var isCurrentUser: Bool {
        currentUserId == event.author.id
    }

    private var authorDisplayName: String {
        isCurrentUser ? "\(event.author.displayName) · \(String(localized: "social.you"))" : event.author.displayName
    }

    private var placeMetadata: String {
        let categoryName = event.place.category.localizedName
        if event.place.city.isEmpty {
            return categoryName
        }
        return "\(categoryName) · \(event.place.city)"
    }

    private var canExpand: Bool {
        event.publicReview.count > 120
    }

    var body: some View {
        VStack(alignment: .leading, spacing: PhokartaSpacing.md) {
            // Author row
            HStack(spacing: PhokartaSpacing.sm) {
                Button {
                    onOpenAuthor(event.author.id)
                } label: {
                    HStack(spacing: PhokartaSpacing.sm) {
                        UserAvatarView(urlString: event.author.avatarUrl, name: event.author.displayName, size: 40)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(authorDisplayName)
                                .font(.subheadline.bold())
                                .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                            Text(PlaceDateFormatting.mediumDate(from: event.visitedAt))
                                .font(.caption)
                                .foregroundStyle(PhokartaColor.muted(for: colorScheme))
                        }
                    }
                }
                .buttonStyle(.plain)

                Spacer()

                if !isCurrentUser, let onReportVisit {
                    Menu {
                        Button {
                            onReportVisit(event.visitId, event.place.name)
                        } label: {
                            Label(String(localized: "report.action_visit"), systemImage: "flag")
                        }
                    } label: {
                        Image(systemName: "ellipsis")
                            .foregroundStyle(PhokartaColor.muted(for: colorScheme))
                            .padding(PhokartaSpacing.xs)
                    }
                    .accessibilityLabel(String(localized: "action.more_options"))
                }
            }

            // Place info row
            Button {
                onOpenPlace(event.place.id)
            } label: {
                HStack(spacing: PhokartaSpacing.md) {
                    PlaceImageView(path: event.place.coverImage)
                        .frame(width: 64, height: 64)
                        .clipShape(RoundedRectangle(cornerRadius: PhokartaRadius.md))

                    VStack(alignment: .leading, spacing: 2) {
                        Text(event.place.name)
                            .font(.headline)
                            .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                            .lineLimit(2)
                        Text(placeMetadata)
                            .font(.caption)
                            .foregroundStyle(PhokartaColor.muted(for: colorScheme))
                    }
                    Spacer()
                    ScoreBadgeView(value: event.overallScore)
                }
                .padding(PhokartaSpacing.sm)
                .background(
                    PhokartaColor.surface(for: colorScheme),
                    in: RoundedRectangle(cornerRadius: PhokartaRadius.md)
                )
            }
            .buttonStyle(.plain)

            // Public review
            if !event.publicReview.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text(event.publicReview)
                        .font(.body)
                        .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                        .lineLimit(isExpanded ? nil : 3)
                        .fixedSize(horizontal: false, vertical: true)

                    if canExpand {
                        Button(action: onToggleExpand) {
                            Text(isExpanded ? String(localized: "action.show_less") : String(localized: "action.read_more"))
                                .font(.caption.bold())
                                .foregroundStyle(PhokartaColor.accent(for: colorScheme))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .padding(PhokartaSpacing.md)
        .background(
            PhokartaColor.surface(for: colorScheme),
            in: RoundedRectangle(cornerRadius: PhokartaRadius.lg)
        )
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text(verbatim: "\(authorDisplayName), \(event.place.name), \(ScoreFormatting.display(event.overallScore))"))
    }
}
