package com.emirrkls.phokarta.core.data

import com.emirrkls.phokarta.core.model.CompanionCode
import com.emirrkls.phokarta.core.model.DimensionStateCode
import com.emirrkls.phokarta.core.model.ExperienceTitleSource
import com.emirrkls.phokarta.core.model.OverallFeelingCode
import com.emirrkls.phokarta.core.model.PracticalSignalCode
import com.emirrkls.phokarta.core.model.PrimaryExperienceCode
import com.emirrkls.phokarta.core.model.TimeOfDayCode
import com.emirrkls.phokarta.core.model.VibeCode
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.feature.rating.VisitDraft
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class VisitDraftV2PersistenceTest {
    @Test
    fun everyNativeSemanticFieldRoundTripsByStableCode() {
        val original = VisitDraft(
            payloadVersion = 2,
            privateMemory = "owner only",
            visitDate = LocalDate.of(2026, 9, 16),
            visibility = Visibility.FRIENDS,
            primaryExperience = PrimaryExperienceCode.GUN_BATIMI,
            overallFeeling = OverallFeelingCode.BAYILDIM,
            semanticDimensions = mapOf("SCENERY" to DimensionStateCode.VERY_GOOD),
            companion = CompanionCode.PARTNER,
            timeOfDay = TimeOfDayCode.EVENING,
            vibes = linkedSetOf(VibeCode.SCENIC, VibeCode.CALM),
            practicalSignals = linkedSetOf(PracticalSignalCode.FREE, PracticalSignalCode.ARRIVE_EARLY),
            title = "Golden Foça",
            titleSource = ExperienceTitleSource.CUSTOM,
            story = "A still evening",
            tip = "Arrive early",
        )

        val entity = original.toDraftEntity(USER, PLACE, 10, 20)
        val dimensions = original.toDraftDimensionEntities(USER, PLACE)
        val restored = entity.toDomain(dimensions)

        assertEquals(original, restored)
        assertEquals("CALM,SCENIC", entity.vibeCodes)
        assertEquals("ARRIVE_EARLY,FREE", entity.practicalSignalCodes)
        assertEquals(10f, dimensions.single().score)
        assertEquals("VERY_GOOD", dimensions.single().semanticStateCode)
        assertEquals(1, dimensions.single().templateVersion)
    }

    companion object {
        private const val USER = "11111111-1111-1111-1111-111111111111"
        private const val PLACE = "22222222-2222-2222-2222-222222222222"
    }
}
