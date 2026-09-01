import SwiftUI

struct DimensionRatingRow: View {
    let key: String
    let value: Double?
    let onEnable: () -> Void
    let onChange: (Double) -> Void
    let onRemove: () -> Void

    var body: some View {
        if let value {
            VStack(alignment: .leading, spacing: PhokartaSpacing.xs) {
                HStack {
                    Text(VisitDimensionCatalog.localizedName(for: key)).font(.headline)
                    Spacer()
                    Text(ScoreFormatting.display(value)).font(.headline.monospacedDigit())
                    Button(action: onRemove) {
                        Image(systemName: "xmark.circle.fill")
                    }
                    .accessibilityLabel(String(localized: "visit.dimension.remove \(VisitDimensionCatalog.localizedName(for: key))"))
                }
                Slider(value: Binding(get: { value }, set: onChange), in: 0...10, step: 0.1)
                    .accessibilityLabel(VisitDimensionCatalog.localizedName(for: key))
                    .accessibilityValue(String(localized: "visit.score_out_of_ten \(ScoreFormatting.display(value))"))
            }
        } else {
            Button(action: onEnable) {
                HStack {
                    Text(VisitDimensionCatalog.localizedName(for: key))
                    Spacer()
                    Label("visit.dimension.add", systemImage: "plus.circle")
                }
            }
            .accessibilityLabel(String(localized: "visit.dimension.add_named \(VisitDimensionCatalog.localizedName(for: key))"))
        }
    }
}
