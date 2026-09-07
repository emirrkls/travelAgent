import PhotosUI
import SwiftUI

/// Wrapper around `PhotosPicker` for the Visit media strip.
///
/// Uses the system photo picker (PhotosUI) which does NOT require
/// `NSPhotoLibraryUsageDescription` — the picker runs in a separate process.
struct VisitMediaPicker: View {
    let remainingSlots: Int
    let onPicked: ([PhotosPickerItem]) -> Void

    @State private var selectedItems: [PhotosPickerItem] = []

    var body: some View {
        PhotosPicker(
            selection: $selectedItems,
            maxSelectionCount: max(1, remainingSlots),
            matching: .images,
            photoLibrary: .shared()
        ) {
            Label {
                Text("visit.media.add_photos")
            } icon: {
                Image(systemName: "photo.badge.plus")
            }
        }
        .disabled(remainingSlots <= 0)
        .onChange(of: selectedItems) { _, newItems in
            guard !newItems.isEmpty else { return }
            onPicked(newItems)
            selectedItems = []
        }
        .accessibilityLabel(String(localized: "visit.media.add_photos"))
    }
}
