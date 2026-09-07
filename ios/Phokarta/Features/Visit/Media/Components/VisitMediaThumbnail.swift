import SwiftUI

/// Per-item thumbnail view for the Visit media strip.
///
/// Shows the thumbnail image with overlay for:
/// - Preparing: spinner
/// - Uploading: progress ring
/// - Failed: error badge + retry button
/// - Confirmed: checkmark badge
/// - Remove button (top-right X)
struct VisitMediaThumbnail: View {
    let item: VisitMediaItem
    let onRemove: () -> Void
    let onRetry: () -> Void

    private let size: CGFloat = 80

    var body: some View {
        ZStack(alignment: .topTrailing) {
            thumbnailImage
                .frame(width: size, height: size)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay(phaseOverlay)

            removeButton
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityDescription)
    }

    // MARK: - Subviews

    @ViewBuilder
    private var thumbnailImage: some View {
        if let data = item.thumbnailData, let uiImage = UIImage(data: data) {
            Image(uiImage: uiImage)
                .resizable()
                .aspectRatio(contentMode: .fill)
        } else {
            Rectangle()
                .fill(.quaternary)
                .overlay {
                    Image(systemName: "photo")
                        .foregroundStyle(.secondary)
                }
        }
    }

    @ViewBuilder
    private var phaseOverlay: some View {
        switch item.phase {
        case .selected, .preparing:
            overlayBackground {
                ProgressView()
                    .tint(.white)
                    .accessibilityLabel(String(localized: "visit.media.preparing"))
            }
        case .requestingIntent:
            overlayBackground {
                ProgressView()
                    .tint(.white)
                    .accessibilityLabel(String(localized: "visit.media.uploading"))
            }
        case .uploading(let progress):
            overlayBackground {
                CircularProgressView(progress: progress)
                    .frame(width: 32, height: 32)
                    .accessibilityLabel(String(localized: "visit.media.uploading"))
            }
        case .uploaded, .confirming:
            overlayBackground {
                ProgressView()
                    .tint(.white)
                    .accessibilityLabel(String(localized: "visit.media.uploading"))
            }
        case .confirmed:
            EmptyView()
        case .failedRetryable(let message):
            overlayBackground {
                VStack(spacing: 4) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.yellow)
                    Button {
                        onRetry()
                    } label: {
                        Text("visit.media.retry")
                            .font(.caption2)
                            .foregroundStyle(.white)
                    }
                    .accessibilityLabel(String(localized: "visit.media.retry"))
                }
            }
            .accessibilityHint(message)
        case .failedPermanent(let message):
            overlayBackground {
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(.red)
            }
            .accessibilityHint(message)
        case .readyForIntent:
            EmptyView()
        }
    }

    private var removeButton: some View {
        Button {
            onRemove()
        } label: {
            Image(systemName: "xmark.circle.fill")
                .font(.system(size: 18))
                .symbolRenderingMode(.palette)
                .foregroundStyle(.white, .black.opacity(0.6))
        }
        .offset(x: 4, y: -4)
        .accessibilityLabel(String(localized: "visit.media.remove"))
    }

    private func overlayBackground<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        RoundedRectangle(cornerRadius: 8)
            .fill(.black.opacity(0.5))
            .overlay { content() }
    }

    private var accessibilityDescription: String {
        let position = String(localized: "visit.media.photo")
        switch item.phase {
        case .confirmed:
            return position
        case .preparing, .selected:
            return "\(position), \(String(localized: "visit.media.preparing"))"
        case .requestingIntent, .uploading, .uploaded, .confirming:
            return "\(position), \(String(localized: "visit.media.uploading"))"
        case .failedRetryable(let msg):
            return "\(position), \(msg)"
        case .failedPermanent(let msg):
            return "\(position), \(msg)"
        case .readyForIntent:
            return "\(position), \(String(localized: "visit.media.waiting"))"
        }
    }
}

// MARK: - Circular Progress

private struct CircularProgressView: View {
    let progress: Double

    var body: some View {
        ZStack {
            Circle()
                .stroke(lineWidth: 3)
                .opacity(0.3)
                .foregroundStyle(.white)
            Circle()
                .trim(from: 0, to: min(max(progress, 0), 1))
                .stroke(style: StrokeStyle(lineWidth: 3, lineCap: .round))
                .foregroundStyle(.white)
                .rotationEffect(.degrees(-90))
        }
    }
}
