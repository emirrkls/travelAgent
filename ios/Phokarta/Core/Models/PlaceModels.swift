import Foundation

/// Wire values match backend `PlaceCategory` (`BEACH`, `RESTAURANT`, …).
/// Unknown future values decode as `unknown` and are never sent as query params.
enum PlaceCategory: String, Codable, Sendable, CaseIterable, Hashable {
    case beach = "BEACH"
    case restaurant = "RESTAURANT"
    case cafe = "CAFE"
    case hotel = "HOTEL"
    case bar = "BAR"
    case nightlife = "NIGHTLIFE"
    case attraction = "ATTRACTION"
    case activity = "ACTIVITY"
    case nature = "NATURE"
    case unknown

    static var filterCases: [PlaceCategory] {
        allCases.filter { $0 != .unknown }
    }

    var wireValue: String? {
        self == .unknown ? nil : rawValue
    }

    var localizationKey: String {
        switch self {
        case .beach: "category.beach"
        case .restaurant: "category.restaurant"
        case .cafe: "category.cafe"
        case .hotel: "category.hotel"
        case .bar: "category.bar"
        case .nightlife: "category.nightlife"
        case .attraction: "category.attraction"
        case .activity: "category.activity"
        case .nature: "category.nature"
        case .unknown: "category.unknown"
        }
    }

    var localizedName: String {
        String(localized: String.LocalizationValue(localizationKey))
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let raw = try container.decode(String.self)
        self = PlaceCategory(rawValue: raw) ?? .unknown
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(wireValue ?? "UNKNOWN")
    }
}

enum ReviewScope: String, Sendable, Hashable {
    case community
    case friends
}

struct PageDTO<Item: Decodable & Sendable>: Decodable, Sendable {
    let content: [Item]
    let page: Int
    let size: Int
    let totalElements: Int64
    let totalPages: Int
    let hasNext: Bool
}

/// Community discovery summary. `communityScore` is backend `averageScore`
/// (PUBLIC Visit aggregate). Null means unrated — never coerced to 0.
struct PlaceSummary: Decodable, Equatable, Sendable, Identifiable {
    let id: UUID
    let name: String
    let category: PlaceCategory
    let coverImage: String
    let city: String
    let region: String
    let country: String
    let latitude: Double
    let longitude: Double
    let priceLevel: Int
    let communityScore: Double?
    let ratingCount: Int64

    enum CodingKeys: String, CodingKey {
        case id, name, category, coverImage, city, region, country
        case latitude, longitude, priceLevel, ratingCount
        case communityScore = "averageScore"
    }

    init(
        id: UUID,
        name: String,
        category: PlaceCategory,
        coverImage: String,
        city: String,
        region: String,
        country: String,
        latitude: Double,
        longitude: Double,
        priceLevel: Int,
        communityScore: Double?,
        ratingCount: Int64
    ) {
        self.id = id
        self.name = name
        self.category = category
        self.coverImage = coverImage
        self.city = city
        self.region = region
        self.country = country
        self.latitude = latitude
        self.longitude = longitude
        self.priceLevel = priceLevel
        self.communityScore = communityScore
        self.ratingCount = ratingCount
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(UUID.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        category = try container.decode(PlaceCategory.self, forKey: .category)
        coverImage = try container.decode(String.self, forKey: .coverImage)
        city = try container.decode(String.self, forKey: .city)
        region = try container.decode(String.self, forKey: .region)
        country = try container.decode(String.self, forKey: .country)
        latitude = try container.decode(Double.self, forKey: .latitude)
        longitude = try container.decode(Double.self, forKey: .longitude)
        priceLevel = try container.decode(Int.self, forKey: .priceLevel)
        communityScore = try container.decodeIfPresent(Double.self, forKey: .communityScore)
        ratingCount = try container.decode(Int64.self, forKey: .ratingCount)
    }
}

struct DimensionAggregate: Decodable, Equatable, Sendable, Identifiable {
    let key: String
    let average: Double

    var id: String { key }

    var localizationKey: String {
        "dimension.\(key.lowercased())"
    }

    var localizedName: String {
        let value = String(localized: String.LocalizationValue(localizationKey))
        return value == localizationKey ? key : value
    }

    init(key: String, average: Double) {
        self.key = key
        self.average = average
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        key = try container.decode(String.self, forKey: .key)
        average = try container.decode(Double.self, forKey: .average)
    }

    private enum CodingKeys: String, CodingKey {
        case key, average
    }
}

struct PlaceDetail: Decodable, Equatable, Sendable, Identifiable {
    let id: UUID
    let name: String
    let description: String
    let category: PlaceCategory
    let subcategories: [String]
    let latitude: Double
    let longitude: Double
    let city: String
    let region: String
    let country: String
    let address: String
    let coverImage: String
    let photos: [String]
    let priceLevel: Int
    let communityScore: Double?
    let ratingCount: Int64
    let dimensionScores: [DimensionAggregate]
    let recentPublicReviews: [ReviewSummary]

    enum CodingKeys: String, CodingKey {
        case id, name, description, category, subcategories
        case latitude, longitude, city, region, country, address
        case coverImage, photos, priceLevel, ratingCount, dimensionScores
        case recentPublicReviews
        case communityScore = "averageScore"
    }

    init(
        id: UUID,
        name: String,
        description: String,
        category: PlaceCategory,
        subcategories: [String],
        latitude: Double,
        longitude: Double,
        city: String,
        region: String,
        country: String,
        address: String,
        coverImage: String,
        photos: [String],
        priceLevel: Int,
        communityScore: Double?,
        ratingCount: Int64,
        dimensionScores: [DimensionAggregate],
        recentPublicReviews: [ReviewSummary]
    ) {
        self.id = id
        self.name = name
        self.description = description
        self.category = category
        self.subcategories = subcategories
        self.latitude = latitude
        self.longitude = longitude
        self.city = city
        self.region = region
        self.country = country
        self.address = address
        self.coverImage = coverImage
        self.photos = photos
        self.priceLevel = priceLevel
        self.communityScore = communityScore
        self.ratingCount = ratingCount
        self.dimensionScores = dimensionScores
        self.recentPublicReviews = recentPublicReviews
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(UUID.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        description = try container.decode(String.self, forKey: .description)
        category = try container.decode(PlaceCategory.self, forKey: .category)
        subcategories = try container.decodeIfPresent([String].self, forKey: .subcategories) ?? []
        latitude = try container.decode(Double.self, forKey: .latitude)
        longitude = try container.decode(Double.self, forKey: .longitude)
        city = try container.decode(String.self, forKey: .city)
        region = try container.decode(String.self, forKey: .region)
        country = try container.decode(String.self, forKey: .country)
        address = try container.decodeIfPresent(String.self, forKey: .address) ?? ""
        coverImage = try container.decodeIfPresent(String.self, forKey: .coverImage) ?? ""
        photos = try container.decodeIfPresent([String].self, forKey: .photos) ?? []
        priceLevel = try container.decode(Int.self, forKey: .priceLevel)
        communityScore = try container.decodeIfPresent(Double.self, forKey: .communityScore)
        ratingCount = try container.decodeIfPresent(Int64.self, forKey: .ratingCount) ?? 0
        dimensionScores = try container.decodeIfPresent([DimensionAggregate].self, forKey: .dimensionScores) ?? []
        recentPublicReviews = try container.decodeIfPresent([ReviewSummary].self, forKey: .recentPublicReviews) ?? []
    }
}

/// Public review row. Backend `PublicVisitResponse` never includes `privateMemory`.
struct ReviewSummary: Decodable, Equatable, Sendable, Identifiable {
    let id: UUID
    let placeId: UUID
    let placeName: String
    let userId: UUID
    let username: String
    let displayName: String
    let avatarUrl: String?
    let visitedAt: String
    let overallRating: Double
    let publicReview: String
    let photos: [String]
    let verificationStatus: String?

    enum CodingKeys: String, CodingKey {
        case id, placeId, placeName, userId, username, displayName, avatarUrl
        case visitedAt, overallRating, publicReview, photos, verificationStatus
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(UUID.self, forKey: .id)
        placeId = try container.decode(UUID.self, forKey: .placeId)
        placeName = try container.decode(String.self, forKey: .placeName)
        userId = try container.decode(UUID.self, forKey: .userId)
        username = try container.decode(String.self, forKey: .username)
        displayName = try container.decode(String.self, forKey: .displayName)
        avatarUrl = try container.decodeIfPresent(String.self, forKey: .avatarUrl)
        visitedAt = try container.decode(String.self, forKey: .visitedAt)
        overallRating = try container.decode(Double.self, forKey: .overallRating)
        publicReview = try container.decodeIfPresent(String.self, forKey: .publicReview) ?? ""
        photos = try container.decodeIfPresent([String].self, forKey: .photos) ?? []
        verificationStatus = try container.decodeIfPresent(String.self, forKey: .verificationStatus)
    }

    init(
        id: UUID,
        placeId: UUID,
        placeName: String,
        userId: UUID,
        username: String,
        displayName: String,
        avatarUrl: String?,
        visitedAt: String,
        overallRating: Double,
        publicReview: String,
        photos: [String] = [],
        verificationStatus: String? = nil
    ) {
        self.id = id
        self.placeId = placeId
        self.placeName = placeName
        self.userId = userId
        self.username = username
        self.displayName = displayName
        self.avatarUrl = avatarUrl
        self.visitedAt = visitedAt
        self.overallRating = overallRating
        self.publicReview = publicReview
        self.photos = photos
        self.verificationStatus = verificationStatus
    }
}

struct FriendPreview: Decodable, Equatable, Sendable, Identifiable {
    let userId: UUID
    let displayName: String
    let avatarUrl: String?
    let latestScore: Double
    let latestVisitedAt: String

    var id: UUID { userId }
}

struct FriendPlaceSummary: Decodable, Equatable, Sendable {
    let averageScore: Double?
    let friendsVisitedCount: Int64
    let friends: [FriendPreview]

    init(averageScore: Double?, friendsVisitedCount: Int64, friends: [FriendPreview]) {
        self.averageScore = averageScore
        self.friendsVisitedCount = friendsVisitedCount
        self.friends = friends
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        averageScore = try container.decodeIfPresent(Double.self, forKey: .averageScore)
        friendsVisitedCount = try container.decodeIfPresent(Int64.self, forKey: .friendsVisitedCount) ?? 0
        friends = try container.decodeIfPresent([FriendPreview].self, forKey: .friends) ?? []
    }

    private enum CodingKeys: String, CodingKey {
        case averageScore, friendsVisitedCount, friends
    }
}

struct FriendPlaceMetrics: Decodable, Equatable, Sendable, Identifiable {
    let placeId: UUID
    let friendAverageScore: Double?
    let friendsVisitedCount: Int64

    var id: UUID { placeId }

    init(placeId: UUID, friendAverageScore: Double?, friendsVisitedCount: Int64) {
        self.placeId = placeId
        self.friendAverageScore = friendAverageScore
        self.friendsVisitedCount = friendsVisitedCount
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        placeId = try container.decode(UUID.self, forKey: .placeId)
        friendAverageScore = try container.decodeIfPresent(Double.self, forKey: .friendAverageScore)
        friendsVisitedCount = try container.decodeIfPresent(Int64.self, forKey: .friendsVisitedCount) ?? 0
    }

    private enum CodingKeys: String, CodingKey {
        case placeId, friendAverageScore, friendsVisitedCount
    }
}

struct FriendMetricsRequestDTO: Encodable, Sendable {
    let placeIds: [UUID]
}

struct SavedPlaceDTO: Decodable, Sendable {
    let place: PlaceSummary
    let savedAt: String
    let friendAverageScore: Double?
    let friendsVisitedCount: Int64

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        place = try container.decode(PlaceSummary.self, forKey: .place)
        savedAt = try container.decodeIfPresent(String.self, forKey: .savedAt) ?? ""
        friendAverageScore = try container.decodeIfPresent(Double.self, forKey: .friendAverageScore)
        friendsVisitedCount = try container.decodeIfPresent(Int64.self, forKey: .friendsVisitedCount) ?? 0
    }

    private enum CodingKeys: String, CodingKey {
        case place, savedAt, friendAverageScore, friendsVisitedCount
    }
}

/// Owner visit used only for current-user visited/personal score.
/// `privateMemory` is intentionally not decoded.
struct OwnerVisitDTO: Decodable, Sendable {
    let id: UUID
    let place: PlaceSummary
    let visitedAt: String
    let overallRating: Double
}

struct PlacePage: Equatable, Sendable {
    let places: [PlaceSummary]
    let page: Int
    let totalPages: Int
    let totalElements: Int64
    let hasNext: Bool
}

struct ReviewPage: Equatable, Sendable {
    let reviews: [ReviewSummary]
    let page: Int
    let totalElements: Int64
    let hasNext: Bool
}

struct OwnerVisitSummary: Equatable, Sendable, Identifiable {
    let id: UUID
    let placeId: UUID
    let visitedAt: String
    let overallRating: Double
}

enum PlaceImageURL {
    /// Catalog images are backend `coverImage`/`photos` strings.
    /// Release never loads `http:`; Debug may for local fixtures.
    static func displayURL(from raw: String?) -> URL? {
        guard let raw, !raw.isEmpty, let url = URL(string: raw) else { return nil }
        let scheme = url.scheme?.lowercased()
        if scheme == "https" { return url }
        #if DEBUG
        if scheme == "http" { return url }
        #endif
        return nil
    }
}

enum PlaceDateFormatting {
    static func mediumDate(from isoDate: String) -> String {
        let parser = DateFormatter()
        parser.calendar = Calendar(identifier: .iso8601)
        parser.locale = Locale(identifier: "en_US_POSIX")
        parser.timeZone = TimeZone(secondsFromGMT: 0)
        parser.dateFormat = "yyyy-MM-dd"
        guard let date = parser.date(from: String(isoDate.prefix(10))) else {
            return isoDate
        }
        let display = DateFormatter()
        display.dateStyle = .medium
        display.timeStyle = .none
        return display.string(from: date)
    }
}

enum ScoreFormatting {
    static func display(_ score: Double) -> String {
        let formatter = NumberFormatter()
        formatter.locale = .current
        formatter.numberStyle = .decimal
        formatter.minimumFractionDigits = 1
        formatter.maximumFractionDigits = 1
        return formatter.string(from: NSNumber(value: score)) ?? String(format: "%.1f", score)
    }
}
