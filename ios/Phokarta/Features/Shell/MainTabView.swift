import SwiftUI

struct MainTabView: View {
    let environment: AppEnvironment
    let user: CurrentUser
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        TabView {
            ExploreScreen(places: environment.places)
                .tabItem {
                    Label {
                        Text("tab.explore")
                    } icon: {
                        Image(systemName: "safari")
                    }
                }

            ProfilePlaceholderView(user: user) {
                Task { await environment.session.logout() }
            }
            .tabItem {
                Label {
                    Text("tab.profile")
                } icon: {
                    Image(systemName: "person.crop.circle")
                }
            }
        }
        .tint(PhokartaColor.accent(for: colorScheme))
    }
}

struct ProfilePlaceholderView: View {
    let user: CurrentUser
    let onLogout: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: PhokartaSpacing.md) {
                Text(user.displayName)
                    .font(.title.bold())
                    .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                Text(user.username)
                    .font(.body)
                    .foregroundStyle(PhokartaColor.muted(for: colorScheme))
                Text("profile.placeholder")
                    .font(.body)
                    .foregroundStyle(PhokartaColor.muted(for: colorScheme))
                    .fixedSize(horizontal: false, vertical: true)
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
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .background(PhokartaColor.background(for: colorScheme))
            .navigationTitle(String(localized: "profile.title"))
        }
    }
}
