import SwiftUI

struct VisibilityPicker: View {
    let selection: VisitVisibility
    let onChange: (VisitVisibility) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: PhokartaSpacing.xs) {
            Picker("visit.visibility", selection: Binding(get: { selection }, set: onChange)) {
                ForEach(VisitVisibility.allCases, id: \.self) { option in
                    Text(String(localized: String.LocalizationValue(option.localizationKey))).tag(option)
                }
            }
            Text(String(localized: String.LocalizationValue(selection.helperLocalizationKey)))
                .font(.footnote)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
