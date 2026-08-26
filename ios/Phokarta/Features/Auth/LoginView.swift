import SwiftUI

struct LoginView: View {
    @State private var controller: LoginController
    let onCreateAccount: () -> Void
    @Environment(\.colorScheme) private var colorScheme
    @FocusState private var focused: Field?

    private enum Field {
        case identifier, password
    }

    init(session: AuthSessionController, onCreateAccount: @escaping () -> Void) {
        _controller = State(initialValue: LoginController(session: session))
        self.onCreateAccount = onCreateAccount
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: PhokartaSpacing.md) {
                Text("app.name")
                    .font(.largeTitle.bold())
                    .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                Text("auth.login.subtitle")
                    .font(.body)
                    .foregroundStyle(PhokartaColor.muted(for: colorScheme))

                TextField("auth.identifier", text: $controller.identifier)
                    .textContentType(.username)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.next)
                    .focused($focused, equals: .identifier)
                    .onSubmit { focused = .password }
                    .disabled(controller.isLoading)
                    .padding(PhokartaSpacing.md)
                    .background(PhokartaColor.surface(for: colorScheme), in: RoundedRectangle(cornerRadius: PhokartaRadius.md))
                    .accessibilityLabel(String(localized: "auth.identifier"))

                SecureField("auth.password", text: $controller.password)
                    .textContentType(.password)
                    .submitLabel(.go)
                    .focused($focused, equals: .password)
                    .onSubmit(controller.submit)
                    .disabled(controller.isLoading)
                    .padding(PhokartaSpacing.md)
                    .background(PhokartaColor.surface(for: colorScheme), in: RoundedRectangle(cornerRadius: PhokartaRadius.md))
                    .accessibilityLabel(String(localized: "auth.password"))

                if let errorMessage = controller.errorMessage {
                    Text(errorMessage)
                        .font(.body)
                        .foregroundStyle(.red)
                        .accessibilityLabel(errorMessage)
                }

                Button(action: controller.submit) {
                    Group {
                        if controller.isLoading {
                            ProgressView()
                                .tint(.white)
                        } else {
                            Text("auth.login")
                                .font(.headline)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, PhokartaSpacing.md)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.white)
                .background(PhokartaColor.accent(for: colorScheme), in: RoundedRectangle(cornerRadius: PhokartaRadius.lg))
                .disabled(controller.isLoading)
                .accessibilityLabel(String(localized: "auth.login"))

                Button("auth.create_account", action: onCreateAccount)
                    .frame(maxWidth: .infinity)
                    .disabled(controller.isLoading)
                    .foregroundStyle(PhokartaColor.accent(for: colorScheme))
            }
            .padding(.horizontal, PhokartaSpacing.lg)
            .padding(.vertical, PhokartaSpacing.xl)
        }
        .background(PhokartaColor.background(for: colorScheme))
        .onDisappear { controller.cancel() }
    }
}
