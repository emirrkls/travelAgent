package com.emirrkls.phokarta.core.model

import java.time.LocalDate

private inline fun <reified T : Enum<T>> decodeCode(raw: String): T =
    enumValues<T>().firstOrNull { it.name == raw } ?: enumValues<T>().first { it.name == "UNKNOWN" }

enum class ExperienceClassification { LEGACY_COMPATIBILITY, NATIVE_V2, UNKNOWN;
    companion object { fun fromWire(raw: String) = decodeCode<ExperienceClassification>(raw) }
}
enum class OverallFeelingCode { BAYILDIM, GUZELDI, EH_ISTE, BEKLENTIMI_KARSILAMADI, BIR_DAHA_TERCIH_ETMEM, UNKNOWN;
    companion object { fun fromWire(raw: String) = decodeCode<OverallFeelingCode>(raw) }
}
enum class FeelingProvenance { EXPLICIT, DERIVED_LEGACY, UNKNOWN;
    companion object { fun fromWire(raw: String) = decodeCode<FeelingProvenance>(raw) }
}
enum class DimensionStateCode(val compatibilityScore: Int?) {
    VERY_GOOD(10), GOOD(8), MEDIUM(6), WEAK(4), VERY_WEAK(2), UNKNOWN(null);
    companion object { fun fromWire(raw: String) = decodeCode<DimensionStateCode>(raw) }
}
enum class ExperienceFamily {
    FOOD_AND_DRINK, SCENERY_AND_MOMENT, SEA_AND_WATER, NATURE_AND_OUTDOOR,
    TRAVEL_AND_DISCOVERY, CULTURE_AND_LOCAL_LIFE, ENTERTAINMENT_AND_NIGHTLIFE,
    ACTIVITY_AND_ADVENTURE, REST_AND_WELLNESS, ACCOMMODATION, UNKNOWN;
    companion object { fun fromWire(raw: String) = decodeCode<ExperienceFamily>(raw) }
}
enum class PrimaryExperienceCode {
    KAHVALTI, OGUN_YEMEK, KAHVE, TATLI, SOKAK_LEZZETI, YEREL_LEZZET,
    GUN_BATIMI, GUN_DOGUMU, MANZARA, GECE_MANZARASI, FOTOGRAF_NOKTASI,
    DENIZ_YUZME, PLAJ, TEKNE, DALIS_SNORKEL, SU_AKTIVITESI,
    DOGA_YURUYUSU, PIKNIK, KAMP, ORMAN, GOL_SELALE, SEYIR_NOKTASI,
    SOKAK_KESFI, MAHALLE_SEHIR_GEZISI, SAHIL_YURUYUSU, GIZLI_KOSE, ROTA_GEZI,
    MUZE, TARIHI_YER, MIMARI, YEREL_PAZAR, YEREL_YASAM, SERGI_SANAT,
    CANLI_MUZIK, BAR_PUB, GECE_HAYATI, KONSER_GOSTERI, SOSYAL_ETKINLIK,
    BISIKLET, TIRMANIS, KAYAK, SU_SPORU, WORKSHOP, ACIK_HAVA_AKTIVITESI,
    SAKIN_ZAMAN, SPA_HAMAM, TERMAL, YOGA_MEDITASYON, DINLENME,
    OTEL, BUTIK_OTEL, HOSTEL, KAMP_KONAKLAMASI, KIRALIK_EV_BUNGALOV,
    OTHER, UNKNOWN_LEGACY, UNKNOWN;
    companion object { fun fromWire(raw: String) = decodeCode<PrimaryExperienceCode>(raw) }
}
enum class CompanionCode { ALONE, PARTNER, FRIENDS, FAMILY, CHILDREN, UNKNOWN;
    companion object { fun fromWire(raw: String) = decodeCode<CompanionCode>(raw) }
}
enum class TimeOfDayCode { MORNING, DAYTIME, EVENING, NIGHT, UNKNOWN;
    companion object { fun fromWire(raw: String) = decodeCode<TimeOfDayCode>(raw) }
}
enum class VibeCode { CALM, LIVELY, ROMANTIC, SOCIAL, INTIMATE, LOCAL_AUTHENTIC, SCENIC, ADVENTUROUS, UNKNOWN;
    companion object { fun fromWire(raw: String) = decodeCode<VibeCode>(raw) }
}
enum class PracticalSignalCode {
    ACCESSIBLE_WITHOUT_CAR, CAR_RECOMMENDED, PARKING_DIFFICULT, RESERVATION_RECOMMENDED,
    NO_RESERVATION_NEEDED, WEEKENDS_CROWDED, MAY_BE_CROWDED, CALMER_IN_MORNING,
    IDEAL_FOR_SUNSET, SUITABLE_WITH_CHILDREN, PET_FRIENDLY, WALKING_REQUIRED,
    ARRIVE_EARLY, CASH_MAY_BE_NEEDED, FREE, QUIET_AREA_AVAILABLE, UNKNOWN;
    companion object { fun fromWire(raw: String) = decodeCode<PracticalSignalCode>(raw) }
}
enum class ExperienceTitleSource { GENERATED, CUSTOM, UNKNOWN;
    companion object { fun fromWire(raw: String) = decodeCode<ExperienceTitleSource>(raw) }
}
enum class ExperienceMediaKind { LEGACY_URL, MANAGED, UNKNOWN;
    companion object { fun fromWire(raw: String) = decodeCode<ExperienceMediaKind>(raw) }
}
enum class ExperienceVisibility { PRIVATE, FRIENDS, PUBLIC, UNKNOWN;
    companion object { fun fromWire(raw: String) = decodeCode<ExperienceVisibility>(raw) }
}

enum class ExperienceFeedLens { FOR_YOU, FOLLOWING, NEARBY, POPULAR }

data class ExperiencePage(
    val items: List<ExperienceSummary>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

data class ExperienceSummary(
    val id: String,
    val classification: ExperienceClassification,
    val author: ExperienceAuthor,
    val place: ExperiencePlace,
    val experiencedAt: LocalDate,
    val title: String,
    val titleSource: ExperienceTitleSource?,
    val primaryExperience: ExperiencePrimary,
    val feeling: OverallFeelingCode,
    val storyPreview: String?,
    val tipPreview: String?,
    val companion: CompanionCode?,
    val timeOfDay: TimeOfDayCode?,
    val vibes: List<VibeCode>,
    val practicalSignals: List<PracticalSignalCode>,
    val mediaPreview: ExperienceMedia?,
    val mediaCount: Int,
    val visibility: ExperienceVisibility,
    val plannedByViewer: Boolean = false,
    val acknowledgedByViewer: Boolean = false,
    val acknowledgementCount: Long = 0,
)

data class Experience(
    val id: String,
    val classification: ExperienceClassification,
    val author: ExperienceAuthor,
    val place: ExperiencePlace,
    val experiencedAt: LocalDate,
    val title: String,
    val titleSource: ExperienceTitleSource?,
    val titlePersisted: Boolean,
    val story: String,
    val tip: String?,
    val feeling: ExperienceFeeling,
    val primaryExperience: ExperiencePrimary,
    val companion: CompanionCode?,
    val timeOfDay: TimeOfDayCode?,
    val vibes: List<VibeCode>,
    val practicalSignals: List<PracticalSignalCode>,
    val dimensions: List<ExperienceDimension>,
    val media: List<ExperienceMedia>,
    val visibility: ExperienceVisibility,
    val taxonomyVersion: Int?,
    val plannedByViewer: Boolean = false,
    val acknowledgedByViewer: Boolean = false,
    val acknowledgementCount: Long = 0,
)

data class ExperienceAcknowledgement(
    val id: String,
    val ownerUserId: String,
    val sourceExperienceId: String?,
    val sourceAvailable: Boolean,
    val sourceExperience: Experience?,
    val place: ExperiencePlace,
    val primaryExperience: PrimaryExperienceCode,
    val rawExperienceLabel: String?,
    val acknowledgedAt: String,
    val converted: Boolean,
    val convertedExperienceId: String?,
)

data class PlannedExperienceItem(
    val experienceId: String,
    val title: String,
    val placeId: String,
    val placeName: String,
    val primaryExperience: PrimaryExperienceCode,
    val feeling: OverallFeelingCode,
    val authorName: String,
    val imageUrl: String?,
)

data class AcknowledgementAnchor(
    val id: String,
    val sourceExperienceId: String?,
    val sourceAvailable: Boolean,
    val placeId: String,
    val placeName: String,
    val placeCity: String,
    val primaryExperience: PrimaryExperienceCode,
    val rawExperienceLabel: String?,
    val acknowledgedAtEpochMillis: Long,
)

data class ExperienceAuthor(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val relationship: RelationshipV2? = null,
)
data class ExperiencePlace(
    val id: String,
    val name: String,
    val categoryCode: String,
    val city: String,
    val region: String,
    val country: String,
    val coverImage: String,
    val distanceMeters: Double? = null,
)
data class ExperienceFeeling(
    val code: OverallFeelingCode,
    val provenance: FeelingProvenance,
    val compatibilityNumericRating: Double,
)
data class ExperiencePrimary(
    val code: PrimaryExperienceCode,
    val canonical: Boolean,
    val family: ExperienceFamily?,
    val rawLabel: String?,
)
data class ExperienceDimension(
    val key: String,
    val numericScore: Double,
    val semanticState: DimensionStateCode?,
    val templateVersion: Int?,
)
data class ExperienceMedia(
    val kind: ExperienceMediaKind,
    val position: Int,
    val id: String?,
    val url: String,
    val accessExpiresAt: String?,
)
