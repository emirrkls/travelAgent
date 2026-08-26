import SwiftUI

struct RootView: View {
    var environment: AppEnvironment

    private var session: AuthSessionController { environment.session }

    var body: some View {
        Group {
            switch session.state {
            case .restoring:
                LaunchView()
            case .signedOut:
                AuthFlowView(session: session)
            case .signedIn(let user):
                MainTabView(environment: environment, user: user)
            }
        }
        .task {
            await session.restore()
        }
    }
}

struct LaunchView: View {
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(spacing: PhokartaSpacing.md) {
            Text("app.name")
                .font(.largeTitle.bold())
                .foregroundStyle(PhokartaColor.ink(for: colorScheme))
            ProgressView()
                .accessibilityLabel(String(localized: "auth.restoring"))
            Text("auth.restoring")
                .font(.body)
                .foregroundStyle(PhokartaColor.muted(for: colorScheme))
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(PhokartaColor.background(for: colorScheme))
    }
}

struct ConfigurationErrorView: View {
    let message: String
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(alignment: .leading, spacing: PhokartaSpacing.md) {
            Text("error.invalid_configuration")
                .font(.title.bold())
            Text(message)
                .font(.body)
        }
        .padding(PhokartaSpacing.lg)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .background(PhokartaColor.background(for: colorScheme))
        .foregroundStyle(PhokartaColor.ink(for: colorScheme))
    }
}
