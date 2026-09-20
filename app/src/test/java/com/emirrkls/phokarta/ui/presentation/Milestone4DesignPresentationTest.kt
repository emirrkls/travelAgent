package com.emirrkls.phokarta.ui.presentation

import com.emirrkls.phokarta.core.model.OverallFeelingCode
import com.emirrkls.phokarta.core.model.PrimaryExperienceCode
import com.emirrkls.phokarta.feature.rating.VisitDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Milestone4DesignPresentationTest {
    @Test
    fun noMediaUsesCompactIntentionalPresentationInsteadOfLoading() {
        assertEquals(
            ExperienceDetailMediaPresentation.NO_MEDIA,
            experienceDetailMediaPresentation(mediaCount = 0),
        )
        assertEquals(
            ExperienceDetailMediaPresentation.MEDIA,
            experienceDetailMediaPresentation(mediaCount = 1),
        )
    }

    @Test
    fun collectionSummaryDistinguishesPlaceExperienceAndMixedContent() {
        assertEquals(CollectionSummaryKind.PLACES, collectionContentSummary(2, 0).kind)
        assertEquals(CollectionSummaryKind.EXPERIENCES, collectionContentSummary(0, 2).kind)
        val mixed = collectionContentSummary(1, 1)
        assertEquals(CollectionSummaryKind.MIXED, mixed.kind)
        assertEquals(2, mixed.totalCount)
    }

    @Test
    fun acknowledgementPrefillAloneDoesNotClaimDraftWasRestored() {
        val prefill = VisitDraft(
            payloadVersion = 2,
            primaryExperience = PrimaryExperienceCode.GUN_BATIMI,
            originAcknowledgementId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        )

        assertFalse(shouldShowDraftRestoredFeedback(prefill))
        assertTrue(
            shouldShowDraftRestoredFeedback(
                prefill.copy(overallFeeling = OverallFeelingCode.GUZELDI),
            ),
        )
        assertTrue(shouldShowDraftRestoredFeedback(VisitDraft(publicReview = "Recovered edits")))
    }
}
