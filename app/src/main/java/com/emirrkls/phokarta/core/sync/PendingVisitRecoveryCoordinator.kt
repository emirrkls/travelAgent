package com.emirrkls.phokarta.core.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface PendingVisitRecoveryEvent {
    data class NavigateToRating(val placeId: String) : PendingVisitRecoveryEvent
    data class ShowReplaceDraftDialog(val mutationId: String, val placeId: String) : PendingVisitRecoveryEvent
}

class PendingVisitRecoveryCoordinator(
    private val offlineMutations: OfflineMutationRepository,
) {
    private val _events = MutableSharedFlow<PendingVisitRecoveryEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<PendingVisitRecoveryEvent> = _events.asSharedFlow()

    suspend fun editAndRetry(mutationId: String, placeId: String, replaceExisting: Boolean = false) {
        when (offlineMutations.recoverFailedVisitForEditing(mutationId, replaceExisting)) {
            RecoverFailedVisitResult.SUCCESS ->
                _events.emit(PendingVisitRecoveryEvent.NavigateToRating(placeId))
            RecoverFailedVisitResult.EXISTING_DRAFT_CONFLICT ->
                _events.emit(PendingVisitRecoveryEvent.ShowReplaceDraftDialog(mutationId, placeId))
            RecoverFailedVisitResult.NOT_FOUND,
            RecoverFailedVisitResult.NOT_OWNER,
            RecoverFailedVisitResult.INVALID_STATE,
            -> Unit
        }
    }

    suspend fun confirmReplaceDraft(mutationId: String, placeId: String) {
        when (offlineMutations.recoverFailedVisitForEditing(mutationId, replaceExisting = true)) {
            RecoverFailedVisitResult.SUCCESS ->
                _events.emit(PendingVisitRecoveryEvent.NavigateToRating(placeId))
            else -> Unit
        }
    }

    suspend fun removeFailedVisit(mutationId: String): RemoveFailedVisitResult =
        offlineMutations.removeFailedVisit(mutationId)

    suspend fun retry(mutationId: String) {
        offlineMutations.retry(mutationId)
    }
}
