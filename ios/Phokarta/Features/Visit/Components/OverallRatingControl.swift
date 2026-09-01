import SwiftUI

struct OverallRatingControl: View {
    let value: Double
    let onChange: @MainActor @Sendable (Double) -> Void

    var body: some View {
        VStack(spacing: PhokartaSpacing.sm) {
            Text(ScoreFormatting.display(value))
                .font(.system(.largeTitle, design: .rounded, weight: .bold))
                .accessibilityHidden(true)
            Slider(value: Binding(get: { value }, set: onChange), in: 0...10, step: 0.1)
                .accessibilityLabel(String(localized: "visit.overall"))
                .accessibilityValue(String(localized: "visit.score_out_of_ten \(ScoreFormatting.display(value))"))
        }
    }
}
