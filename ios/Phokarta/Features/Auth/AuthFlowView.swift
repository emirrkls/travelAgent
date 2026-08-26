import SwiftUI

struct AuthFlowView: View {
    let session: AuthSessionController
    @State private var showsRegister = false

    var body: some View {
        NavigationStack {
            LoginView(session: session, onCreateAccount: { showsRegister = true })
                .navigationDestination(isPresented: $showsRegister) {
                    RegisterView(session: session, onHaveAccount: { showsRegister = false })
                }
        }
    }
}
