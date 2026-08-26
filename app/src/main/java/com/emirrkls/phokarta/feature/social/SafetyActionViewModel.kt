package com.emirrkls.phokarta.feature.social

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.model.ReportReason
import com.emirrkls.phokarta.core.model.ReportTargetType
import com.emirrkls.phokarta.ui.presentation.toBlockMessageRes
import com.emirrkls.phokarta.ui.presentation.toReportMessageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SafetyEvent {
    data object UserBlocked : SafetyEvent
    data object ReportSubmitted : SafetyEvent
}

data class SafetyActionUiState(
    val blockUserId: String? = null,
    val reportTargetType: ReportTargetType? = null,
    val reportTargetId: String? = null,
    val reportAuthorUserId: String? = null,
    val selectedReason: ReportReason? = null,
    val details: String = "",
    val submitting: Boolean = false,
    @StringRes val error: Int? = null,
    val event: SafetyEvent? = null,
    val offerBlockUserId: String? = null,
)

@HiltViewModel
class SafetyActionViewModel @Inject constructor(
    private val repository: TravelRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SafetyActionUiState())
    val uiState = _uiState.asStateFlow()

    fun openBlock(userId: String) {
        _uiState.update {
            it.copy(blockUserId = userId, error = null, event = null, offerBlockUserId = null)
        }
    }

    fun dismissBlock() {
        if (_uiState.value.submitting) return
        _uiState.update { it.copy(blockUserId = null, error = null) }
    }

    fun openReportUser(userId: String) {
        _uiState.update {
            SafetyActionUiState(
                reportTargetType = ReportTargetType.USER,
                reportTargetId = userId,
                reportAuthorUserId = userId,
            )
        }
    }

    fun openReportVisit(visitId: String, authorUserId: String) {
        _uiState.update {
            SafetyActionUiState(
                reportTargetType = ReportTargetType.VISIT,
                reportTargetId = visitId,
                reportAuthorUserId = authorUserId,
            )
        }
    }

    fun dismissReport() {
        if (_uiState.value.submitting) return
        _uiState.update {
            it.copy(
                reportTargetType = null,
                reportTargetId = null,
                selectedReason = null,
                error = null,
            )
        }
    }

    fun selectReason(reason: ReportReason) {
        _uiState.update { it.copy(selectedReason = reason, error = null) }
    }

    fun updateDetails(value: String) {
        _uiState.update { it.copy(details = value.take(REPORT_DETAILS_MAX), error = null) }
    }

    fun confirmBlock() {
        val userId = _uiState.value.blockUserId ?: return
        if (_uiState.value.submitting) return
        _uiState.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.blockUser(userId)) {
                is RepositoryResult.Failure -> _uiState.update {
                    it.copy(submitting = false, error = result.error.toBlockMessageRes())
                }
                is RepositoryResult.Success -> _uiState.update {
                    SafetyActionUiState(event = SafetyEvent.UserBlocked)
                }
            }
        }
    }

    fun submitReport() {
        val current = _uiState.value
        val type = current.reportTargetType ?: return
        val targetId = current.reportTargetId ?: return
        val reason = current.selectedReason ?: return
        if (current.submitting) return
        _uiState.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            when (
                val result = repository.submitReport(
                    type,
                    targetId,
                    reason,
                    current.details.trim().ifBlank { null },
                )
            ) {
                is RepositoryResult.Failure -> _uiState.update {
                    it.copy(submitting = false, error = result.error.toReportMessageRes())
                }
                is RepositoryResult.Success -> _uiState.update {
                    SafetyActionUiState(
                        event = SafetyEvent.ReportSubmitted,
                        offerBlockUserId = current.reportAuthorUserId,
                    )
                }
            }
        }
    }

    fun consumeEvent() {
        _uiState.update { it.copy(event = null) }
    }

    fun dismissOfferBlock() {
        _uiState.update { it.copy(offerBlockUserId = null) }
    }

    companion object {
        const val REPORT_DETAILS_MAX = 2000
    }
}
