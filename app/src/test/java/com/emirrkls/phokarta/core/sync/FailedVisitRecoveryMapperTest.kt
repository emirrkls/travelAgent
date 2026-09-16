package com.emirrkls.phokarta.core.sync

import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.core.database.dao.PendingVisitMutation
import com.emirrkls.phokarta.core.database.dao.PendingExperienceV2Mutation
import com.emirrkls.phokarta.core.database.entity.MutationStateValue
import com.emirrkls.phokarta.core.database.entity.MutationTypeValue
import com.emirrkls.phokarta.core.database.entity.PendingMutationEntity
import com.emirrkls.phokarta.core.database.entity.PendingVisitDimensionScoreEntity
import com.emirrkls.phokarta.core.database.entity.PendingVisitPayloadEntity
import com.emirrkls.phokarta.core.database.entity.PendingVisitPhotoEntity
import com.emirrkls.phokarta.core.database.entity.PendingExperienceV2DimensionEntity
import com.emirrkls.phokarta.core.database.entity.PendingExperienceV2PayloadEntity
import com.emirrkls.phokarta.core.model.DimensionStateCode
import com.emirrkls.phokarta.core.model.OverallFeelingCode
import com.emirrkls.phokarta.core.model.PrimaryExperienceCode
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.feature.rating.VisitDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FailedVisitRecoveryMapperTest {
    @Test
    fun mapsFailedPayloadToDraftExactly() {
        val item = pendingVisitMutation(
            overall = 8.0,
            review = "Great cove",
            memory = "Secret note",
            date = LocalDate.of(2026, 5, 12),
            visibility = Visibility.FRIENDS,
            dimensions = mapOf(RatingDimension.SEA to 9.0),
            photos = listOf("https://example.test/1.jpg"),
        )

        val draft = FailedVisitRecoveryMapper.toDraft(item)

        assertEquals(8f, draft.overallScore)
        assertEquals(mapOf(RatingDimension.SEA to 9f), draft.dimensions)
        assertEquals("Great cove", draft.publicReview)
        assertEquals("Secret note", draft.privateMemory)
        assertEquals(LocalDate.of(2026, 5, 12), draft.visitDate)
        assertEquals(Visibility.FRIENDS, draft.visibility)
        assertEquals(listOf("https://example.test/1.jpg"), draft.photos)
        assertTrue(draft.dimensionsExpanded)
    }

    @Test
    fun meaningfulDraftConflictDetectsExistingWork() {
        val meaningful = VisitDraft(publicReview = "existing")
        val empty = VisitDraft()
        assertTrue(FailedVisitRecoveryMapper.hasMeaningfulDraftConflict(meaningful))
        assertFalse(FailedVisitRecoveryMapper.hasMeaningfulDraftConflict(empty))
        assertFalse(FailedVisitRecoveryMapper.hasMeaningfulDraftConflict(null))
    }

    @Test
    fun nativeV2FailureRestoresExactSemanticPayloadAndOrderedMedia() {
        val mutationId = "native-v2"
        val item = PendingExperienceV2Mutation(
            mutation = PendingMutationEntity(
                mutationId, USER, MutationTypeValue.PUBLISH_EXPERIENCE_V2, mutationId,
                MutationStateValue.FAILED_PERMANENT, 1, null, 1, 10, 10, "VALIDATION", 2,
            ),
            payload = PendingExperienceV2PayloadEntity(
                mutationId, PLACE, LocalDate.of(2026, 9, 16).toEpochDay(),
                "GUN_BATIMI", null, "BAYILDIM", "PARTNER", "EVENING",
                "CALM,SCENIC", "ARRIVE_EARLY,FREE", "Golden hour", "CUSTOM",
                "story", "tip", "owner only", "FRIENDS",
            ),
            dimensions = listOf(
                PendingExperienceV2DimensionEntity(mutationId, "SCENERY", "VERY_GOOD", 1),
            ),
            photos = listOf(
                PendingVisitPhotoEntity(mutationId, 0, "owned/first.jpg"),
                PendingVisitPhotoEntity(mutationId, 1, "owned/second.jpg"),
            ),
        )

        val draft = FailedVisitRecoveryMapper.toDraft(item)

        assertEquals(2, draft.payloadVersion)
        assertEquals(PrimaryExperienceCode.GUN_BATIMI, draft.primaryExperience)
        assertEquals(OverallFeelingCode.BAYILDIM, draft.overallFeeling)
        assertEquals(mapOf("SCENERY" to DimensionStateCode.VERY_GOOD), draft.semanticDimensions)
        assertEquals("story", draft.story)
        assertEquals("tip", draft.tip)
        assertEquals("owner only", draft.privateMemory)
        assertEquals(listOf("owned/first.jpg", "owned/second.jpg"), draft.photos)
    }

    @Test
    fun permanentFailureActionsFollowPolicy() {
        assertEquals(
            SyncFailureReason.LEGACY_MEDIA_RESELECT_REQUIRED,
            SyncFailureReason.fromCategory("LEGACY_MEDIA_RESELECT_REQUIRED"),
        )
        assertEquals(
            R.string.sync_failure_legacy_media_reselect,
            SyncFailureReason.LEGACY_MEDIA_RESELECT_REQUIRED.labelRes(),
        )
        val legacyMedia = FailedVisitRecoveryPolicy.actionsFor(
            MutationStateValue.FAILED_PERMANENT,
            "LEGACY_MEDIA_RESELECT_REQUIRED",
        )
        assertFalse(legacyMedia.showRetry)
        assertTrue(legacyMedia.showEditAndRetry)
        assertTrue(legacyMedia.showRemove)

        val validation = FailedVisitRecoveryPolicy.actionsFor(
            MutationStateValue.FAILED_PERMANENT,
            "VALIDATION",
        )
        assertFalse(validation.showRetry)
        assertTrue(validation.showEditAndRetry)
        assertTrue(validation.showRemove)

        val notFound = FailedVisitRecoveryPolicy.actionsFor(
            MutationStateValue.FAILED_PERMANENT,
            "NOT_FOUND",
        )
        assertFalse(notFound.showEditAndRetry)
        assertTrue(notFound.showRemove)

        val retryable = FailedVisitRecoveryPolicy.actionsFor(
            MutationStateValue.FAILED_RETRYABLE,
            "TIMEOUT",
        )
        assertTrue(retryable.showRetry)
        assertFalse(retryable.showEditAndRetry)
        assertFalse(retryable.showAcceptPolicy)

        val policy = FailedVisitRecoveryPolicy.actionsFor(
            MutationStateValue.FAILED_RETRYABLE,
            "POLICY_ACCEPTANCE_REQUIRED",
        )
        assertFalse(policy.showRetry)
        assertFalse(policy.showEditAndRetry)
        assertFalse(policy.showRemove)
        assertTrue(policy.showAcceptPolicy)
        assertEquals(
            R.string.sync_failure_policy_acceptance,
            SyncFailureReason.POLICY_ACCEPTANCE_REQUIRED.labelRes(),
        )
    }

    private fun pendingVisitMutation(
        overall: Double,
        review: String,
        memory: String,
        date: LocalDate,
        visibility: Visibility,
        dimensions: Map<RatingDimension, Double>,
        photos: List<String>,
    ): PendingVisitMutation {
        val mutationId = "mutation-1"
        return PendingVisitMutation(
            mutation = PendingMutationEntity(
                mutationId, USER, MutationTypeValue.PUBLISH_VISIT, mutationId,
                MutationStateValue.FAILED_PERMANENT, 1, null, 1, 10, 10, "VALIDATION",
            ),
            payload = PendingVisitPayloadEntity(
                mutationId, PLACE, date.toEpochDay(), overall, review, memory, visibility.name,
            ),
            dimensions = dimensions.map { (key, score) ->
                PendingVisitDimensionScoreEntity(mutationId, key.name, score)
            },
            photos = photos.mapIndexed { index, url ->
                PendingVisitPhotoEntity(mutationId, index, url)
            },
        )
    }

    companion object {
        private const val USER = "11111111-1111-1111-1111-111111111111"
        private const val PLACE = "20000000-0000-0000-0000-000000000003"
    }
}
