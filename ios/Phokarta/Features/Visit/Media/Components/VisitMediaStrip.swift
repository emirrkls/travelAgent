import PhotosUI
import SwiftUI

/// Horizontal scroll strip displaying selected Visit media items and the add photos action.
struct VisitMediaStrip: View {
    @Bindable var coordinator: VisitMediaUploadCoordinator
    let disabled: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("visit.media.section_title")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.secondary)
                Spacer()
                Text(photoCountText)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 12) {
                    ForEach(coordinator.items) { item in
                        VisitMediaThumbnail(
                            item: item,
                            onRemove: {
                                coordinator.removeItem(id: item.id)
                            },
                            onRetry: {
                                coordinator.retryFailed(id: item.id)
                            }
                        )
                        .disabled(disabled)
                    }

                    if coordinator.remainingSlots > 0 {
                        addPhotosButton
                            .disabled(disabled)
                    }
                }
                .padding(.vertical, 4)
                .padding(.horizontal, 2)
            }
        }
    }

    private var addPhotosButton: some View {
        VisitMediaPicker(remainingSlots: coordinator.remainingSlots) { pickedItems in
            coordinator.addFromPicker(pickedItems)
        }
        .frame(width: 80, height: 80)
        .background(
            RoundedRectangle(cornerRadius: 8)
                .strokeBorder(style: StrokeStyle(lineWidth: 1.5, dash: [4]))
                .foregroundStyle(disabled ? .tertiary : Color.accentColor)
        )
    }

    private var photoCountText: String {
        String.localizedStringWithFormat(
            String(localized: "visit.media.photos_count"),
            coordinator.items.count,
            MediaContract.maxPerVisit
        )
    }
}
