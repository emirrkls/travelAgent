import Foundation
import Observation
import SwiftUI

@MainActor
@Observable
final class ExperienceDetailController {
    private(set) var experience: ExperienceV2?
    private(set) var isLoading = false
    private(set) var relationshipBusy = false
    private(set) var error: AppError?
    private let id: UUID
    private let service: any ExperienceDiscoveryServing
    private let privacy: any PrivacyV2Serving

    init(id: UUID, service: any ExperienceDiscoveryServing, privacy: any PrivacyV2Serving) {
        self.id = id
        self.service = service
        self.privacy = privacy
    }

    func load() async {
        isLoading = true
        error = nil
        do {
            let value = try await service.experience(id: id)
            experience = value
            isLoading = false
            await renewExpiringMedia(value)
        } catch let appError as AppError {
            error = appError
            isLoading = false
        } catch {
            self.error = .server
            isLoading = false
        }
    }

    func toggleRelationship() async {
        guard let value = experience, let relationship = value.author.relationship, !relationshipBusy else { return }
        relationshipBusy = true
        defer { relationshipBusy = false }
        do {
            let updated: RelationshipV2
            switch relationship.state {
            case .none: updated = try await privacy.follow(userId: value.author.id)
            case .requestPending: updated = try await privacy.cancelFollowRequest(userId: value.author.id)
            case .following, .friends: updated = try await privacy.unfollow(userId: value.author.id)
            default: return
            }
            experience = value.replacingRelationship(updated)
        } catch let appError as AppError {
            error = appError
        } catch {
            self.error = .server
        }
    }

    private func renewExpiringMedia(_ value: ExperienceV2) async {
        let parser = ISO8601DateFormatter()
        let threshold = Date().addingTimeInterval(60)
        var media = value.media
        for index in media.indices {
            let item = media[index]
            guard item.kind == .managed, let mediaId = item.id else { continue }
            let expiry = item.accessExpiresAt.flatMap(parser.date(from:))
            guard expiry == nil || expiry! < threshold else { continue }
            guard let response = try? await service.renewMedia(id: mediaId) else { continue }
            media[index] = ExperienceV2.Media(
                kind: item.kind,
                position: item.position,
                id: item.id,
                url: response.url.absoluteString,
                accessExpiresAt: response.expiresAt
            )
        }
        experience = value.replacingMedia(media)
    }
}

private extension ExperienceV2 {
    func replacingRelationship(_ relationship: RelationshipV2) -> Self {
        replacing(author: Author(
            id: author.id,
            username: author.username,
            displayName: author.displayName,
            avatarUrl: author.avatarUrl,
            relationship: relationship
        ), media: media)
    }

    func replacingMedia(_ media: [Media]) -> Self { replacing(author: author, media: media) }

    func replacing(author: Author, media: [Media]) -> Self {
        ExperienceV2(
            id: id,
            classification: classification,
            author: author,
            place: place,
            experiencedAt: experiencedAt,
            title: title,
            titleSource: titleSource,
            titlePersisted: titlePersisted,
            story: story,
            tip: tip,
            feeling: feeling,
            primaryExperience: primaryExperience,
            companion: companion,
            timeOfDay: timeOfDay,
            vibes: vibes,
            practicalSignals: practicalSignals,
            dimensions: dimensions,
            media: media,
            visibility: visibility,
            taxonomyVersion: taxonomyVersion
        )
    }
}

struct ExperienceDetailScreen: View {
    @State private var controller: ExperienceDetailController
    let onAuthor: (UUID) -> Void
    let onPlace: (UUID) -> Void
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.locale) private var locale

    init(
        id: UUID,
        service: any ExperienceDiscoveryServing,
        privacy: any PrivacyV2Serving,
        onAuthor: @escaping (UUID) -> Void,
        onPlace: @escaping (UUID) -> Void
    ) {
        _controller = State(initialValue: ExperienceDetailController(id: id, service: service, privacy: privacy))
        self.onAuthor = onAuthor
        self.onPlace = onPlace
    }

    var body: some View {
        Group {
            if controller.isLoading && controller.experience == nil {
                ProgressView()
            } else if let experience = controller.experience {
                content(experience)
            } else {
                FeatureEmptyState(
                    title: controller.error?.localizedMessage ?? phokartaString("experience.detail.error", locale: locale),
                    retryTitle: phokartaString("action.try_again", locale: locale),
                    retry: { Task { await controller.load() } }
                )
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(PhokartaColor.background(for: colorScheme))
        .navigationTitle(phokartaString("experience.detail.title", locale: locale))
        .navigationBarTitleDisplayMode(.inline)
        .task { if controller.experience == nil { await controller.load() } }
    }

    private func content(_ experience: ExperienceV2) -> some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: PhokartaSpacing.md) {
                if !experience.media.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(spacing: PhokartaSpacing.sm) {
                            ForEach(experience.media.sorted(by: { $0.position < $1.position }), id: \.position) { media in
                                AsyncImage(url: URL(string: media.url)) { image in
                                    image.resizable().scaledToFill()
                                } placeholder: {
                                    Rectangle().fill(PhokartaColor.mist)
                                }
                                .frame(width: 320, height: 260).clipped()
                                .clipShape(RoundedRectangle(cornerRadius: PhokartaRadius.lg))
                                .accessibilityLabel(String.localizedStringWithFormat(
                                    String(localized: "experience.gallery_image"),
                                    media.position + 1,
                                    experience.media.count
                                ))
                            }
                        }
                    }
                } else {
                    ZStack(alignment: .bottomLeading) {
                        PhokartaColor.softSurface(for: colorScheme)
                        Image(systemName: "safari.fill")
                            .font(.system(size: 38))
                            .foregroundStyle(PhokartaColor.accent(for: colorScheme))
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                            .padding(20)
                        VStack(alignment: .leading, spacing: 4) {
                            if let label = ExperienceLocalizedLabels.primary(experience.primaryExperience.code, locale: locale) {
                                Text(label).font(.caption.weight(.semibold)).foregroundStyle(.tint)
                            }
                            if ExperienceLocalizedLabels.shouldShowTitle(experience.title, placeName: experience.place.name, classification: experience.classification) {
                                Text(experience.title).font(.title2.bold())
                            }
                            Text(experience.place.name).foregroundStyle(.secondary)
                        }
                        .padding(20)
                    }
                    .frame(height: 190)
                    .clipShape(RoundedRectangle(cornerRadius: PhokartaRadius.lg))
                }
                HStack {
                    AsyncImage(url: experience.author.avatarUrl.flatMap(URL.init(string:))) { image in
                        image.resizable().scaledToFill()
                    } placeholder: {
                        Image(systemName: "person.crop.circle.fill").resizable().foregroundStyle(.secondary)
                    }
                    .frame(width: 44, height: 44).clipShape(Circle())
                    Button(action: { onAuthor(experience.author.id) }) {
                        VStack(alignment: .leading) {
                            Text(experience.author.displayName).font(.headline)
                            Text(experience.experiencedAt).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .buttonStyle(.plain)
                    Spacer()
                    if let relationship = experience.author.relationship,
                       relationship.state != .unavailable, relationship.state != .unknown {
                        Button(relationshipTitle(relationship.state)) {
                            Task { await controller.toggleRelationship() }
                        }
                        .buttonStyle(.bordered)
                        .disabled(controller.relationshipBusy || relationship.state == .friends)
                    }
                }
                if ExperienceLocalizedLabels.shouldShowTitle(experience.title, placeName: experience.place.name, classification: experience.classification) {
                    Text(experience.title).font(.largeTitle.bold())
                }
                if let primary = experience.primaryExperience.rawLabel ?? ExperienceLocalizedLabels.primary(experience.primaryExperience.code, locale: locale) {
                    Text(primary).font(.headline).foregroundStyle(.tint)
                }
                Text(ExperienceLocalizedLabels.feeling(experience.feeling.code, locale: locale))
                    .font(.subheadline.weight(.semibold))
                    .padding(.horizontal, 11).padding(.vertical, 7)
                    .background(PhokartaColor.selected(for: colorScheme), in: Capsule())
                if experience.companion != nil || experience.timeOfDay != nil || !experience.vibes.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack {
                            if let value = experience.companion { chip(ExperienceLocalizedLabels.companion(value, locale: locale)) }
                            if let value = experience.timeOfDay { chip(ExperienceLocalizedLabels.time(value, locale: locale)) }
                            ForEach(experience.vibes, id: \.rawValue) { vibe in chip(ExperienceLocalizedLabels.vibe(vibe, locale: locale)) }
                        }
                    }
                }
                if !experience.story.isEmpty {
                    Text("experience.story").font(.headline)
                    Text(experience.story).font(.body)
                }
                if let tip = experience.tip, !tip.isEmpty {
                    Text("\(String(localized: "experience.tip")) · \(tip)")
                        .padding().frame(maxWidth: .infinity, alignment: .leading)
                        .background(PhokartaColor.mist, in: RoundedRectangle(cornerRadius: PhokartaRadius.lg))
                }
                if !experience.practicalSignals.isEmpty {
                    Text("experience.practical").font(.headline)
                    ForEach(experience.practicalSignals, id: \.rawValue) { signal in
                        Text("• \(ExperienceLocalizedLabels.practical(signal, locale: locale))")
                    }
                }
                if !experience.dimensions.isEmpty {
                    Text("experience.dimensions").font(.headline)
                    ForEach(experience.dimensions, id: \.key) { dimension in
                        HStack {
                            Text(ExperienceLocalizedLabels.dimensionKey(dimension.key, locale: locale)).foregroundStyle(.secondary)
                            Spacer()
                            Text(dimension.semanticState.map { ExperienceLocalizedLabels.dimensionState($0, locale: locale) } ?? String(localized: "experience.past_ratings"))
                                .fontWeight(.semibold)
                        }
                    }
                }
                Button(action: { onPlace(experience.place.id) }) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("experience.about_place").font(.caption)
                        Text(experience.place.name).font(.title2.bold())
                        Text([experience.place.city, experience.place.region, experience.place.country]
                            .filter { !$0.isEmpty }.joined(separator: " · "))
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
                    .background(PhokartaColor.softSurface(for: colorScheme), in: RoundedRectangle(cornerRadius: PhokartaRadius.lg))
                    .overlay(RoundedRectangle(cornerRadius: PhokartaRadius.lg).stroke(PhokartaColor.border(for: colorScheme), lineWidth: 1))
                }
                .buttonStyle(.plain)
                if let error = controller.error {
                    Text(error.localizedMessage).font(.footnote).foregroundStyle(.red)
                }
            }
            .padding(PhokartaSpacing.md)
        }
    }

    private func chip(_ text: String) -> some View {
        Text(text).font(.caption).padding(.horizontal, 10).padding(.vertical, 6)
            .background(PhokartaColor.softSurface(for: colorScheme), in: Capsule())
    }

    private func relationshipTitle(_ state: RelationshipActionState) -> LocalizedStringKey {
        switch state {
        case .none: "social.follow"
        case .requestPending: "experience.relationship.requested"
        case .following: "experience.relationship.following"
        case .friends: "experience.relationship.friends"
        default: ""
        }
    }
}
