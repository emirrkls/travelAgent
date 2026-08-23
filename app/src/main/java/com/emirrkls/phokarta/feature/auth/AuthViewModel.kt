package com.emirrkls.phokarta.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.auth.AuthRepository
import com.emirrkls.phokarta.core.auth.AuthResult
import com.emirrkls.phokarta.core.auth.AuthState
import com.emirrkls.phokarta.core.auth.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthFormState(
    val identifier: String = "",
    val email: String = "",
    val username: String = "",
    val displayName: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    sessionManager: SessionManager,
) : ViewModel() {
    val authState: StateFlow<AuthState> = sessionManager.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, AuthState.Loading)

    private val _loginForm = MutableStateFlow(AuthFormState())
    val loginForm: StateFlow<AuthFormState> = _loginForm.asStateFlow()

    private val _registerForm = MutableStateFlow(AuthFormState())
    val registerForm: StateFlow<AuthFormState> = _registerForm.asStateFlow()

    fun updateLogin(identifier: String? = null, password: String? = null) {
        _loginForm.update {
            it.copy(
                identifier = identifier ?: it.identifier,
                password = password ?: it.password,
                error = null,
            )
        }
    }

    fun updateRegister(
        displayName: String? = null,
        username: String? = null,
        email: String? = null,
        password: String? = null,
    ) {
        _registerForm.update {
            it.copy(
                displayName = displayName ?: it.displayName,
                username = username ?: it.username,
                email = email ?: it.email,
                password = password ?: it.password,
                error = null,
            )
        }
    }

    fun toggleLoginPasswordVisible() {
        _loginForm.update { it.copy(passwordVisible = !it.passwordVisible) }
    }

    fun toggleRegisterPasswordVisible() {
        _registerForm.update { it.copy(passwordVisible = !it.passwordVisible) }
    }

    fun login() {
        val form = _loginForm.value
        if (form.identifier.isBlank() || form.password.length < 8) {
            _loginForm.update { it.copy(error = "Enter your email/username and password (min 8).") }
            return
        }
        viewModelScope.launch {
            _loginForm.update { it.copy(loading = true, error = null) }
            when (val result = authRepository.login(form.identifier, form.password)) {
                AuthResult.Success -> _loginForm.update { AuthFormState() }
                is AuthResult.Error -> _loginForm.update {
                    it.copy(loading = false, error = result.message, password = "")
                }
            }
        }
    }

    fun register() {
        val form = _registerForm.value
        when {
            form.displayName.isBlank() -> {
                _registerForm.update { it.copy(error = "Display name is required.") }
                return
            }
            form.username.length < 3 -> {
                _registerForm.update { it.copy(error = "Username must be at least 3 characters.") }
                return
            }
            !form.email.contains("@") -> {
                _registerForm.update { it.copy(error = "Enter a valid email.") }
                return
            }
            form.password.length < 8 -> {
                _registerForm.update { it.copy(error = "Password must be at least 8 characters.") }
                return
            }
        }
        viewModelScope.launch {
            _registerForm.update { it.copy(loading = true, error = null) }
            when (
                val result = authRepository.register(
                    email = form.email,
                    username = form.username,
                    displayName = form.displayName,
                    password = form.password,
                )
            ) {
                AuthResult.Success -> _registerForm.update { AuthFormState() }
                is AuthResult.Error -> _registerForm.update {
                    it.copy(loading = false, error = result.message, password = "")
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }
}
