package com.emirrkls.phokarta.feature.rating

import androidx.annotation.StringRes
import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.core.model.CompanionCode
import com.emirrkls.phokarta.core.model.DimensionStateCode
import com.emirrkls.phokarta.core.model.ExperienceTitleSource
import com.emirrkls.phokarta.core.model.OverallFeelingCode
import com.emirrkls.phokarta.core.model.PracticalSignalCode
import com.emirrkls.phokarta.core.model.PrimaryExperienceCode
import com.emirrkls.phokarta.core.model.TimeOfDayCode
import com.emirrkls.phokarta.core.model.VibeCode
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.ui.localization.ScoreBand
import com.emirrkls.phokarta.ui.localization.impactHintRes
import com.emirrkls.phokarta.ui.localization.labelRes
import com.emirrkls.phokarta.ui.localization.reviewHelperRes
import com.emirrkls.phokarta.ui.localization.scoreBandFor
import com.emirrkls.phokarta.ui.localization.sheetDescriptionRes
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt

data class VisitDraft(
    /** Existing on-disk rows migrate as 1; all new composer drafts are native V2. */
    val payloadVersion: Int = 2,
    val overallScore: Float = 8f,
    val dimensions: Map<RatingDimension, Float> = emptyMap(),
    val publicReview: String = "",
    val privateMemory: String = "",
    val visitDate: LocalDate = LocalDate.now(),
    val visibility: Visibility = Visibility.PUBLIC,
    val dimensionsExpanded: Boolean = false,
    /** App-private relative paths. Metadata and remote identity live in Room media rows. */
    val photos: List<String> = emptyList(),
    val primaryExperience: PrimaryExperienceCode? = null,
    val rawExperienceLabel: String? = null,
    val overallFeeling: OverallFeelingCode? = null,
    val semanticDimensions: Map<String, DimensionStateCode> = emptyMap(),
    val companion: CompanionCode? = null,
    val timeOfDay: TimeOfDayCode? = null,
    val vibes: Set<VibeCode> = emptySet(),
    val practicalSignals: Set<PracticalSignalCode> = emptySet(),
    val title: String? = null,
    val titleSource: ExperienceTitleSource = ExperienceTitleSource.GENERATED,
    val story: String = "",
    val tip: String = "",
)

/**
 * User-facing copy for visit visibility — resource IDs only.
 * PUBLIC — community + mutual friends. FRIENDS — mutual friends only.
 * PRIVATE — owner only. Backend is authoritative for audience.
 */
object VisitVisibilityCopy {
    val selectionOrder: List<Visibility> = listOf(
        Visibility.PUBLIC,
        Visibility.FRIENDS,
        Visibility.PRIVATE,
    )

    @StringRes fun labelRes(visibility: Visibility): Int = visibility.labelRes()
    @StringRes fun sheetDescriptionRes(visibility: Visibility): Int = visibility.sheetDescriptionRes()
    @StringRes fun reviewHelperRes(visibility: Visibility): Int = visibility.reviewHelperRes()
    @StringRes fun impactHintRes(visibility: Visibility): Int = visibility.impactHintRes()
}

object VisitDraftLogic {
    fun scoreBand(score: Float): ScoreBand = scoreBandFor(score)

    @StringRes
    fun validateDateRes(date: LocalDate, today: LocalDate = LocalDate.now()): Int? =
        if (date.isAfter(today)) R.string.visit_date_future_error else null

    fun canPublish(draft: VisitDraft, today: LocalDate = LocalDate.now()): Boolean {
        if (validateDateRes(draft.visitDate, today) != null) return false
        if (draft.payloadVersion == 1) return draft.overallScore in 0f..10f
        val primary = draft.primaryExperience ?: return false
        if (primary == PrimaryExperienceCode.UNKNOWN || primary == PrimaryExperienceCode.UNKNOWN_LEGACY) return false
        if (primary == PrimaryExperienceCode.OTHER && draft.rawExperienceLabel.isNullOrBlank()) return false
        if (primary != PrimaryExperienceCode.OTHER && !draft.rawExperienceLabel.isNullOrBlank()) return false
        val feeling = draft.overallFeeling ?: return false
        if (feeling == OverallFeelingCode.UNKNOWN) return false
        if (draft.vibes.size > 2 || draft.photos.size > V2_MAX_MEDIA) return false
        if (VibeCode.UNKNOWN in draft.vibes || PracticalSignalCode.UNKNOWN in draft.practicalSignals) return false
        if (draft.companion == CompanionCode.UNKNOWN || draft.timeOfDay == TimeOfDayCode.UNKNOWN) return false
        val allowedDimensions = ExperienceDimensionCatalog.keysFor(primary).toSet()
        if (draft.semanticDimensions.any { (key, state) ->
                key !in allowedDimensions || state == DimensionStateCode.UNKNOWN
            }) return false
        if (draft.titleSource == ExperienceTitleSource.CUSTOM && draft.title.isNullOrBlank()) return false
        if (draft.titleSource == ExperienceTitleSource.GENERATED && !draft.title.isNullOrBlank()) return false
        return draft.photos.isNotEmpty() || draft.story.isNotBlank() || draft.tip.isNotBlank()
    }

    /**
     * Default-only drafts must not be persisted (avoids a draft for every Place open).
     * [dimensionsExpanded] alone is UI chrome and does not count as meaningful.
     */
    fun hasMeaningfulContent(draft: VisitDraft, today: LocalDate = LocalDate.now()): Boolean {
        val defaults = VisitDraft(visitDate = today)
        return draft.overallScore != defaults.overallScore ||
            draft.dimensions.isNotEmpty() ||
            draft.publicReview.isNotBlank() ||
            draft.privateMemory.isNotBlank() ||
            draft.visitDate != today ||
            draft.visibility != Visibility.PUBLIC ||
            draft.photos.isNotEmpty() ||
            draft.primaryExperience != null ||
            draft.rawExperienceLabel != null ||
            draft.overallFeeling != null ||
            draft.semanticDimensions.isNotEmpty() ||
            draft.companion != null ||
            draft.timeOfDay != null ||
            draft.vibes.isNotEmpty() ||
            draft.practicalSignals.isNotEmpty() ||
            draft.title != null ||
            draft.story.isNotBlank() ||
            draft.tip.isNotBlank()
    }

    fun toVisit(
        draft: VisitDraft,
        placeId: String,
        userId: String,
        visitId: String = UUID.randomUUID().toString(),
    ): Visit = Visit(
        id = visitId,
        userId = userId,
        placeId = placeId,
        visitedAt = draft.visitDate,
        overallRating = draft.overallScore.roundToTenth().toDouble(),
        ratingDimensions = draft.dimensions.mapValues { it.value.roundToTenth().toDouble() },
        review = draft.publicReview.trim(),
        personalNote = draft.privateMemory.trim(),
        photos = draft.photos,
        visibility = draft.visibility,
    )

    const val V2_MAX_MEDIA = 6
}

object ExperienceDimensionCatalog {
    fun keysFor(primary: PrimaryExperienceCode?): List<String> = when (primary) {
        PrimaryExperienceCode.KAHVALTI,
        PrimaryExperienceCode.OGUN_YEMEK,
        PrimaryExperienceCode.KAHVE,
        PrimaryExperienceCode.TATLI,
        PrimaryExperienceCode.SOKAK_LEZZETI,
        PrimaryExperienceCode.YEREL_LEZZET -> listOf("FOOD", "SERVICE", "ATMOSPHERE", "VALUE")
        PrimaryExperienceCode.GUN_BATIMI,
        PrimaryExperienceCode.GUN_DOGUMU,
        PrimaryExperienceCode.MANZARA,
        PrimaryExperienceCode.GECE_MANZARASI,
        PrimaryExperienceCode.FOTOGRAF_NOKTASI -> listOf("SCENERY", "ATMOSPHERE", "TRANQUILITY", "ACCESS")
        PrimaryExperienceCode.DENIZ_YUZME,
        PrimaryExperienceCode.PLAJ,
        PrimaryExperienceCode.TEKNE,
        PrimaryExperienceCode.DALIS_SNORKEL,
        PrimaryExperienceCode.SU_AKTIVITESI -> listOf("SEA", "CLEANLINESS", "COMFORT", "ACCESS")
        PrimaryExperienceCode.DOGA_YURUYUSU,
        PrimaryExperienceCode.PIKNIK,
        PrimaryExperienceCode.KAMP,
        PrimaryExperienceCode.ORMAN,
        PrimaryExperienceCode.GOL_SELALE,
        PrimaryExperienceCode.SEYIR_NOKTASI -> listOf("SCENERY", "ROUTE", "TRANQUILITY", "ACCESS")
        PrimaryExperienceCode.SOKAK_KESFI,
        PrimaryExperienceCode.MAHALLE_SEHIR_GEZISI,
        PrimaryExperienceCode.SAHIL_YURUYUSU,
        PrimaryExperienceCode.GIZLI_KOSE,
        PrimaryExperienceCode.ROTA_GEZI -> listOf("ATMOSPHERE", "WALKABILITY", "LOCALITY", "DISCOVERY_VALUE")
        PrimaryExperienceCode.MUZE,
        PrimaryExperienceCode.TARIHI_YER,
        PrimaryExperienceCode.MIMARI,
        PrimaryExperienceCode.YEREL_PAZAR,
        PrimaryExperienceCode.YEREL_YASAM,
        PrimaryExperienceCode.SERGI_SANAT -> listOf("CONTENT_INTEREST", "ATMOSPHERE", "ACCESS", "VALUE")
        PrimaryExperienceCode.CANLI_MUZIK,
        PrimaryExperienceCode.BAR_PUB,
        PrimaryExperienceCode.GECE_HAYATI,
        PrimaryExperienceCode.KONSER_GOSTERI,
        PrimaryExperienceCode.SOSYAL_ETKINLIK -> listOf("ATMOSPHERE", "MUSIC_ENTERTAINMENT", "SERVICE", "VALUE")
        PrimaryExperienceCode.BISIKLET,
        PrimaryExperienceCode.TIRMANIS,
        PrimaryExperienceCode.KAYAK,
        PrimaryExperienceCode.SU_SPORU,
        PrimaryExperienceCode.WORKSHOP,
        PrimaryExperienceCode.ACIK_HAVA_AKTIVITESI -> listOf("FUN", "ORGANIZATION", "COMFORT_DIFFICULTY", "VALUE")
        PrimaryExperienceCode.SAKIN_ZAMAN,
        PrimaryExperienceCode.SPA_HAMAM,
        PrimaryExperienceCode.TERMAL,
        PrimaryExperienceCode.YOGA_MEDITASYON,
        PrimaryExperienceCode.DINLENME -> listOf("ATMOSPHERE", "COMFORT", "CLEANLINESS", "VALUE")
        PrimaryExperienceCode.OTEL,
        PrimaryExperienceCode.BUTIK_OTEL,
        PrimaryExperienceCode.HOSTEL,
        PrimaryExperienceCode.KAMP_KONAKLAMASI,
        PrimaryExperienceCode.KIRALIK_EV_BUNGALOV -> listOf("CLEANLINESS", "COMFORT", "LOCATION", "SERVICE")
        else -> emptyList()
    }
}

fun Float.roundToTenth(): Float = (this * 10).roundToInt() / 10f
