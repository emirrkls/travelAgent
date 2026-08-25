package com.emirrkls.phokarta.feature.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.auth.AuthRepository
import com.emirrkls.phokarta.core.auth.AuthResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountDeletionUiState(
    val confirmOpen: Boolean = false,
    val password: String = "",
    val passwordVisible: Boolean = false,
    val loading: Boolean = false,
    @StringRes val error: Int? = null,
    val requiresPassword: Boolean = true,
)

@HiltViewModel
class AccountDeletionViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AccountDeletionUiState())
    val uiState: StateFlow<AccountDeletionUiState> = _uiState.asStateFlow()

    fun openConfirmation() {
        _uiState.value = AccountDeletionUiState(confirmOpen = true)
    }

    fun dismissConfirmation() {
        if (_uiState.value.loading) return
        _uiState.value = AccountDeletionUiState()
    }

    fun updatePassword(value: String) {
        _uiState.update { it.copy(password = value, error = null) }
    }

    fun togglePasswordVisible() {
        _uiState.update { it.copy(passwordVisible = !it.passwordVisible) }
    }

    fun confirmDelete() {
        val current = _uiState.value
        if (current.loading) return
        val password = current.password
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = authRepository.deleteAccount(password.ifBlank { null })) {
                AuthResult.Success -> _uiState.value = AccountDeletionUiState()
                is AuthResult.Error -> _uiState.update {
                    it.copy(loading = false, error = result.message, password = "")
                }
            }
        }
    }
}
