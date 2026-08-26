package com.emirrkls.phokarta.feature.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.model.BlockedUser
import com.emirrkls.phokarta.ui.presentation.toUserMessageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BlockedUsersUiState(
    val items: List<BlockedUser> = emptyList(),
    val loading: Boolean = true,
    val unblockingUserId: String? = null,
    @StringRes val error: Int? = null,
    val unblocked: Boolean = false,
)

@HiltViewModel
class BlockedUsersViewModel @Inject constructor(
    private val repository: TravelRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(BlockedUsersUiState())
    val uiState = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update { it.copy(loading = true, error = null, unblocked = false) }
        viewModelScope.launch {
            when (val result = repository.loadBlockedUsers()) {
                is RepositoryResult.Failure -> _uiState.update {
                    it.copy(loading = false, error = result.error.toUserMessageRes())
                }
                is RepositoryResult.Success -> _uiState.update {
                    it.copy(loading = false, items = result.value.items, error = null)
                }
            }
        }
    }

    fun unblock(userId: String) {
        if (_uiState.value.unblockingUserId != null) return
        _uiState.update { it.copy(unblockingUserId = userId, error = null, unblocked = false) }
        viewModelScope.launch {
            when (val result = repository.unblockUser(userId)) {
                is RepositoryResult.Failure -> _uiState.update {
                    it.copy(unblockingUserId = null, error = result.error.toUserMessageRes())
                }
                is RepositoryResult.Success -> {
                    when (val refreshed = repository.loadBlockedUsers()) {
                        is RepositoryResult.Success -> _uiState.update {
                            it.copy(
                                unblockingUserId = null,
                                items = refreshed.value.items,
                                unblocked = true,
                            )
                        }
                        is RepositoryResult.Failure -> _uiState.update {
                            it.copy(
                                unblockingUserId = null,
                                items = it.items.filterNot { user -> user.userId == userId },
                                unblocked = true,
                            )
                        }
                    }
                }
            }
        }
    }

    fun consumeUnblocked() {
        _uiState.update { it.copy(unblocked = false) }
    }
}
