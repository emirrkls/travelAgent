import SwiftUI

struct SignedInShellView: View {
    let user: CurrentUser
    let onLogout: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(spacing: PhokartaSpacing.lg) {
            Spacer()
            Text("app.name")
                .font(.largeTitle.bold())
                .foregroundStyle(PhokartaColor.ink(for: colorScheme))
            Text("auth.signed_in_as \(user.displayName)")
                .font(.title3)
                .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                .accessibilityLabel(String(localized: "auth.signed_in_as \(user.displayName)"))
            Text(user.username)
                .font(.body)
                .foregroundStyle(PhokartaColor.muted(for: colorScheme))
            Spacer()
            Button(action: onLogout) {
                Text("auth.logout")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, PhokartaSpacing.md)
            }
            .buttonStyle(.plain)
            .foregroundStyle(.white)
            .background(PhokartaColor.accent(for: colorScheme), in: RoundedRectangle(cornerRadius: PhokartaRadius.lg))
            .accessibilityLabel(String(localized: "auth.logout"))
        }
        .padding(PhokartaSpacing.lg)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(PhokartaColor.background(for: colorScheme))
    }
}
