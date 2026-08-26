import SwiftUI

struct PlaceImageView: View {
    let path: String?
    var contentMode: ContentMode = .fill

    var body: some View {
        let url = PlaceImageURL.displayURL(from: path)
        ZStack {
            PhokartaColor.mist
            if let url {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .empty:
                        ProgressView()
                            .accessibilityLabel(String(localized: "explore.loading"))
                    case .success(let image):
                        image
                            .resizable()
                            .aspectRatio(contentMode: contentMode)
                    case .failure:
                        imageFallback
                    @unknown default:
                        imageFallback
                    }
                }
            } else {
                imageFallback
            }
        }
        .clipped()
        .accessibilityHidden(true)
    }

    private var imageFallback: some View {
        Image(systemName: "photo")
            .font(.title2)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .accessibilityHidden(true)
    }
}

struct CategoryChipView: View {
    let title: String
    let isSelected: Bool
    let action: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline.weight(isSelected ? .semibold : .regular))
                .padding(.horizontal, PhokartaSpacing.md)
                .padding(.vertical, PhokartaSpacing.sm)
                .foregroundStyle(isSelected ? Color.white : PhokartaColor.ink(for: colorScheme))
                .background(
                    isSelected
                        ? PhokartaColor.accent(for: colorScheme)
                        : PhokartaColor.surface(for: colorScheme),
                    in: Capsule()
                )
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityLabel(title)
    }
}

struct ScoreBadgeView: View {
    let value: Double?
    var compact: Bool = false

    var body: some View {
        Text(value.map(ScoreFormatting.display) ?? String(localized: "score.not_rated"))
            .font(compact ? .subheadline.weight(.semibold) : .headline)
            .foregroundStyle(value == nil ? .secondary : .primary)
            .padding(.horizontal, PhokartaSpacing.sm)
            .padding(.vertical, 4)
            .background(.thinMaterial, in: Capsule())
    }
}

struct FeatureEmptyState: View {
    let title: String
    var message: String?
    var retryTitle: String? = nil
    var retry: (() -> Void)? = nil
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(spacing: PhokartaSpacing.md) {
            Text(title)
                .font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)
                .foregroundStyle(PhokartaColor.ink(for: colorScheme))
            if let message {
                Text(message)
                    .font(.body)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(PhokartaColor.muted(for: colorScheme))
            }
            if let retry, let retryTitle {
                Button(retryTitle, action: retry)
                    .buttonStyle(.borderedProminent)
                    .tint(PhokartaColor.accent(for: colorScheme))
                    .accessibilityLabel(retryTitle)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(PhokartaSpacing.lg)
    }
}

struct FeatureSectionHeader: View {
    let title: String
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        Text(title)
            .font(.title3.weight(.semibold))
            .foregroundStyle(PhokartaColor.ink(for: colorScheme))
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}
