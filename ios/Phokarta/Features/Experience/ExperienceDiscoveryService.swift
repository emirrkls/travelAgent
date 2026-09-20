import Foundation

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
}

extension ExperienceDiscoveryServing {
    func plannedExperiences(page: Int) async throws -> PageDTO<PlannedExperienceV2> { throw AppError.server }
    func setPlanned(experienceId: UUID, desired: Bool) async throws -> PlannedExperienceV2? { throw AppError.server }
    func acknowledge(experienceId: UUID) async throws -> ExperienceAcknowledgementV2 { throw AppError.server }
    func profileAcknowledgements(userId: UUID, page: Int) async throws -> PageDTO<ExperienceAcknowledgementV2> { throw AppError.server }
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
}
