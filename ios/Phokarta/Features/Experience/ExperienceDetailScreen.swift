import Foundation
import Observation
import SwiftUI

enum ExperienceDetailMediaPresentation: Equatable {
    case media
    case noMedia

    static func resolve(mediaCount: Int) -> Self { mediaCount > 0 ? .media : .noMedia }
}

enum AcknowledgementPresentation: Equatable {
    case action
    case confirmed

    static func resolve(acknowledged: Bool) -> Self { acknowledged ? .confirmed : .action }
}

enum ConversationComposerPresentation: Equatable {
    case expanded
    case compact
}

struct ConversationCountPresentation: Equatable {
    let visual: String
    let accessibilityLabel: String
}

enum ConversationPresentation {
    static func authorBadgeKey(
        experienceAuthor: Bool, rootType: ConversationEntryType, isReply: Bool
    ) -> String? {
        guard experienceAuthor else { return nil }
        return isReply && rootType == .question
            ? "conversation.author_answer" : "conversation.author"
    }

    static func canReply(to entry: ConversationEntry) -> Bool {
        entry.type != .reply && entry.syncState == .synced
    }

    static func timestamp(
        createdAt: String,
        now: Date = Date(),
        calendar inputCalendar: Calendar = .current,
        locale: Locale = .current
    ) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let created = formatter.date(from: createdAt) ?? ISO8601DateFormatter().date(from: createdAt)
        guard let created else { return localized("conversation.time.just_now", locale: locale) }

        var calendar = inputCalendar
        calendar.locale = locale
        let elapsed = max(0, Int(now.timeIntervalSince(created)))
        let turkish = locale.identifier.lowercased().hasPrefix("tr")
        if elapsed < 60 { return localized("conversation.time.just_now", locale: locale) }
        if elapsed < 3_600 {
            let minutes = elapsed / 60
            return turkish ? "\(minutes) dk" : "\(minutes)m"
        }
        if calendar.isDate(created, inSameDayAs: now) {
            let hours = elapsed / 3_600
            return turkish ? "\(hours) sa" : "\(hours)h"
        }
        if let yesterday = calendar.date(byAdding: .day, value: -1, to: now),
           calendar.isDate(created, inSameDayAs: yesterday) {
            return localized("conversation.time.yesterday", locale: locale)
        }

        let dateFormatter = DateFormatter()
        dateFormatter.locale = locale
        dateFormatter.calendar = calendar
        dateFormatter.timeZone = calendar.timeZone
        let createdYear = calendar.component(.year, from: created)
        let currentYear = calendar.component(.year, from: now)
        dateFormatter.dateFormat = if createdYear == currentYear {
            turkish ? "d MMM" : "MMM d"
        } else {
            turkish ? "d MMM yyyy" : "MMM d, yyyy"
        }
        return dateFormatter.string(from: created)
    }

    static func metadata(
        typeLabel: String?, timestamp: String, edited: Bool, editedLabel: String
    ) -> String {
        var parts = [String]()
        if let typeLabel, !typeLabel.isEmpty { parts.append(typeLabel) }
        parts.append(timestamp)
        if edited { parts.append(editedLabel) }
        return parts.joined(separator: " · ")
    }

    static func composerMode(
        visibleRootCount: Int, expansionRequested: Bool, draft: String
    ) -> ConversationComposerPresentation {
        visibleRootCount == 0 || expansionRequested || !draft.isEmpty ? .expanded : .compact
    }

    static func count(_ count: Int, locale: Locale = .current) -> ConversationCountPresentation? {
        guard count > 0 else { return nil }
        let turkish = locale.identifier.lowercased().hasPrefix("tr")
        let label: String
        if turkish {
            label = count == 1 ? "1 soru veya yorum" : "\(count) soru ve yorum"
        } else {
            label = count == 1 ? "1 question or comment" : "\(count) questions and comments"
        }
        return ConversationCountPresentation(visual: String(count), accessibilityLabel: label)
    }

    private static func localized(_ key: String, locale: Locale) -> String {
        String(localized: String.LocalizationValue(key), locale: locale)
    }
}

@MainActor
@Observable
final class ExperienceDetailController {
    private(set) var experience: ExperienceV2?
    private(set) var isLoading = false
    private(set) var relationshipBusy = false
    private(set) var planBusy = false
    private(set) var acknowledgementBusy = false
    private(set) var conversation: [ConversationEntry] = []
    private(set) var conversationLoading = false
    private(set) var conversationBusy = false
    private(set) var error: AppError?
    private let id: UUID
    private let service: any ExperienceDiscoveryServing
    private let privacy: any PrivacyV2Serving
    private let mutationRepository: (any OfflineMutationRepository)?
    private let syncEngine: MutationSyncEngine?
    private let currentUserId: UUID?

    init(
        id: UUID, service: any ExperienceDiscoveryServing, privacy: any PrivacyV2Serving,
        mutationRepository: (any OfflineMutationRepository)? = nil,
        syncEngine: MutationSyncEngine? = nil,
        currentUserId: UUID? = nil
    ) {
        self.id = id
        self.service = service
        self.privacy = privacy
        self.mutationRepository = mutationRepository
        self.syncEngine = syncEngine
        self.currentUserId = currentUserId
    }

    func load() async {
        isLoading = true
        error = nil
        do {
            let value = try await service.experience(id: id)
            experience = value
            isLoading = false
            await renewExpiringMedia(value)
            await loadConversation()
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

    func togglePlanned() async {
        guard let value = experience, !planBusy else { return }
        planBusy = true
        defer { planBusy = false }
        do {
            let desired = !(value.plannedByViewer ?? false)
            if let mutationRepository, let currentUserId {
                try await mutationRepository.setPlannedExperience(value, userId: currentUserId, desired: desired)
                _ = await syncEngine?.drain()
            } else {
                _ = try await service.setPlanned(experienceId: value.id, desired: desired)
            }
            experience = value.replacingMilestone(
                planned: desired,
                acknowledged: value.acknowledgedByViewer ?? false,
                count: value.acknowledgementCount ?? 0
            )
        } catch let appError as AppError { error = appError }
        catch { self.error = .server }
    }

    func acknowledge() async {
        guard let value = experience,
              value.author.relationship != nil,
              !(value.acknowledgedByViewer ?? false),
              !acknowledgementBusy else { return }
        acknowledgementBusy = true
        defer { acknowledgementBusy = false }
        do {
            if let mutationRepository, let currentUserId {
                _ = try await mutationRepository.acknowledgeExperience(value, userId: currentUserId)
                _ = await syncEngine?.drain()
            } else {
                _ = try await service.acknowledge(experienceId: value.id)
            }
            experience = value.replacingMilestone(
                planned: value.plannedByViewer ?? false,
                acknowledged: true,
                count: (value.acknowledgementCount ?? 0) + 1
            )
        } catch let appError as AppError { error = appError }
        catch { self.error = .server }
    }

    func loadConversation() async {
        conversationLoading = true
        defer { conversationLoading = false }
        if let mutationRepository, let currentUserId,
           let local = try? await mutationRepository.localConversation(experienceId: id, userId: currentUserId),
           !local.isEmpty {
            conversation = local
        }
        do {
            let page = try await service.conversation(experienceId: id)
            if let mutationRepository, let currentUserId {
                try await mutationRepository.replaceConversationSnapshot(
                    page.items, experienceId: id, userId: currentUserId
                )
                conversation = try await mutationRepository.localConversation(
                    experienceId: id, userId: currentUserId
                )
            } else {
                conversation = page.items
            }
        } catch let appError as AppError {
            if conversation.isEmpty { error = appError }
        } catch {
            if conversation.isEmpty { self.error = .server }
        }
    }

    func createConversation(type: ConversationEntryType, body: String) async {
        guard let experience, !conversationBusy else { return }
        conversationBusy = true
        defer { conversationBusy = false }
        do {
            if let mutationRepository, let currentUserId {
                _ = try await mutationRepository.createConversationRoot(
                    experience: experience, type: type, body: body,
                    author: optimisticAuthor(for: experience, userId: currentUserId),
                    userId: currentUserId
                )
                await refreshLocalConversation()
                _ = await syncEngine?.drain()
                await refreshLocalConversation()
            } else {
                _ = try await service.createConversationEntry(
                    experienceId: experience.id,
                    request: CreateConversationEntryRequest(
                        clientMutationId: UUID(), type: type, body: body
                    )
                )
                await loadConversation()
            }
        } catch let appError as AppError { error = appError }
        catch { self.error = .server }
    }

    func reply(to root: ConversationEntry, body: String) async {
        guard let experience, !conversationBusy else { return }
        conversationBusy = true
        defer { conversationBusy = false }
        do {
            if let mutationRepository, let currentUserId {
                _ = try await mutationRepository.createConversationReply(
                    experience: experience, root: root, body: body,
                    author: optimisticAuthor(for: experience, userId: currentUserId),
                    userId: currentUserId
                )
                await refreshLocalConversation()
                _ = await syncEngine?.drain()
                await refreshLocalConversation()
            } else {
                _ = try await service.createConversationReply(
                    rootId: root.id,
                    request: CreateConversationReplyRequest(clientMutationId: UUID(), body: body)
                )
                await loadConversation()
            }
        } catch let appError as AppError { error = appError }
        catch { self.error = .server }
    }

    func edit(_ entry: ConversationEntry, body: String) async {
        guard !conversationBusy else { return }
        conversationBusy = true
        defer { conversationBusy = false }
        do {
            if let mutationRepository, let currentUserId {
                try await mutationRepository.editConversationEntry(entry, body: body, userId: currentUserId)
                await refreshLocalConversation()
                _ = await syncEngine?.drain()
                await refreshLocalConversation()
            } else {
                _ = try await service.updateConversationEntry(entryId: entry.id, body: body)
                await loadConversation()
            }
        } catch let appError as AppError { error = appError }
        catch { self.error = .server }
    }

    func delete(_ entry: ConversationEntry) async {
        guard !conversationBusy else { return }
        conversationBusy = true
        defer { conversationBusy = false }
        do {
            if let mutationRepository, let currentUserId {
                try await mutationRepository.deleteConversationEntry(entry, userId: currentUserId)
                await refreshLocalConversation()
                _ = await syncEngine?.drain()
                await refreshLocalConversation()
            } else {
                try await service.deleteConversationEntry(entryId: entry.id)
                await loadConversation()
            }
        } catch let appError as AppError { error = appError }
        catch { self.error = .server }
    }

    func retry(_ entry: ConversationEntry) async {
        guard let mutationId = entry.clientMutationId,
              let mutationRepository, let currentUserId else { return }
        do {
            try await mutationRepository.retry(mutationId: mutationId, userId: currentUserId)
            await refreshLocalConversation()
            _ = await syncEngine?.drain()
            await refreshLocalConversation()
        } catch let appError as AppError { error = appError }
        catch { self.error = .server }
    }

    private func refreshLocalConversation() async {
        guard let mutationRepository, let currentUserId else { return }
        if let values = try? await mutationRepository.localConversation(
            experienceId: id, userId: currentUserId
        ) { conversation = values }
    }

    private func optimisticAuthor(for experience: ExperienceV2, userId: UUID) -> ConversationAuthor {
        if experience.author.id == userId {
            return ConversationAuthor(
                id: userId, username: experience.author.username,
                displayName: experience.author.displayName, avatarUrl: experience.author.avatarUrl
            )
        }
        return ConversationAuthor(id: userId, username: "", displayName: "", avatarUrl: nil)
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
            taxonomyVersion: taxonomyVersion,
            plannedByViewer: plannedByViewer,
            acknowledgedByViewer: acknowledgedByViewer,
            acknowledgementCount: acknowledgementCount,
            conversationCount: conversationCount
        )
    }


    func replacingMilestone(planned: Bool, acknowledged: Bool, count: Int) -> Self {
        ExperienceV2(
            id: id, classification: classification, author: author, place: place,
            experiencedAt: experiencedAt, title: title, titleSource: titleSource,
            titlePersisted: titlePersisted, story: story, tip: tip, feeling: feeling,
            primaryExperience: primaryExperience, companion: companion, timeOfDay: timeOfDay,
            vibes: vibes, practicalSignals: practicalSignals, dimensions: dimensions, media: media,
            visibility: visibility, taxonomyVersion: taxonomyVersion,
            plannedByViewer: planned, acknowledgedByViewer: acknowledged,
            acknowledgementCount: count, conversationCount: conversationCount
        )
    }
}

struct ExperienceDetailScreen: View {
    @State private var controller: ExperienceDetailController
    @State private var showingCollections = false
    @State private var reportController: ReportController?
    @State private var blockEntry: ConversationEntry?
    let collections: CollectionStore?
    let reportService: (any ReportServing)?
    let blockService: (any BlockServing)?
    let currentUserId: UUID?
    let onAuthor: (UUID) -> Void
    let onPlace: (UUID) -> Void
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.locale) private var locale

    init(
        id: UUID,
        service: any ExperienceDiscoveryServing,
        privacy: any PrivacyV2Serving,
        mutationRepository: (any OfflineMutationRepository)? = nil,
        syncEngine: MutationSyncEngine? = nil,
        currentUserId: UUID? = nil,
        collections: CollectionStore? = nil,
        reportService: (any ReportServing)? = nil,
        blockService: (any BlockServing)? = nil,
        onAuthor: @escaping (UUID) -> Void,
        onPlace: @escaping (UUID) -> Void
    ) {
        _controller = State(initialValue: ExperienceDetailController(
            id: id, service: service, privacy: privacy,
            mutationRepository: mutationRepository, syncEngine: syncEngine,
            currentUserId: currentUserId
        ))
        self.collections = collections
        self.reportService = reportService
        self.blockService = blockService
        self.currentUserId = currentUserId
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
        .sheet(isPresented: $showingCollections) {
            if let collections, let experience = controller.experience {
                ExperienceCollectionPickerSheet(store: collections, experience: experience)
            }
        }
        .sheet(isPresented: Binding(
            get: { reportController != nil },
            set: { if !$0 { reportController = nil } }
        )) {
            if let reportController {
                ReportSheet(controller: reportController) { self.reportController = nil }
            }
        }
        .alert(
            String(localized: "block.confirm_title"),
            isPresented: Binding(
                get: { blockEntry != nil },
                set: { if !$0 { blockEntry = nil } }
            ),
            presenting: blockEntry
        ) { entry in
            Button(String(localized: "block.action"), role: .destructive) {
                Task {
                    try? await blockService?.block(userId: entry.author.id)
                    blockEntry = nil
                    await controller.loadConversation()
                }
            }
            Button(String(localized: "action.cancel"), role: .cancel) { blockEntry = nil }
        } message: { _ in
            Text("block.confirm_body")
        }
    }

    private func content(_ experience: ExperienceV2) -> some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: PhokartaSpacing.md) {
                if !experience.media.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(spacing: PhokartaSpacing.sm) {
                            ForEach(experience.media.sorted(by: { $0.position < $1.position }), id: \.position) { media in
                                AsyncImage(url: URL(string: media.url)) { phase in
                                    switch phase {
                                    case .empty:
                                        ZStack {
                                            PhokartaColor.mist
                                            ProgressView()
                                                .accessibilityLabel(String(localized: "experience.media.loading"))
                                        }
                                    case .success(let image):
                                        image.resizable().scaledToFill()
                                    case .failure:
                                        ZStack {
                                            PhokartaColor.softSurface(for: colorScheme)
                                            Label("experience.media.failed", systemImage: "photo.badge.exclamationmark")
                                                .font(.subheadline.weight(.semibold))
                                                .foregroundStyle(.secondary)
                                        }
                                    @unknown default:
                                        ProgressView()
                                    }
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
                    HStack(spacing: 14) {
                        Image(systemName: "safari.fill")
                            .font(.system(size: 32))
                            .foregroundStyle(PhokartaColor.accent(for: colorScheme).opacity(0.45))
                        VStack(alignment: .leading, spacing: 4) {
                            Text("experience.no_media")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            if let label = ExperienceLocalizedLabels.primary(experience.primaryExperience.code, locale: locale) {
                                Text(label).font(.caption.weight(.semibold)).foregroundStyle(.tint)
                            }
                        }
                        Spacer()
                    }
                    .padding(.horizontal, 18)
                    .frame(height: 112)
                    .background(PhokartaColor.softSurface(for: colorScheme))
                    .clipShape(RoundedRectangle(cornerRadius: PhokartaRadius.lg))
                    .overlay(RoundedRectangle(cornerRadius: PhokartaRadius.lg).stroke(PhokartaColor.border(for: colorScheme), lineWidth: 1))
                    .accessibilityElement(children: .combine)
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
                        if relationship.state == .friends {
                            Text(relationshipTitle(relationship.state))
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(colorScheme == .dark ? Color(red: 204 / 255, green: 245 / 255, blue: 251 / 255) : PhokartaColor.ink(for: colorScheme))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 7)
                                .background(PhokartaColor.selected(for: colorScheme), in: Capsule())
                                .overlay(Capsule().stroke(PhokartaColor.accent(for: colorScheme).opacity(0.35), lineWidth: 1))
                        } else {
                            Button(relationshipTitle(relationship.state)) {
                                Task { await controller.toggleRelationship() }
                            }
                            .buttonStyle(.bordered)
                            .disabled(controller.relationshipBusy)
                        }
                    }
                }
                if ExperienceLocalizedLabels.shouldShowTitle(experience.title, placeName: experience.place.name, classification: experience.classification) {
                    Text(experience.title).font(.largeTitle.bold())
                }
                HStack(spacing: PhokartaSpacing.sm) {
                    Button {
                        Task { await controller.togglePlanned() }
                    } label: {
                        Label(
                            String(localized: (experience.plannedByViewer ?? false) ? "experience.planned" : "experience.plan"),
                            systemImage: (experience.plannedByViewer ?? false) ? "bookmark.fill" : "bookmark"
                        )
                    }
                    .buttonStyle(.bordered)
                    .disabled(controller.planBusy)

                    if experience.author.relationship != nil {
                        if AcknowledgementPresentation.resolve(acknowledged: experience.acknowledgedByViewer ?? false) == .confirmed {
                            Label("experience.acknowledged", systemImage: "checkmark.circle.fill")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 8)
                                .background(PhokartaColor.selected(for: colorScheme), in: Capsule())
                                .overlay(Capsule().stroke(PhokartaColor.accent(for: colorScheme).opacity(0.55), lineWidth: 1))
                                .accessibilityLabel(String(localized: "experience.acknowledged.accessibility"))
                                .accessibilityAddTraits(.isSelected)
                        } else {
                            Button {
                                Task { await controller.acknowledge() }
                            } label: {
                                Label("experience.acknowledge", systemImage: "checkmark.circle")
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(controller.acknowledgementBusy)
                        }
                    }
                }
                if (experience.acknowledgementCount ?? 0) > 0 {
                    Text(String(localized: "experience.acknowledgement_count \(experience.acknowledgementCount ?? 0)"))
                        .font(.caption).foregroundStyle(.secondary)
                }
                if collections != nil {
                    Button { showingCollections = true } label: {
                        Label("collection.add_experience", systemImage: "folder.badge.plus")
                    }
                    .buttonStyle(.bordered)
                    .accessibilityHint(String(localized: "collection.add_experience_hint"))
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
                    VStack(alignment: .leading, spacing: 4) {
                        Text("experience.tip")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(PhokartaColor.accent(for: colorScheme))
                        Text(tip)
                            .font(.subheadline)
                            .foregroundStyle(PhokartaColor.ink(for: colorScheme))
                    }
                    .padding(14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(PhokartaColor.softSurface(for: colorScheme), in: RoundedRectangle(cornerRadius: PhokartaRadius.md))
                    .overlay(RoundedRectangle(cornerRadius: PhokartaRadius.md).stroke(PhokartaColor.border(for: colorScheme), lineWidth: 1))
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
                ExperienceConversationSection(
                    controller: controller,
                    currentUserId: currentUserId,
                    onReport: openReport,
                    onBlock: { blockEntry = $0 }
                )
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

    private func openReport(_ entry: ConversationEntry) {
        guard let reportService, let currentUserId else { return }
        let report = ReportController(service: reportService)
        report.activate(accountId: currentUserId)
        report.open(target: .conversationEntry(
            id: entry.id,
            authorName: entry.author.displayName.isEmpty ? entry.author.username : entry.author.displayName
        ))
        reportController = report
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

private struct ExperienceConversationSection: View {
    @Bindable var controller: ExperienceDetailController
    let currentUserId: UUID?
    let onReport: (ConversationEntry) -> Void
    let onBlock: (ConversationEntry) -> Void
    @State private var type: ConversationEntryType = .question
    @State private var rootBody = ""
    @State private var composerExpanded = false
    @State private var replyingTo: ConversationEntry?
    @State private var editingEntry: ConversationEntry?
    @State private var deletingEntry: ConversationEntry?
    @State private var modalBody = ""
    @FocusState private var rootComposerFocused: Bool
    @Environment(\.colorScheme) private var colorScheme

    var bodyView: some View {
        VStack(alignment: .leading, spacing: PhokartaSpacing.sm) {
            Text("conversation.title").font(.title2.bold())
            Text("conversation.subtitle").font(.subheadline).foregroundStyle(.secondary)
            if composerMode == .compact {
                Button {
                    composerExpanded = true
                    DispatchQueue.main.async { rootComposerFocused = true }
                } label: {
                    Label("conversation.composer.compact", systemImage: "square.and.pencil")
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.bordered)
                .accessibilityLabel(Text("conversation.composer.compact"))
                .accessibilityHint(Text("conversation.composer.expand_hint"))
            } else {
                Picker("conversation.type", selection: $type) {
                    Text("conversation.question").tag(ConversationEntryType.question)
                    Text("conversation.comment").tag(ConversationEntryType.comment)
                }
                .pickerStyle(.segmented)
                TextField(
                    type == .question
                        ? String(localized: "conversation.question.placeholder")
                        : String(localized: "conversation.comment.placeholder"),
                    text: $rootBody,
                    axis: .vertical
                )
                .lineLimit(2...5)
                .focused($rootComposerFocused)
                .onChange(of: rootBody) { _, value in rootBody = String(value.prefix(Self.bodyLimit)) }
                HStack {
                    Text("\(rootBody.count)/\(Self.bodyLimit)").font(.caption).foregroundStyle(.secondary)
                    Spacer()
                    if !controller.conversation.isEmpty && rootBody.isEmpty {
                        Button("action.cancel") {
                            composerExpanded = false
                            rootComposerFocused = false
                        }
                        .buttonStyle(.borderless)
                    }
                    Button {
                        let submitted = rootBody
                        rootBody = ""
                        composerExpanded = false
                        rootComposerFocused = false
                        Task { await controller.createConversation(type: type, body: submitted) }
                    } label: {
                        Label("conversation.send", systemImage: "paperplane.fill")
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(rootBody.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || controller.conversationBusy)
                }
            }
            if controller.conversationLoading && controller.conversation.isEmpty {
                ProgressView().frame(maxWidth: .infinity).padding()
            } else if controller.conversation.isEmpty {
                Text("conversation.empty").foregroundStyle(.secondary).padding(.vertical)
            } else {
                ForEach(controller.conversation) { entry in
                    ConversationEntryCard(
                        entry: entry,
                        rootType: entry.type,
                        allowReply: true,
                        currentUserId: currentUserId,
                        busy: controller.conversationBusy,
                        onReply: {
                            replyingTo = entry
                            editingEntry = nil
                            modalBody = ""
                        },
                        onEdit: {
                            editingEntry = $0
                            replyingTo = nil
                            modalBody = $0.body
                        },
                        onDelete: { deletingEntry = $0 },
                        onRetry: { value in Task { await controller.retry(value) } },
                        onReport: onReport,
                        onBlock: onBlock
                    )
                }
            }
        }
        .padding(.top, PhokartaSpacing.sm)
        .sheet(isPresented: Binding(
            get: { replyingTo != nil || editingEntry != nil },
            set: {
                if !$0 { replyingTo = nil; editingEntry = nil; modalBody = "" }
            }
        )) {
            NavigationStack {
                VStack(alignment: .leading, spacing: 8) {
                    TextField("conversation.reply.placeholder", text: $modalBody, axis: .vertical)
                        .lineLimit(4...8)
                        .onChange(of: modalBody) { _, value in
                            modalBody = String(value.prefix(Self.bodyLimit))
                        }
                    Text("\(modalBody.count)/\(Self.bodyLimit)")
                        .font(.caption).foregroundStyle(.secondary)
                    Spacer()
                }
                .padding()
                .navigationTitle(
                    editingEntry == nil
                        ? String(localized: "conversation.reply.title")
                        : String(localized: "conversation.edit.title")
                )
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("action.cancel") { replyingTo = nil; editingEntry = nil; modalBody = "" }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button(
                            editingEntry == nil
                                ? String(localized: "conversation.reply")
                                : String(localized: "action.save")
                        ) {
                            let submitted = modalBody
                            let reply = replyingTo
                            let edit = editingEntry
                            replyingTo = nil
                            editingEntry = nil
                            modalBody = ""
                            Task {
                                if let edit { await controller.edit(edit, body: submitted) }
                                else if let reply { await controller.reply(to: reply, body: submitted) }
                            }
                        }
                        .disabled(modalBody.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || controller.conversationBusy)
                    }
                }
            }
            .presentationDetents([.medium])
        }
        .alert(
            String(localized: "conversation.delete.title"),
            isPresented: Binding(
                get: { deletingEntry != nil },
                set: { if !$0 { deletingEntry = nil } }
            ),
            presenting: deletingEntry
        ) { entry in
            Button(String(localized: "action.delete"), role: .destructive) {
                deletingEntry = nil
                Task { await controller.delete(entry) }
            }
            Button(String(localized: "action.cancel"), role: .cancel) { deletingEntry = nil }
        } message: { entry in
            Text(entry.replies.isEmpty
                 ? String(localized: "conversation.delete.body")
                 : String(localized: "conversation.delete.thread_body"))
        }
    }

    var body: some View { bodyView }
    private var composerMode: ConversationComposerPresentation {
        ConversationPresentation.composerMode(
            visibleRootCount: controller.conversation.count,
            expansionRequested: composerExpanded,
            draft: rootBody
        )
    }
    private static let bodyLimit = 1_000
}

private struct ConversationEntryCard: View {
    let entry: ConversationEntry
    let rootType: ConversationEntryType
    let allowReply: Bool
    let currentUserId: UUID?
    let busy: Bool
    let onReply: () -> Void
    let onEdit: (ConversationEntry) -> Void
    let onDelete: (ConversationEntry) -> Void
    let onRetry: (ConversationEntry) -> Void
    let onReport: (ConversationEntry) -> Void
    let onBlock: (ConversationEntry) -> Void
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.locale) private var locale

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 8) {
                AsyncImage(url: entry.author.avatarUrl.flatMap(URL.init(string:))) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    Image(systemName: "person.crop.circle.fill").resizable().foregroundStyle(.secondary)
                }
                .frame(width: allowReply ? 34 : 28, height: allowReply ? 34 : 28)
                .clipShape(Circle())
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text(authorName).font(.subheadline.weight(.semibold))
                        if entry.experienceAuthor {
                            Text(authorBadge)
                                .font(.caption2.weight(.semibold))
                                .padding(.horizontal, 7).padding(.vertical, 2)
                                .background(PhokartaColor.selected(for: colorScheme), in: Capsule())
                        }
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(metadata).font(.caption).foregroundStyle(.secondary)
                        if entry.syncState == .pending {
                            Text("conversation.pending").font(.caption).foregroundStyle(.orange)
                        } else if entry.syncState == .failed {
                            Text("conversation.failed").font(.caption).foregroundStyle(.red)
                        }
                    }
                }
                Spacer()
                Menu {
                    if entry.ownedByViewer {
                        Button("action.edit") { onEdit(entry) }
                        Button("action.delete", role: .destructive) { onDelete(entry) }
                    } else {
                        if entry.reportableByViewer { Button("report.action_conversation") { onReport(entry) } }
                        Button("block.action", role: .destructive) { onBlock(entry) }
                    }
                } label: {
                    Image(systemName: "ellipsis.circle").frame(width: 44, height: 44)
                }
                .disabled(busy)
                .accessibilityLabel(Text("conversation.actions"))
            }
            Text(entry.body).font(.body)
            HStack {
                Spacer()
                if entry.syncState == .failed {
                    Button("action.try_again") { onRetry(entry) }.buttonStyle(.borderless)
                }
                if allowReply && entry.syncState == .synced {
                    Button("conversation.reply", action: onReply).buttonStyle(.borderless).disabled(busy)
                }
            }
            ForEach(entry.replies) { reply in
                ConversationReplyCard(
                    entry: reply, rootType: rootType,
                    currentUserId: currentUserId, busy: busy,
                    onEdit: onEdit, onDelete: onDelete, onRetry: onRetry,
                    onReport: onReport, onBlock: onBlock
                )
                .padding(.leading, 18)
            }
        }
        .padding(12)
        .background(
            allowReply ? PhokartaColor.softSurface(for: colorScheme) : PhokartaColor.mist.opacity(0.55),
            in: RoundedRectangle(cornerRadius: PhokartaRadius.md)
        )
        .overlay(RoundedRectangle(cornerRadius: PhokartaRadius.md).stroke(PhokartaColor.border(for: colorScheme), lineWidth: 1))
    }

    private var authorName: String {
        if entry.author.id == currentUserId { return String(localized: "conversation.you") }
        return entry.author.displayName.isEmpty ? entry.author.username : entry.author.displayName
    }

    private var metadata: String {
        let typeLabel: String? = if allowReply {
            entry.type == .question
                ? String(localized: "conversation.question", locale: locale)
                : String(localized: "conversation.comment", locale: locale)
        } else { nil }
        return ConversationPresentation.metadata(
            typeLabel: typeLabel,
            timestamp: ConversationPresentation.timestamp(createdAt: entry.createdAt, locale: locale),
            edited: entry.edited,
            editedLabel: String(localized: "conversation.edited", locale: locale)
        )
    }

    private var authorBadge: LocalizedStringKey {
        LocalizedStringKey(ConversationPresentation.authorBadgeKey(
            experienceAuthor: entry.experienceAuthor,
            rootType: rootType,
            isReply: !allowReply
        ) ?? "conversation.author")
    }
}

private struct ConversationReplyCard: View {
    let entry: ConversationEntry
    let rootType: ConversationEntryType
    let currentUserId: UUID?
    let busy: Bool
    let onEdit: (ConversationEntry) -> Void
    let onDelete: (ConversationEntry) -> Void
    let onRetry: (ConversationEntry) -> Void
    let onReport: (ConversationEntry) -> Void
    let onBlock: (ConversationEntry) -> Void
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.locale) private var locale

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 8) {
                AsyncImage(url: entry.author.avatarUrl.flatMap(URL.init(string:))) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    Image(systemName: "person.crop.circle.fill").resizable().foregroundStyle(.secondary)
                }
                .frame(width: 28, height: 28)
                .clipShape(Circle())
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text(authorName).font(.subheadline.weight(.semibold))
                        if entry.experienceAuthor {
                            Text(authorBadge)
                                .font(.caption2.weight(.semibold))
                                .padding(.horizontal, 7).padding(.vertical, 2)
                                .background(PhokartaColor.selected(for: colorScheme), in: Capsule())
                        }
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(metadata).font(.caption).foregroundStyle(.secondary)
                        if entry.syncState == .pending {
                            Text("conversation.pending").font(.caption).foregroundStyle(.orange)
                        } else if entry.syncState == .failed {
                            Text("conversation.failed").font(.caption).foregroundStyle(.red)
                        }
                    }
                }
                Spacer()
                Menu {
                    if entry.ownedByViewer {
                        Button("action.edit") { onEdit(entry) }
                        Button("action.delete", role: .destructive) { onDelete(entry) }
                    } else {
                        if entry.reportableByViewer { Button("report.action_conversation") { onReport(entry) } }
                        Button("block.action", role: .destructive) { onBlock(entry) }
                    }
                } label: {
                    Image(systemName: "ellipsis.circle").frame(width: 44, height: 44)
                }
                .disabled(busy)
                .accessibilityLabel(Text("conversation.actions"))
            }
            Text(entry.body).font(.body)
            if entry.syncState == .failed {
                HStack {
                    Spacer()
                    Button("action.try_again") { onRetry(entry) }.buttonStyle(.borderless)
                }
            }
        }
        .padding(12)
        .background(
            PhokartaColor.mist.opacity(0.55),
            in: RoundedRectangle(cornerRadius: PhokartaRadius.md)
        )
        .overlay(RoundedRectangle(cornerRadius: PhokartaRadius.md).stroke(PhokartaColor.border(for: colorScheme), lineWidth: 1))
    }

    private var authorName: String {
        if entry.author.id == currentUserId { return String(localized: "conversation.you") }
        return entry.author.displayName.isEmpty ? entry.author.username : entry.author.displayName
    }

    private var metadata: String {
        ConversationPresentation.metadata(
            typeLabel: nil,
            timestamp: ConversationPresentation.timestamp(createdAt: entry.createdAt, locale: locale),
            edited: entry.edited,
            editedLabel: String(localized: "conversation.edited", locale: locale)
        )
    }

    private var authorBadge: LocalizedStringKey {
        LocalizedStringKey(rootType == .question
            ? "conversation.author_answer" : "conversation.author")
    }
}

struct ExperienceCollectionPickerSheet: View {
    let store: CollectionStore
    let experience: ExperienceV2
    @Environment(\.dismiss) private var dismiss
    @State private var loading = false
    @State private var error: AppError?

    var body: some View {
        NavigationStack {
            List {
                if loading && store.summaries.isEmpty {
                    ProgressView().frame(maxWidth: .infinity)
                } else if store.summaries.isEmpty {
                    FeatureEmptyState(title: String(localized: "collections.empty_list"))
                } else {
                    ForEach(store.summaries) { collection in
                        Button {
                            Task { await add(to: collection.id) }
                        } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(collection.title).font(.headline)
                                    Text(String(localized: String.LocalizationValue(collection.visibility.localizationKey)))
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Image(systemName: "plus.circle")
                            }
                        }
                        .accessibilityLabel(String(localized: "collection.add_experience_to \(collection.title)"))
                    }
                }
                if let error {
                    Text(error.localizedMessage).foregroundStyle(.red)
                }
            }
            .navigationTitle("collection.choose")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("action.cancel") { dismiss() }
                }
            }
            .task {
                loading = true
                defer { loading = false }
                do { try await store.refreshList() }
                catch let appError as AppError { error = appError }
                catch { self.error = .server }
            }
        }
    }

    private func add(to collectionID: UUID) async {
        error = nil
        do {
            try await store.add(experienceID: experience.id, to: collectionID)
            dismiss()
        } catch let appError as AppError { error = appError }
        catch { self.error = .server }
    }
}
