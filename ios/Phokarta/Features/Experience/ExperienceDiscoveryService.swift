import Foundation

enum ConversationEntryType: String, ExperienceWireCode, Equatable, CaseIterable {
    case question = "QUESTION"
    case comment = "COMMENT"
    case reply = "REPLY"
    case unknown = "UNKNOWN"
}

enum ConversationSyncState: String, Sendable, Equatable {
    case synced = "SYNCED"
    case pending = "PENDING"
    case failed = "FAILED"
}

struct ConversationAuthor: Decodable, Equatable, Sendable {
    let id: UUID
    let username: String
    let displayName: String
    let avatarUrl: String?
}

struct ConversationEntry: Decodable, Equatable, Sendable, Identifiable {
    let id: UUID
    let experienceId: UUID
    let type: ConversationEntryType
    var body: String
    let author: ConversationAuthor
    let createdAt: String
    var updatedAt: String
    var edited: Bool
    let experienceAuthor: Bool
    let ownedByViewer: Bool
    let reportableByViewer: Bool
    var replies: [ConversationEntry]
    var syncState: ConversationSyncState
    var clientMutationId: UUID?

    init(
        id: UUID, experienceId: UUID, type: ConversationEntryType, body: String,
        author: ConversationAuthor, createdAt: String, updatedAt: String, edited: Bool,
        experienceAuthor: Bool, ownedByViewer: Bool, reportableByViewer: Bool,
        replies: [ConversationEntry] = [], syncState: ConversationSyncState = .synced,
        clientMutationId: UUID? = nil
    ) {
        self.id = id
        self.experienceId = experienceId
        self.type = type
        self.body = body
        self.author = author
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.edited = edited
        self.experienceAuthor = experienceAuthor
        self.ownedByViewer = ownedByViewer
        self.reportableByViewer = reportableByViewer
        self.replies = replies
        self.syncState = syncState
        self.clientMutationId = clientMutationId
    }

    private enum CodingKeys: String, CodingKey {
        case id, experienceId, type, body, author, createdAt, updatedAt, edited
        case experienceAuthor, ownedByViewer, reportableByViewer, replies
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(UUID.self, forKey: .id)
        experienceId = try container.decode(UUID.self, forKey: .experienceId)
        type = try container.decode(ConversationEntryType.self, forKey: .type)
        body = try container.decode(String.self, forKey: .body)
        author = try container.decode(ConversationAuthor.self, forKey: .author)
        createdAt = try container.decode(String.self, forKey: .createdAt)
        updatedAt = try container.decode(String.self, forKey: .updatedAt)
        edited = try container.decode(Bool.self, forKey: .edited)
        experienceAuthor = try container.decode(Bool.self, forKey: .experienceAuthor)
        ownedByViewer = try container.decode(Bool.self, forKey: .ownedByViewer)
        reportableByViewer = try container.decode(Bool.self, forKey: .reportableByViewer)
        replies = try container.decodeIfPresent([ConversationEntry].self, forKey: .replies) ?? []
        syncState = .synced
        clientMutationId = nil
    }
}

struct CreateConversationEntryRequest: Encodable, Equatable, Sendable {
    let clientMutationId: UUID
    let type: ConversationEntryType
    let body: String
}

struct CreateConversationReplyRequest: Encodable, Equatable, Sendable {
    let clientMutationId: UUID
    let body: String
}

struct UpdateConversationEntryRequest: Encodable, Equatable, Sendable {
    let body: String
}

struct ExperienceConversationEndpoint: APIEndpoint {
    typealias Response = CursorPageDTO<ConversationEntry>
    let experienceId: UUID
    let cursor: String?
    let size: Int
    var method: HTTPMethod { .get }
    var path: String { "api/v2/experiences/\(experienceId.uuidString.uppercased())/conversation" }
    var requiresAuthentication: Bool { false }
    var queryItems: [URLQueryItem] {
        var values = [URLQueryItem(name: "size", value: String(size))]
        if let cursor { values.append(URLQueryItem(name: "cursor", value: cursor)) }
        return values
    }
}

struct CreateConversationEntryEndpoint: APIEndpoint {
    typealias Response = ConversationEntry
    typealias Body = CreateConversationEntryRequest
    let experienceId: UUID
    let requestBody: CreateConversationEntryRequest
    var method: HTTPMethod { .post }
    var path: String { "api/v2/experiences/\(experienceId.uuidString.uppercased())/conversation" }
    var requiresAuthentication: Bool { true }
    var body: CreateConversationEntryRequest? { requestBody }
}

struct CreateConversationReplyEndpoint: APIEndpoint {
    typealias Response = ConversationEntry
    typealias Body = CreateConversationReplyRequest
    let rootId: UUID
    let requestBody: CreateConversationReplyRequest
    var method: HTTPMethod { .post }
    var path: String { "api/v2/conversation/\(rootId.uuidString.uppercased())/replies" }
    var requiresAuthentication: Bool { true }
    var body: CreateConversationReplyRequest? { requestBody }
}

struct UpdateConversationEntryEndpoint: APIEndpoint {
    typealias Response = ConversationEntry
    typealias Body = UpdateConversationEntryRequest
    let entryId: UUID
    let requestBody: UpdateConversationEntryRequest
    var method: HTTPMethod { .patch }
    var path: String { "api/v2/conversation/\(entryId.uuidString.uppercased())" }
    var requiresAuthentication: Bool { true }
    var body: UpdateConversationEntryRequest? { requestBody }
}

struct DeleteConversationEntryEndpoint: APIEndpoint {
    typealias Response = EmptyPayload
    let entryId: UUID
    var method: HTTPMethod { .delete }
    var path: String { "api/v2/conversation/\(entryId.uuidString.uppercased())" }
    var requiresAuthentication: Bool { true }
}

struct ExperienceFeedEndpoint: APIEndpoint {
    typealias Response = CursorPageDTO<ExperienceSummaryV2>
    let lens: ExperienceFeedLens
    let cursor: String?
    let size: Int
    let query: String?
    let primary: PrimaryExperienceCode?
    let vibe: VibeCode?
    let latitude: Double?
    let longitude: Double?
    let radiusMeters: Double?
    var method: HTTPMethod { .get }
    var path: String { "api/v2/experiences/feed" }
    var requiresAuthentication: Bool { true }
    var queryItems: [URLQueryItem] {
        var values = [
            URLQueryItem(name: "lens", value: lens.rawValue),
            URLQueryItem(name: "size", value: String(size))
        ]
        if let cursor { values.append(URLQueryItem(name: "cursor", value: cursor)) }
        if let query, !query.isEmpty { values.append(URLQueryItem(name: "q", value: query)) }
        if let primary { values.append(URLQueryItem(name: "primary", value: primary.rawValue)) }
        if let vibe { values.append(URLQueryItem(name: "vibe", value: vibe.rawValue)) }
        if let latitude { values.append(URLQueryItem(name: "lat", value: String(latitude))) }
        if let longitude { values.append(URLQueryItem(name: "lon", value: String(longitude))) }
        if let radiusMeters { values.append(URLQueryItem(name: "radiusMeters", value: String(radiusMeters))) }
        return values
    }
}

struct PlaceExperiencesEndpoint: APIEndpoint {
    typealias Response = CursorPageDTO<ExperienceSummaryV2>
    let placeId: UUID
    let cursor: String?
    let size: Int
    let primary: PrimaryExperienceCode?
    var method: HTTPMethod { .get }
    var path: String { "api/v2/places/\(placeId.uuidString.uppercased())/experiences" }
    var requiresAuthentication: Bool { true }
    var queryItems: [URLQueryItem] {
        var values = [URLQueryItem(name: "size", value: String(size))]
        if let cursor { values.append(URLQueryItem(name: "cursor", value: cursor)) }
        if let primary { values.append(URLQueryItem(name: "primary", value: primary.rawValue)) }
        return values
    }
}

struct ProfileExperiencesEndpoint: APIEndpoint {
    typealias Response = CursorPageDTO<ExperienceSummaryV2>
    let userId: UUID
    let cursor: String?
    let size: Int
    var method: HTTPMethod { .get }
    var path: String { "api/v2/users/\(userId.uuidString.uppercased())/experiences" }
    var requiresAuthentication: Bool { true }
    var queryItems: [URLQueryItem] {
        var values = [URLQueryItem(name: "size", value: String(size))]
        if let cursor { values.append(URLQueryItem(name: "cursor", value: cursor)) }
        return values
    }
}

struct PlannedExperiencesEndpoint: APIEndpoint {
    typealias Response = PageDTO<PlannedExperienceV2>
    let page: Int
    let size: Int
    var method: HTTPMethod { .get }
    var path: String { "api/v2/me/planned-experiences" }
    var requiresAuthentication: Bool { true }
    var queryItems: [URLQueryItem] {
        var values = [URLQueryItem(name: "size", value: String(size))]
        values.append(URLQueryItem(name: "page", value: String(page)))
        return values
    }
}

struct PlanExperienceEndpoint: APIEndpoint {
    typealias Response = PlannedExperienceV2
    let experienceId: UUID
    var method: HTTPMethod { .put }
    var path: String { "api/v2/me/planned-experiences/\(experienceId.uuidString.uppercased())" }
    var requiresAuthentication: Bool { true }
}

struct UnplanExperienceEndpoint: APIEndpoint {
    typealias Response = EmptyPayload
    let experienceId: UUID
    var method: HTTPMethod { .delete }
    var path: String { "api/v2/me/planned-experiences/\(experienceId.uuidString.uppercased())" }
    var requiresAuthentication: Bool { true }
}

struct AcknowledgeExperienceEndpoint: APIEndpoint {
    typealias Response = ExperienceAcknowledgementV2
    let experienceId: UUID
    var method: HTTPMethod { .put }
    var path: String { "api/v2/experiences/\(experienceId.uuidString.uppercased())/acknowledgement" }
    var requiresAuthentication: Bool { true }
}

struct ProfileAcknowledgementsEndpoint: APIEndpoint {
    typealias Response = PageDTO<ExperienceAcknowledgementV2>
    let userId: UUID
    let page: Int
    let size: Int
    var method: HTTPMethod { .get }
    var path: String { "api/v2/users/\(userId.uuidString.uppercased())/experience-acknowledgements" }
    var requiresAuthentication: Bool { true }
    var queryItems: [URLQueryItem] {
        var values = [URLQueryItem(name: "size", value: String(size))]
        values.append(URLQueryItem(name: "page", value: String(page)))
        return values
    }
}

protocol ExperienceDiscoveryServing: Sendable {
    func feed(
        lens: ExperienceFeedLens,
        cursor: String?,
        query: String?,
        primary: PrimaryExperienceCode?,
        vibe: VibeCode?,
        latitude: Double?,
        longitude: Double?,
        radiusMeters: Double?
    ) async throws -> CursorPageDTO<ExperienceSummaryV2>
    func experience(id: UUID) async throws -> ExperienceV2
    func placeExperiences(
        placeId: UUID,
        cursor: String?,
        primary: PrimaryExperienceCode?
    ) async throws -> CursorPageDTO<ExperienceSummaryV2>
    func profileExperiences(userId: UUID, cursor: String?) async throws -> CursorPageDTO<ExperienceSummaryV2>
    func renewMedia(id: UUID) async throws -> MediaAccessResponse
    func plannedExperiences(page: Int) async throws -> PageDTO<PlannedExperienceV2>
    func setPlanned(experienceId: UUID, desired: Bool) async throws -> PlannedExperienceV2?
    func acknowledge(experienceId: UUID) async throws -> ExperienceAcknowledgementV2
    func profileAcknowledgements(userId: UUID, page: Int) async throws -> PageDTO<ExperienceAcknowledgementV2>
    func conversation(experienceId: UUID) async throws -> CursorPageDTO<ConversationEntry>
    func createConversationEntry(experienceId: UUID, request: CreateConversationEntryRequest) async throws -> ConversationEntry
    func createConversationReply(rootId: UUID, request: CreateConversationReplyRequest) async throws -> ConversationEntry
    func updateConversationEntry(entryId: UUID, body: String) async throws -> ConversationEntry
    func deleteConversationEntry(entryId: UUID) async throws
}

extension ExperienceDiscoveryServing {
    func plannedExperiences(page: Int) async throws -> PageDTO<PlannedExperienceV2> { throw AppError.server }
    func setPlanned(experienceId: UUID, desired: Bool) async throws -> PlannedExperienceV2? { throw AppError.server }
    func acknowledge(experienceId: UUID) async throws -> ExperienceAcknowledgementV2 { throw AppError.server }
    func profileAcknowledgements(userId: UUID, page: Int) async throws -> PageDTO<ExperienceAcknowledgementV2> { throw AppError.server }
    func conversation(experienceId: UUID) async throws -> CursorPageDTO<ConversationEntry> { throw AppError.server }
    func createConversationEntry(experienceId: UUID, request: CreateConversationEntryRequest) async throws -> ConversationEntry { throw AppError.server }
    func createConversationReply(rootId: UUID, request: CreateConversationReplyRequest) async throws -> ConversationEntry { throw AppError.server }
    func updateConversationEntry(entryId: UUID, body: String) async throws -> ConversationEntry { throw AppError.server }
    func deleteConversationEntry(entryId: UUID) async throws { throw AppError.server }
}

struct ExperienceDiscoveryService: ExperienceDiscoveryServing {
    let client: APIClient

    func feed(
        lens: ExperienceFeedLens,
        cursor: String? = nil,
        query: String? = nil,
        primary: PrimaryExperienceCode? = nil,
        vibe: VibeCode? = nil,
        latitude: Double? = nil,
        longitude: Double? = nil,
        radiusMeters: Double? = nil
    ) async throws -> CursorPageDTO<ExperienceSummaryV2> {
        try await client.send(ExperienceFeedEndpoint(
            lens: lens,
            cursor: cursor,
            size: 20,
            query: query,
            primary: primary,
            vibe: vibe,
            latitude: latitude,
            longitude: longitude,
            radiusMeters: radiusMeters
        ))
    }

    func experience(id: UUID) async throws -> ExperienceV2 {
        try await client.send(ExperienceV2Endpoint(experienceId: id))
    }

    func placeExperiences(
        placeId: UUID,
        cursor: String? = nil,
        primary: PrimaryExperienceCode? = nil
    ) async throws -> CursorPageDTO<ExperienceSummaryV2> {
        try await client.send(PlaceExperiencesEndpoint(
            placeId: placeId,
            cursor: cursor,
            size: 20,
            primary: primary
        ))
    }

    func profileExperiences(
        userId: UUID,
        cursor: String? = nil
    ) async throws -> CursorPageDTO<ExperienceSummaryV2> {
        try await client.send(ProfileExperiencesEndpoint(userId: userId, cursor: cursor, size: 20))
    }

    func renewMedia(id: UUID) async throws -> MediaAccessResponse {
        try await client.send(MediaAccessEndpoint(mediaId: id))
    }

    func plannedExperiences(page: Int = 0) async throws -> PageDTO<PlannedExperienceV2> {
        try await client.send(PlannedExperiencesEndpoint(page: page, size: 20))
    }

    func setPlanned(experienceId: UUID, desired: Bool) async throws -> PlannedExperienceV2? {
        if desired { return try await client.send(PlanExperienceEndpoint(experienceId: experienceId)) }
        _ = try await client.send(UnplanExperienceEndpoint(experienceId: experienceId))
        return nil
    }

    func acknowledge(experienceId: UUID) async throws -> ExperienceAcknowledgementV2 {
        try await client.send(AcknowledgeExperienceEndpoint(experienceId: experienceId))
    }

    func profileAcknowledgements(userId: UUID, page: Int = 0) async throws -> PageDTO<ExperienceAcknowledgementV2> {
        try await client.send(ProfileAcknowledgementsEndpoint(userId: userId, page: page, size: 20))
    }

    func conversation(experienceId: UUID) async throws -> CursorPageDTO<ConversationEntry> {
        try await client.send(ExperienceConversationEndpoint(experienceId: experienceId, cursor: nil, size: 30))
    }

    func createConversationEntry(
        experienceId: UUID, request: CreateConversationEntryRequest
    ) async throws -> ConversationEntry {
        try await client.send(CreateConversationEntryEndpoint(experienceId: experienceId, requestBody: request))
    }

    func createConversationReply(
        rootId: UUID, request: CreateConversationReplyRequest
    ) async throws -> ConversationEntry {
        try await client.send(CreateConversationReplyEndpoint(rootId: rootId, requestBody: request))
    }

    func updateConversationEntry(entryId: UUID, body: String) async throws -> ConversationEntry {
        try await client.send(UpdateConversationEntryEndpoint(
            entryId: entryId, requestBody: UpdateConversationEntryRequest(body: body)
        ))
    }

    func deleteConversationEntry(entryId: UUID) async throws {
        _ = try await client.send(DeleteConversationEntryEndpoint(entryId: entryId))
    }
}
