import SwiftUI

struct RegisterView: View {
    @State private var controller: RegisterController
    let onHaveAccount: () -> Void
    @Environment(\.colorScheme) private var colorScheme
    @FocusState private var focused: Field?

    private enum Field {
        case displayName, username, email, password
    }

    init(session: AuthSessionController, onHaveAccount: @escaping () -> Void) {
        _controller = State(initialValue: RegisterController(session: session))
        self.onHaveAccount = onHaveAccount
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: PhokartaSpacing.md) {
                Text("auth.register")
                    .font(.largeTitle.bold())
                    .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                Text("auth.register.subtitle")
                    .font(.body)
                    .foregroundStyle(PhokartaColor.muted(for: colorScheme))

                TextField("auth.display_name", text: $controller.displayName)
                    .textContentType(.name)
                    .submitLabel(.next)
                    .focused($focused, equals: .displayName)
                    .onSubmit { focused = .username }
                    .disabled(controller.isLoading)
                    .padding(PhokartaSpacing.md)
                    .background(PhokartaColor.surface(for: colorScheme), in: RoundedRectangle(cornerRadius: PhokartaRadius.md))
                    .accessibilityLabel(String(localized: "auth.display_name"))

                TextField("auth.username", text: $controller.username)
                    .textContentType(.username)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.next)
                    .focused($focused, equals: .username)
                    .onSubmit { focused = .email }
                    .disabled(controller.isLoading)
                    .padding(PhokartaSpacing.md)
                    .background(PhokartaColor.surface(for: colorScheme), in: RoundedRectangle(cornerRadius: PhokartaRadius.md))
                    .accessibilityLabel(String(localized: "auth.username"))

                TextField("auth.email", text: $controller.email)
                    .textContentType(.emailAddress)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.next)
                    .focused($focused, equals: .email)
                    .onSubmit { focused = .password }
                    .disabled(controller.isLoading)
                    .padding(PhokartaSpacing.md)
                    .background(PhokartaColor.surface(for: colorScheme), in: RoundedRectangle(cornerRadius: PhokartaRadius.md))
                    .accessibilityLabel(String(localized: "auth.email"))

                SecureField("auth.password", text: $controller.password)
                    .textContentType(.newPassword)
                    .submitLabel(.join)
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
                }

                Button(action: controller.submit) {
                    Group {
                        if controller.isLoading {
                            ProgressView()
                                .tint(.white)
                        } else {
                            Text("auth.register")
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
                .accessibilityLabel(String(localized: "auth.register"))

                Button("auth.already_have_account", action: onHaveAccount)
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
