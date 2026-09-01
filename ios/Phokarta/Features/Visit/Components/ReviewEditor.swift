import SwiftUI

struct ReviewEditor: View {
    let text: String
    let onChange: @MainActor @Sendable (String) -> Void

    var body: some View {
        VStack(alignment: .trailing, spacing: 4) {
            TextEditor(text: Binding(get: { text }, set: onChange))
                .frame(minHeight: 120)
                .accessibilityLabel(String(localized: "visit.review.a11y"))
            Text("\(text.count)/\(VisitValidation.textLimit)")
                .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
        }
    }
}
