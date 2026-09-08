import SwiftUI

struct PendingVisitDetailSheet: View {
    let place: PlaceDetail
    let pending: PendingVisit
    let onDismiss: () -> Void
    let onRetry: () -> Void
    let onEditAndRetry: () -> Void
    let onRemove: () -> Void
    let onAcceptPolicy: () -> Void

    @Environment(\.colorScheme) private var colorScheme
    @State private var showingRemoveAlert = false

    init(
        place: PlaceDetail,
        pending: PendingVisit,
        onDismiss: @escaping () -> Void,
        onRetry: @escaping () -> Void,
        onEditAndRetry: @escaping () -> Void,
        onRemove: @escaping () -> Void,
        onAcceptPolicy: @escaping () -> Void = {}
    ) {
        self.place = place
        self.pending = pending
        self.onDismiss = onDismiss
        self.onRetry = onRetry
        self.onEditAndRetry = onEditAndRetry
        self.onRemove = onRemove
        self.onAcceptPolicy = onAcceptPolicy
    }

    private var statusLabel: String {
        switch pending.state {
        case .failedPermanent, .failedRetryable:
            return String(localized: "sync.status.failed")
        default:
            return String(localized: "sync.status.pending")
        }
    }

    private var actions: PendingVisitActions {
        pending.actions
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: PhokartaSpacing.md) {
                    Text(place.name)
                        .font(.title3.weight(.medium))
                        .foregroundStyle(PhokartaColor.muted(for: colorScheme))

                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(ScoreFormatting.display(pending.overallRating))
                            .font(.system(size: 36, weight: .bold, design: .rounded))
                            .foregroundStyle(PhokartaColor.ink(for: colorScheme))

                        Text(PlaceDateFormatting.mediumDate(from: pending.visitedAt))
                            .font(.subheadline)
                            .foregroundStyle(PhokartaColor.muted(for: colorScheme))
                    }

                    // Sync status and visibility
                    HStack(spacing: 6) {
                        Image(systemName: pending.failed ? "exclamationmark.triangle.fill" : "arrow.triangle.2.circlepath")
                            .foregroundStyle(pending.failed ? .red : PhokartaColor.sage)
                        Text("\(statusLabel) · \(String(localized: String.LocalizationValue(pending.visibility.localizationKey)))")
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(PhokartaColor.muted(for: colorScheme))
                    }
                    .padding(.vertical, 4)

                    // Failure reason banner if failed
                    if let reason = pending.failureReason {
                        HStack(spacing: 8) {
                            Image(systemName: "exclamationmark.circle.fill")
                                .foregroundStyle(.red)
                            Text(String(localized: String.LocalizationValue(reason.localizationKey)))
                                .font(.callout)
                                .foregroundStyle(.red)
                        }
                        .padding(PhokartaSpacing.sm)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color.red.opacity(0.1), in: RoundedRectangle(cornerRadius: PhokartaRadius.md))
                    }

                    // Dimensions
                    if !pending.ratingDimensions.isEmpty {
                        VStack(alignment: .leading, spacing: 6) {
                            ForEach(pending.ratingDimensions.sorted(by: { $0.key < $1.key }), id: \.key) { item in
                                HStack {
                                    Text(VisitDimensionCatalog.title(for: item.key))
                                        .font(.body)
                                    Spacer()
                                    Text(ScoreFormatting.display(item.value))
                                        .font(.body.weight(.bold))
                                }
                                .padding(.vertical, 2)
                            }
                        }
                        .padding(.top, 4)
                    }

                    // Public Review
                    if !pending.review.isEmpty {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("place.reviews")
                                .font(.subheadline.weight(.semibold))
                            Text(pending.review)
                                .font(.body)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(.top, 4)
                    }

                    // Private Memory
                    if !pending.personalNote.isEmpty {
                        VStack(alignment: .leading, spacing: 6) {
                            HStack(spacing: 6) {
                                Image(systemName: "lock.fill")
                                    .font(.subheadline)
                                Text("visit.memory.only_you")
                                    .font(.subheadline.weight(.semibold))
                            }
                            .foregroundStyle(PhokartaColor.muted(for: colorScheme))

                            Text(pending.personalNote)
                                .font(.body)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(PhokartaSpacing.md)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(PhokartaColor.surface(for: colorScheme), in: RoundedRectangle(cornerRadius: PhokartaRadius.md))
                        .padding(.top, 4)
                    }

                    Spacer(minLength: 24)

                    // Action buttons
                    HStack(spacing: PhokartaSpacing.sm) {
                        if actions.showRemove {
                            Button(role: .destructive) {
                                showingRemoveAlert = true
                            } label: {
                                Text("sync.action.remove")
                                    .font(.subheadline.weight(.medium))
                                    .foregroundStyle(.red)
                            }
                            .buttonStyle(.bordered)
                        }

                        Spacer()

                        if actions.showRetry {
                            Button {
                                onRetry()
                                onDismiss()
                            } label: {
                                Text("sync.action.retry")
                                    .font(.subheadline.weight(.semibold))
                            }
                            .buttonStyle(.borderedProminent)
                        }

                        if actions.showAcceptPolicy {
                            Button {
                                onAcceptPolicy()
                                onDismiss()
                            } label: {
                                Text("policy.accept_action")
                                    .font(.subheadline.weight(.semibold))
                            }
                            .buttonStyle(.borderedProminent)
                        }

                        if actions.showEditAndRetry {
                            Button {
                                onEditAndRetry()
                            } label: {
                                Text("sync.action.edit_and_retry")
                                    .font(.subheadline.weight(.semibold))
                            }
                            .buttonStyle(.borderedProminent)
                        }
                    }
                }
                .padding(PhokartaSpacing.md)
            }
            .navigationTitle(String(localized: "visit.pending_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "collections.cancel")) {
                        onDismiss()
                    }
                }
            }
            .alert(String(localized: "sync.remove_confirm_title"), isPresented: $showingRemoveAlert) {
                Button(String(localized: "collections.cancel"), role: .cancel) {}
                Button(String(localized: "sync.action.remove"), role: .destructive) {
                    onRemove()
                    onDismiss()
                }
            } message: {
                Text("sync.remove_confirm_message")
            }
        }
    }
}
