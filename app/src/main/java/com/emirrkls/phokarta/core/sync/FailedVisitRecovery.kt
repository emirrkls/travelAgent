package com.emirrkls.phokarta.core.sync

import com.emirrkls.phokarta.core.database.dao.PendingVisitMutation
import com.emirrkls.phokarta.core.database.dao.PendingExperienceV2Mutation
import com.emirrkls.phokarta.core.database.entity.MutationStateValue
import com.emirrkls.phokarta.core.data.POLICY_ACCEPTANCE_REQUIRED_CODE
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
    val showAcceptPolicy: Boolean = false,
)

object FailedVisitRecoveryPolicy {
    fun actionsFor(state: String, errorCategory: String?): PendingVisitActions = when (state) {
        MutationStateValue.FAILED_RETRYABLE -> if (errorCategory == POLICY_ACCEPTANCE_REQUIRED_CODE) {
            PendingVisitActions(
                showRetry = false,
                showEditAndRetry = false,
                showRemove = false,
                showAcceptPolicy = true,
            )
        } else {
            PendingVisitActions(
                showRetry = true,
                showEditAndRetry = false,
                showRemove = false,
            )
        }
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
            photos = item.photos.sortedBy { it.position }.mapNotNull {
                it.localRelativePath ?: it.legacyUrl
            },
        )
    }

    fun toDraft(item: PendingExperienceV2Mutation): VisitDraft {
        val payload = item.payload
        return VisitDraft(
            payloadVersion = 2,
            publicReview = payload.story,
            privateMemory = payload.privateMemory,
            visitDate = LocalDate.ofEpochDay(payload.visitedAtEpochDay),
            visibility = enumOrDefault(payload.visibility, Visibility.PUBLIC),
            dimensionsExpanded = item.dimensions.isNotEmpty(),
            photos = item.photos.sortedBy { it.position }.mapNotNull {
                it.localRelativePath ?: it.legacyUrl
            },
            primaryExperience = enumOrNull<PrimaryExperienceCode>(payload.primaryExperienceCode),
            rawExperienceLabel = payload.rawExperienceLabel,
            overallFeeling = enumOrNull<OverallFeelingCode>(payload.overallFeelingCode),
            semanticDimensions = item.dimensions.associate { row ->
                row.dimensionKey to enumOrDefault(row.semanticStateCode, DimensionStateCode.UNKNOWN)
            }.filterValues { it != DimensionStateCode.UNKNOWN },
            companion = enumOrNull<CompanionCode>(payload.companionCode),
            timeOfDay = enumOrNull<TimeOfDayCode>(payload.timeOfDayCode),
            vibes = decodeSet<VibeCode>(payload.vibeCodes),
            practicalSignals = decodeSet<PracticalSignalCode>(payload.practicalSignalCodes),
            title = payload.title,
            titleSource = enumOrDefault(payload.titleSource, ExperienceTitleSource.GENERATED),
            story = payload.story,
            tip = payload.tip,
            originAcknowledgementId = payload.originAcknowledgementId,
        )
    }

    fun hasMeaningfulDraftConflict(existing: VisitDraft?): Boolean {
        if (existing == null) return false
        return VisitDraftLogic.hasMeaningfulContent(existing)
    }

    private inline fun <reified T : Enum<T>> enumOrNull(raw: String?): T? =
        raw?.let { value -> enumValues<T>().firstOrNull { it.name == value } }

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: default

    private inline fun <reified T : Enum<T>> decodeSet(raw: String): Set<T> =
        raw.split(',').filter(String::isNotBlank).mapNotNull { value ->
            enumValues<T>().firstOrNull { it.name == value }
        }.toSet()
}
