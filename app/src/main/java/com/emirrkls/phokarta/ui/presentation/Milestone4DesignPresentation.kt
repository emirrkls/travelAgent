package com.emirrkls.phokarta.ui.presentation

import com.emirrkls.phokarta.core.network.model.CollectionV2ItemDto
import com.emirrkls.phokarta.feature.rating.VisitDraft
import com.emirrkls.phokarta.feature.rating.VisitDraftLogic

/** Presentation-only decisions locked by the Milestone 4 design review. */
enum class ExperienceDetailMediaPresentation {
    MEDIA,
    NO_MEDIA,
}

fun experienceDetailMediaPresentation(mediaCount: Int): ExperienceDetailMediaPresentation =
    if (mediaCount > 0) ExperienceDetailMediaPresentation.MEDIA else ExperienceDetailMediaPresentation.NO_MEDIA

enum class CollectionSummaryKind {
    PLACES,
    EXPERIENCES,
    MIXED,
}

data class CollectionContentSummary(
    val kind: CollectionSummaryKind,
    val placeCount: Int,
    val experienceCount: Int,
) {
    val totalCount: Int get() = placeCount + experienceCount
}

fun collectionContentSummary(
    items: List<CollectionV2ItemDto>,
    fallbackPlaceCount: Int = 0,
): CollectionContentSummary {
    val typedPlaceCount = items.count { it.type == "PLACE" && it.place != null }
    val experienceCount = items.count { it.type == "EXPERIENCE" && it.experience != null }
    val placeCount = if (items.isEmpty()) fallbackPlaceCount else typedPlaceCount
    return collectionContentSummary(placeCount, experienceCount)
}

fun collectionContentSummary(placeCount: Int, experienceCount: Int): CollectionContentSummary {
    val kind = when {
        placeCount > 0 && experienceCount > 0 -> CollectionSummaryKind.MIXED
        experienceCount > 0 -> CollectionSummaryKind.EXPERIENCES
        else -> CollectionSummaryKind.PLACES
    }
    return CollectionContentSummary(kind, placeCount, experienceCount)
}

/**
 * An acknowledgement-origin prefill is not a restored user draft. Only content the user
 * actually entered on top of that prefill earns recovery feedback.
 */
fun shouldShowDraftRestoredFeedback(draft: VisitDraft?): Boolean {
    draft ?: return false
    if (draft.originAcknowledgementId == null) return VisitDraftLogic.hasMeaningfulContent(draft)
    return draft.overallFeeling != null ||
        draft.companion != null ||
        draft.timeOfDay != null ||
        draft.vibes.isNotEmpty() ||
        draft.practicalSignals.isNotEmpty() ||
        draft.semanticDimensions.isNotEmpty() ||
        draft.publicReview.isNotBlank() ||
        draft.story.isNotBlank() ||
        draft.tip.isNotBlank() ||
        draft.privateMemory.isNotBlank() ||
        draft.photos.isNotEmpty() ||
        draft.title?.isNotBlank() == true
}
