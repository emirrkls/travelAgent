package com.emirrkls.phokarta.core.auth

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class SessionManager @Inject constructor(
    private val tokenStore: TokenStore,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    fun currentUserId(): String? =
        (_state.value as? AuthState.Authenticated)?.user?.id ?: tokenStore.readUser()?.id

    fun accessToken(): String? = tokenStore.accessToken()
    fun refreshToken(): String? = tokenStore.refreshToken()

    fun markLoading() {
        _state.value = AuthState.Loading
    }

    fun setAuthenticated(user: AuthenticatedUser, accessToken: String, refreshToken: String) {
        tokenStore.saveSession(accessToken, refreshToken, user)
        _state.value = AuthState.Authenticated(user)
    }

    fun updateTokens(accessToken: String, refreshToken: String) {
        tokenStore.updateTokens(accessToken, refreshToken)
    }

    fun restoreFromStore() {
        val user = tokenStore.readUser()
        val refresh = tokenStore.refreshToken()
        _state.value = if (user != null && refresh != null) {
            AuthState.Authenticated(user)
        } else {
            AuthState.LoggedOut
        }
    }

    fun clearSession() {
        tokenStore.clear()
        _state.value = AuthState.LoggedOut
    }
}
