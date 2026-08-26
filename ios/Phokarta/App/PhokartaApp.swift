import SwiftUI

@main
struct PhokartaApp: App {
    var body: some Scene {
        WindowGroup {
            BootView()
        }
    }
}

struct BootView: View {
    @State private var environment: AppEnvironment?
    @State private var configError: String?

    var body: some View {
        Group {
            if let configError {
                ConfigurationErrorView(message: configError)
            } else if let environment {
                RootView(session: environment.session)
            } else {
                LaunchView()
            }
        }
        .task {
            guard environment == nil, configError == nil else { return }
            do {
                environment = try AppEnvironment.live()
            } catch {
                configError = String(describing: error)
            }
        }
    }
}
