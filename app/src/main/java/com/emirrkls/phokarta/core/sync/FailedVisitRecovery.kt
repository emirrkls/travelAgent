package com.emirrkls.phokarta.core.sync

import com.emirrkls.phokarta.core.database.dao.PendingVisitMutation
import com.emirrkls.phokarta.core.database.entity.MutationStateValue
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.feature.rating.VisitDraft
import com.emirrkls.phokarta.feature.rating.VisitDraftLogic
import java.time.LocalDate

enum class RecoverFailedVisitResult {
    SUCCESS,
    EXISTING_DRAFT_CONFLICT,
    NOT_FOUND,
    NOT_OWNER,
    INVALID_STATE,
}

enum class RemoveFailedVisitResult {
    SUCCESS,
    NOT_FOUND,
    NOT_OWNER,
    INVALID_STATE,
}

data class PendingVisitActions(
    val showRetry: Boolean,
    val showEditAndRetry: Boolean,
    val showRemove: Boolean,
)

object FailedVisitRecoveryPolicy {
    fun actionsFor(state: String, errorCategory: String?): PendingVisitActions = when (state) {
        MutationStateValue.FAILED_RETRYABLE -> PendingVisitActions(
            showRetry = true,
            showEditAndRetry = false,
            showRemove = false,
        )
        MutationStateValue.FAILED_PERMANENT -> {
            val reason = SyncFailureReason.fromCategory(errorCategory)
            when (reason) {
                SyncFailureReason.FORBIDDEN,
                SyncFailureReason.NOT_FOUND,
                -> PendingVisitActions(
                    showRetry = false,
                    showEditAndRetry = false,
                    showRemove = true,
                )
                else -> PendingVisitActions(
                    showRetry = false,
                    showEditAndRetry = true,
                    showRemove = true,
                )
            }
        }
        else -> PendingVisitActions(
            showRetry = false,
            showEditAndRetry = false,
            showRemove = false,
        )
    }
}

object FailedVisitRecoveryMapper {
    fun toDraft(item: PendingVisitMutation): VisitDraft {
        val payload = item.payload
        return VisitDraft(
            overallScore = payload.overallRating.toFloat(),
            dimensions = item.dimensions.mapNotNull { row ->
                runCatching { RatingDimension.valueOf(row.dimensionKey) }.getOrNull()
                    ?.let { it to row.score.toFloat() }
            }.toMap(),
            publicReview = payload.publicReview,
            privateMemory = payload.privateMemory,
            visitDate = LocalDate.ofEpochDay(payload.visitedAtEpochDay),
            visibility = runCatching { Visibility.valueOf(payload.visibility) }
                .getOrDefault(Visibility.PUBLIC),
            dimensionsExpanded = item.dimensions.isNotEmpty(),
            photos = item.photos.sortedBy { it.position }.map { it.url },
        )
    }

    fun hasMeaningfulDraftConflict(existing: VisitDraft?): Boolean {
        if (existing == null) return false
        return VisitDraftLogic.hasMeaningfulContent(existing)
    }
}
