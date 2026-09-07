import Foundation

enum VisitVisibility: String, Codable, CaseIterable, Equatable, Sendable {
    case publicAccess = "PUBLIC"
    case friends = "FRIENDS"
    case privateAccess = "PRIVATE"

    var localizationKey: String {
        switch self {
        case .publicAccess: "visit.visibility.public"
        case .friends: "visit.visibility.friends"
        case .privateAccess: "visit.visibility.private"
        }
    }

    var helperLocalizationKey: String {
        switch self {
        case .publicAccess: "visit.visibility.public.help"
        case .friends: "visit.visibility.friends.help"
        case .privateAccess: "visit.visibility.private.help"
        }
    }
}

struct VisitDimensionScore: Codable, Equatable, Sendable, Identifiable {
    let key: String
    let score: Double
    var id: String { key }
}

struct VisitCreateRequest: Encodable, Equatable, Sendable {
    let clientMutationId: UUID
    let placeId: UUID
    let visitedAt: String
    let overallRating: Double
    let dimensions: [VisitDimensionScore]
    let publicReview: String?
    let privateMemory: String?
    let photos: [String]?
    let mediaIds: [UUID]?
    let visibility: VisitVisibility

    init(
        clientMutationId: UUID,
        placeId: UUID,
        visitedAt: String,
        overallRating: Double,
        dimensions: [VisitDimensionScore],
        publicReview: String?,
        privateMemory: String?,
        photos: [String]? = nil,
        mediaIds: [UUID]? = nil,
        visibility: VisitVisibility
    ) {
        self.clientMutationId = clientMutationId
        self.placeId = placeId
        self.visitedAt = visitedAt
        self.overallRating = overallRating
        self.dimensions = dimensions
        self.publicReview = publicReview
        self.privateMemory = privateMemory
        self.photos = photos
        self.mediaIds = mediaIds
        self.visibility = visibility
    }

    var logicalPayload: LogicalPayload {
        LogicalPayload(
            placeId: placeId,
            visitedAt: visitedAt,
            overallRating: overallRating,
            dimensions: dimensions.sorted { $0.key < $1.key },
            publicReview: publicReview,
            privateMemory: privateMemory,
            mediaIds: mediaIds ?? [],
            visibility: visibility
        )
    }

    struct LogicalPayload: Equatable, Sendable {
        let placeId: UUID
        let visitedAt: String
        let overallRating: Double
        let dimensions: [VisitDimensionScore]
        let publicReview: String?
        let privateMemory: String?
        let mediaIds: [UUID]
        let visibility: VisitVisibility
    }
}

/// Owner-only Visit response. This is the only iOS model that decodes `privateMemory`.
struct OwnerVisit: Decodable, Equatable, Sendable, Identifiable {
    let id: UUID
    let place: PlaceSummary
    let visitedAt: String
    let overallRating: Double
    let dimensions: [VisitDimensionScore]
    let publicReview: String
    let privateMemory: String
    let media: [VisitMediaDTO]
    let visibility: VisitVisibility
    let verificationStatus: String?

    init(
        id: UUID,
        place: PlaceSummary,
        visitedAt: String,
        overallRating: Double,
        dimensions: [VisitDimensionScore] = [],
        publicReview: String = "",
        privateMemory: String = "",
        media: [VisitMediaDTO] = [],
        visibility: VisitVisibility = .publicAccess,
        verificationStatus: String? = nil
    ) {
        self.id = id
        self.place = place
        self.visitedAt = visitedAt
        self.overallRating = overallRating
        self.dimensions = dimensions
        self.publicReview = publicReview
        self.privateMemory = privateMemory
        self.media = media
        self.visibility = visibility
        self.verificationStatus = verificationStatus
    }

    private enum CodingKeys: String, CodingKey {
        case id, place, visitedAt, overallRating, dimensions
        case publicReview, privateMemory, media, visibility, verificationStatus
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(UUID.self, forKey: .id)
        place = try values.decode(PlaceSummary.self, forKey: .place)
        visitedAt = try values.decode(String.self, forKey: .visitedAt)
        overallRating = try values.decode(Double.self, forKey: .overallRating)
        dimensions = try values.decodeIfPresent([VisitDimensionScore].self, forKey: .dimensions) ?? []
        publicReview = try values.decodeIfPresent(String.self, forKey: .publicReview) ?? ""
        privateMemory = try values.decodeIfPresent(String.self, forKey: .privateMemory) ?? ""
        media = try values.decodeIfPresent([VisitMediaDTO].self, forKey: .media) ?? []
        visibility = try values.decode(VisitVisibility.self, forKey: .visibility)
        verificationStatus = try values.decodeIfPresent(String.self, forKey: .verificationStatus)
    }
}

enum VisitDimensionCatalog {
    static func keys(for category: PlaceCategory) -> [String] {
        switch category {
        case .beach: ["SEA", "ATMOSPHERE", "SERVICE", "CLEANLINESS", "VALUE", "CROWD"]
        case .restaurant, .cafe: ["FOOD", "SERVICE", "ATMOSPHERE", "VALUE", "PRESENTATION"]
        case .hotel: ["CLEANLINESS", "LOCATION", "ROOM", "SERVICE", "BREAKFAST", "VALUE"]
        case .bar, .nightlife: ["DRINKS", "MUSIC", "ATMOSPHERE", "SERVICE", "VALUE"]
        case .attraction: ["EXPERIENCE", "ACCESS", "ATMOSPHERE", "VALUE"]
        case .activity: ["EXPERIENCE", "SAFETY", "GUIDE", "VALUE"]
        case .nature: ["SCENERY", "ACCESS", "CLEANLINESS", "TRANQUILITY"]
        case .unknown: []
        }
    }

    static func localizedName(for key: String) -> String {
        let localizationKey = "dimension.\(key.lowercased())"
        let localized = String(localized: String.LocalizationValue(localizationKey))
        return localized == localizationKey ? key : localized
    }
}
