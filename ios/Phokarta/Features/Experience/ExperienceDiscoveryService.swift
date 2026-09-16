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
}
