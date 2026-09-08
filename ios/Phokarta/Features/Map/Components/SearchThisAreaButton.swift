import SwiftUI

struct SearchThisAreaButton: View {
    let action: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: "arrow.clockwise")
                    .font(.subheadline.weight(.semibold))
                Text("map.search_this_area")
                    .font(.subheadline.weight(.semibold))
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(
                Capsule()
                    .fill(colorScheme == .dark ? Color.white : Color(uiColor: .darkGray))
                    .shadow(color: Color.black.opacity(0.25), radius: 8, y: 3)
            )
            .foregroundStyle(colorScheme == .dark ? Color.black : Color.white)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(String(localized: "a11y.search_this_map_area"))
    }
}
