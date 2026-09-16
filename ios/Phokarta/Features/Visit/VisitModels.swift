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

struct ExperienceV2CreateRequest: Encodable, Equatable, Sendable {
    let clientMutationId: UUID
    let placeId: UUID
    let visitDate: String
    let primaryExperienceCode: PrimaryExperienceCode
    let rawExperienceLabel: String?
    let overallFeelingCode: OverallFeelingCode
    let companionCode: CompanionCode?
    let timeOfDayCode: TimeOfDayCode?
    let vibeCodes: [VibeCode]
    let practicalSignalCodes: [PracticalSignalCode]
    let dimensions: [ExperienceV2CreateDimension]
    let title: String?
    let titleSource: ExperienceTitleSource
    let story: String?
    let tip: String?
    let privateMemory: String?
    let visibility: VisitVisibility
    let mediaIds: [UUID]
}

struct ExperienceV2CreateDimension: Encodable, Equatable, Sendable {
    let key: String
    let semanticStateCode: DimensionStateCode
    let templateVersion: Int
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

    static func title(for key: String) -> String {
        localizedName(for: key)
    }
}

enum ExperienceDimensionCatalog {
    static func keys(for primary: PrimaryExperienceCode?) -> [String] {
        guard let primary else { return [] }
        switch primary {
        case .kahvalti, .ogunYemek, .kahve, .tatli, .sokakLezzeti, .yerelLezzet:
            ["FOOD", "SERVICE", "ATMOSPHERE", "VALUE"]
        case .gunBatimi, .gunDogumu, .manzara, .geceManzarasi, .fotografNoktasi:
            ["SCENERY", "ATMOSPHERE", "TRANQUILITY", "ACCESS"]
        case .denizYuzme, .plaj, .tekne, .dalisSnorkel, .suAktivitesi:
            ["SEA", "CLEANLINESS", "COMFORT", "ACCESS"]
        case .dogaYuruyusu, .piknik, .kamp, .orman, .golSelale, .seyirNoktasi:
            ["SCENERY", "ROUTE", "TRANQUILITY", "ACCESS"]
        case .sokakKesfi, .mahalleSehirGezisi, .sahilYuruyusu, .gizliKose, .rotaGezi:
            ["ATMOSPHERE", "WALKABILITY", "LOCALITY", "DISCOVERY_VALUE"]
        case .muze, .tarihiYer, .mimari, .yerelPazar, .yerelYasam, .sergiSanat:
            ["CONTENT_INTEREST", "ATMOSPHERE", "ACCESS", "VALUE"]
        case .canliMuzik, .barPub, .geceHayati, .konserGosteri, .sosyalEtkinlik:
            ["ATMOSPHERE", "MUSIC_ENTERTAINMENT", "SERVICE", "VALUE"]
        case .bisiklet, .tirmanis, .kayak, .suSporu, .workshop, .acikHavaAktivitesi:
            ["FUN", "ORGANIZATION", "COMFORT_DIFFICULTY", "VALUE"]
        case .sakinZaman, .spaHamam, .termal, .yogaMeditasyon, .dinlenme:
            ["ATMOSPHERE", "COMFORT", "CLEANLINESS", "VALUE"]
        case .otel, .butikOtel, .hostel, .kampKonaklamasi, .kiralikEvBungalov:
            ["CLEANLINESS", "COMFORT", "LOCATION", "SERVICE"]
        default: [String]()
        }
    }
}

// MARK: - Experience V2 read foundation

protocol ExperienceWireCode: RawRepresentable, Codable, Sendable where RawValue == String {
    static var unknown: Self { get }
}

extension ExperienceWireCode {
    init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = Self(rawValue: raw) ?? Self.unknown
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(rawValue)
    }
}

enum ExperienceClassification: String, ExperienceWireCode, Equatable {
    case legacyCompatibility = "LEGACY_COMPATIBILITY"
    case nativeV2 = "NATIVE_V2"
    case unknown = "UNKNOWN"
}

enum OverallFeelingCode: String, ExperienceWireCode, Equatable, CaseIterable {
    case bayildim = "BAYILDIM"
    case guzeldi = "GUZELDI"
    case ehIste = "EH_ISTE"
    case beklentimiKarsilamadi = "BEKLENTIMI_KARSILAMADI"
    case birDahaTercihEtmem = "BIR_DAHA_TERCIH_ETMEM"
    case unknown = "UNKNOWN"
}

enum FeelingProvenance: String, ExperienceWireCode, Equatable {
    case explicit = "EXPLICIT"
    case derivedLegacy = "DERIVED_LEGACY"
    case unknown = "UNKNOWN"
}

enum DimensionStateCode: String, ExperienceWireCode, Equatable, CaseIterable {
    case veryGood = "VERY_GOOD"
    case good = "GOOD"
    case medium = "MEDIUM"
    case weak = "WEAK"
    case veryWeak = "VERY_WEAK"
    case unknown = "UNKNOWN"

    var compatibilityScore: Int? {
        switch self {
        case .veryGood: 10
        case .good: 8
        case .medium: 6
        case .weak: 4
        case .veryWeak: 2
        case .unknown: nil
        }
    }
}

enum ExperienceFamilyCode: String, ExperienceWireCode, Equatable, CaseIterable {
    case foodAndDrink = "FOOD_AND_DRINK"
    case sceneryAndMoment = "SCENERY_AND_MOMENT"
    case seaAndWater = "SEA_AND_WATER"
    case natureAndOutdoor = "NATURE_AND_OUTDOOR"
    case travelAndDiscovery = "TRAVEL_AND_DISCOVERY"
    case cultureAndLocalLife = "CULTURE_AND_LOCAL_LIFE"
    case entertainmentAndNightlife = "ENTERTAINMENT_AND_NIGHTLIFE"
    case activityAndAdventure = "ACTIVITY_AND_ADVENTURE"
    case restAndWellness = "REST_AND_WELLNESS"
    case accommodation = "ACCOMMODATION"
    case unknown = "UNKNOWN"
}

enum PrimaryExperienceCode: String, ExperienceWireCode, Equatable, CaseIterable {
    case kahvalti = "KAHVALTI", ogunYemek = "OGUN_YEMEK", kahve = "KAHVE", tatli = "TATLI"
    case sokakLezzeti = "SOKAK_LEZZETI", yerelLezzet = "YEREL_LEZZET"
    case gunBatimi = "GUN_BATIMI", gunDogumu = "GUN_DOGUMU", manzara = "MANZARA"
    case geceManzarasi = "GECE_MANZARASI", fotografNoktasi = "FOTOGRAF_NOKTASI"
    case denizYuzme = "DENIZ_YUZME", plaj = "PLAJ", tekne = "TEKNE"
    case dalisSnorkel = "DALIS_SNORKEL", suAktivitesi = "SU_AKTIVITESI"
    case dogaYuruyusu = "DOGA_YURUYUSU", piknik = "PIKNIK", kamp = "KAMP", orman = "ORMAN"
    case golSelale = "GOL_SELALE", seyirNoktasi = "SEYIR_NOKTASI"
    case sokakKesfi = "SOKAK_KESFI", mahalleSehirGezisi = "MAHALLE_SEHIR_GEZISI"
    case sahilYuruyusu = "SAHIL_YURUYUSU", gizliKose = "GIZLI_KOSE", rotaGezi = "ROTA_GEZI"
    case muze = "MUZE", tarihiYer = "TARIHI_YER", mimari = "MIMARI", yerelPazar = "YEREL_PAZAR"
    case yerelYasam = "YEREL_YASAM", sergiSanat = "SERGI_SANAT"
    case canliMuzik = "CANLI_MUZIK", barPub = "BAR_PUB", geceHayati = "GECE_HAYATI"
    case konserGosteri = "KONSER_GOSTERI", sosyalEtkinlik = "SOSYAL_ETKINLIK"
    case bisiklet = "BISIKLET", tirmanis = "TIRMANIS", kayak = "KAYAK", suSporu = "SU_SPORU"
    case workshop = "WORKSHOP", acikHavaAktivitesi = "ACIK_HAVA_AKTIVITESI"
    case sakinZaman = "SAKIN_ZAMAN", spaHamam = "SPA_HAMAM", termal = "TERMAL"
    case yogaMeditasyon = "YOGA_MEDITASYON", dinlenme = "DINLENME"
    case otel = "OTEL", butikOtel = "BUTIK_OTEL", hostel = "HOSTEL"
    case kampKonaklamasi = "KAMP_KONAKLAMASI", kiralikEvBungalov = "KIRALIK_EV_BUNGALOV"
    case other = "OTHER", unknownLegacy = "UNKNOWN_LEGACY", unknown = "UNKNOWN"
}

enum CompanionCode: String, ExperienceWireCode, Equatable, CaseIterable {
    case alone = "ALONE", partner = "PARTNER", friends = "FRIENDS", family = "FAMILY"
    case children = "CHILDREN", unknown = "UNKNOWN"
}

enum TimeOfDayCode: String, ExperienceWireCode, Equatable, CaseIterable {
    case morning = "MORNING", daytime = "DAYTIME", evening = "EVENING", night = "NIGHT"
    case unknown = "UNKNOWN"
}

enum VibeCode: String, ExperienceWireCode, Equatable, Hashable, CaseIterable {
    case calm = "CALM", lively = "LIVELY", romantic = "ROMANTIC", social = "SOCIAL"
    case intimate = "INTIMATE", localAuthentic = "LOCAL_AUTHENTIC", scenic = "SCENIC"
    case adventurous = "ADVENTUROUS", unknown = "UNKNOWN"
}

enum PracticalSignalCode: String, ExperienceWireCode, Equatable, Hashable, CaseIterable {
    case accessibleWithoutCar = "ACCESSIBLE_WITHOUT_CAR", carRecommended = "CAR_RECOMMENDED"
    case parkingDifficult = "PARKING_DIFFICULT", reservationRecommended = "RESERVATION_RECOMMENDED"
    case noReservationNeeded = "NO_RESERVATION_NEEDED", weekendsCrowded = "WEEKENDS_CROWDED"
    case mayBeCrowded = "MAY_BE_CROWDED", calmerInMorning = "CALMER_IN_MORNING"
    case idealForSunset = "IDEAL_FOR_SUNSET", suitableWithChildren = "SUITABLE_WITH_CHILDREN"
    case petFriendly = "PET_FRIENDLY", walkingRequired = "WALKING_REQUIRED"
    case arriveEarly = "ARRIVE_EARLY", cashMayBeNeeded = "CASH_MAY_BE_NEEDED"
    case free = "FREE", quietAreaAvailable = "QUIET_AREA_AVAILABLE", unknown = "UNKNOWN"
}

enum ExperienceTitleSource: String, ExperienceWireCode, Equatable, CaseIterable {
    case generated = "GENERATED", custom = "CUSTOM", unknown = "UNKNOWN"
}

enum ExperienceMediaKind: String, ExperienceWireCode, Equatable {
    case legacyURL = "LEGACY_URL", managed = "MANAGED", unknown = "UNKNOWN"
}

struct ExperienceV2: Decodable, Equatable, Sendable, Identifiable {
    let id: UUID
    let classification: ExperienceClassification
    let author: Author
    let place: Place
    let experiencedAt: String
    let title: String
    let titleSource: ExperienceTitleSource?
    let titlePersisted: Bool
    let story: String
    let tip: String?
    let feeling: Feeling
    let primaryExperience: Primary
    let companion: CompanionCode?
    let timeOfDay: TimeOfDayCode?
    let vibes: [VibeCode]
    let practicalSignals: [PracticalSignalCode]
    let dimensions: [Dimension]
    let media: [Media]
    let visibility: VisitVisibility
    let taxonomyVersion: Int?

    struct Author: Decodable, Equatable, Sendable {
        let id: UUID
        let username: String
        let displayName: String
        let avatarUrl: String?
    }

    struct Place: Decodable, Equatable, Sendable {
        let id: UUID
        let name: String
        let category: PlaceCategory
        let city: String
        let region: String
        let country: String
        let coverImage: String
    }

    struct Feeling: Decodable, Equatable, Sendable {
        let code: OverallFeelingCode
        let source: FeelingProvenance
        let compatibilityNumericRating: Double
    }

    struct Primary: Decodable, Equatable, Sendable {
        let code: PrimaryExperienceCode
        let canonical: Bool
        let family: ExperienceFamilyCode?
        let rawLabel: String?
    }

    struct Dimension: Decodable, Equatable, Sendable {
        let key: String
        let numericScore: Double
        let semanticState: DimensionStateCode?
        let templateVersion: Int?
    }

    struct Media: Decodable, Equatable, Sendable {
        let kind: ExperienceMediaKind
        let position: Int
        let id: UUID?
        let url: String
        let accessExpiresAt: String?
    }
}
