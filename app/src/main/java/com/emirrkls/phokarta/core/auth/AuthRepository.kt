package com.emirrkls.phokarta.core.auth

import com.emirrkls.phokarta.core.network.NetworkError
import com.emirrkls.phokarta.core.network.RemoteResult
import com.emirrkls.phokarta.core.network.api.AuthApi
import com.emirrkls.phokarta.core.network.api.MeApi
import com.emirrkls.phokarta.core.network.model.AuthSessionDto
import com.emirrkls.phokarta.core.network.model.LoginRequestDto
import com.emirrkls.phokarta.core.network.model.LogoutRequestDto
import com.emirrkls.phokarta.core.network.model.RefreshRequestDto
import com.emirrkls.phokarta.core.network.model.RegisterRequestDto
import com.emirrkls.phokarta.core.network.model.UserProfileDto
import com.emirrkls.phokarta.core.network.safeApiCall
import com.emirrkls.phokarta.core.network.safeUnitApiCall
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Singleton
class AuthRepository @Inject constructor(
    private val authApi: AuthApi,
    private val meApi: MeApi,
    private val sessionManager: SessionManager,
    private val json: Json,
) {
    suspend fun register(
        email: String,
        username: String,
        displayName: String,
        password: String,
    ): AuthResult = when (
        val result = safeApiCall(json) {
            authApi.register(
                RegisterRequestDto(
                    email = email.trim(),
                    username = username.trim(),
                    displayName = displayName.trim(),
                    password = password,
                ),
            )
        }
    ) {
        is RemoteResult.Failure -> AuthResult.Error(result.error.toAuthMessage())
        is RemoteResult.Success -> acceptSession(result.value)
    }

    suspend fun login(identifier: String, password: String): AuthResult = when (
        val result = safeApiCall(json) {
            authApi.login(LoginRequestDto(identifier = identifier.trim(), password = password))
        }
    ) {
        is RemoteResult.Failure -> AuthResult.Error(result.error.toAuthMessage())
        is RemoteResult.Success -> acceptSession(result.value)
    }

    suspend fun restoreSession(): AuthState {
        sessionManager.markLoading()
        val refresh = sessionManager.refreshToken()
        if (refresh.isNullOrBlank()) {
            sessionManager.clearSession()
            return AuthState.LoggedOut
        }
        when (refreshTokens(refresh)) {
            is AuthResult.Error -> {
                sessionManager.clearSession()
                return AuthState.LoggedOut
            }
            AuthResult.Success -> Unit
        }
        return when (val me = safeApiCall(json) { meApi.profile() }) {
            is RemoteResult.Failure -> {
                sessionManager.clearSession()
                AuthState.LoggedOut
            }
            is RemoteResult.Success -> {
                val user = me.value.toAuthenticatedUser()
                sessionManager.setAuthenticated(
                    user,
                    sessionManager.accessToken().orEmpty(),
                    sessionManager.refreshToken().orEmpty(),
                )
                AuthState.Authenticated(user)
            }
        }
    }

    suspend fun logout() {
        val refresh = sessionManager.refreshToken()
        if (!refresh.isNullOrBlank()) {
            runCatching {
                safeUnitApiCall(json) {
                    authApi.logout(LogoutRequestDto(refreshToken = refresh))
                }
            }
        }
        sessionManager.clearSession()
    }

    fun refreshTokensBlocking(refreshToken: String): Boolean {
        // Used by OkHttp authenticator on a background thread via runBlocking in TokenAuthenticator.
        return false
    }

    suspend fun refreshTokens(refreshToken: String): AuthResult = when (
        val result = safeApiCall(json) {
            authApi.refresh(RefreshRequestDto(refreshToken = refreshToken))
        }
    ) {
        is RemoteResult.Failure -> AuthResult.Error(result.error.toAuthMessage())
        is RemoteResult.Success -> {
            val user = (sessionManager.state.value as? AuthState.Authenticated)?.user
            if (user != null) {
                sessionManager.setAuthenticated(
                    user,
                    result.value.accessToken,
                    result.value.refreshToken,
                )
            } else {
                sessionManager.updateTokens(result.value.accessToken, result.value.refreshToken)
            }
            AuthResult.Success
        }
    }

    private fun acceptSession(session: AuthSessionDto): AuthResult {
        sessionManager.setAuthenticated(
            session.user.toAuthenticatedUser(),
            session.accessToken,
            session.refreshToken,
        )
        return AuthResult.Success
    }
}

sealed interface AuthResult {
    data object Success : AuthResult
    data class Error(val message: String) : AuthResult
}

fun UserProfileDto.toAuthenticatedUser() = AuthenticatedUser(
    id = id,
    email = email,
    username = username,
    displayName = displayName,
    bio = bio.orEmpty(),
    avatarUrl = avatarUrl.orEmpty(),
)

private fun NetworkError.toAuthMessage(): String = when (this) {
    NetworkError.Connection ->
        "You appear to be offline. Check your connection and try again."
    NetworkError.Timeout ->
        "The request took too long. Please try again."
    is NetworkError.Validation ->
        apiError?.message ?: "That request could not be completed."
    is NetworkError.Forbidden ->
        apiError?.message ?: "This content isn't available."
    is NetworkError.NotFound ->
        apiError?.message ?: "We couldn’t find what you were looking for."
    is NetworkError.Conflict ->
        apiError?.message ?: "This changed elsewhere. Refresh and try again."
    is NetworkError.Server ->
        apiError?.message ?: "The service is temporarily unavailable. Please try again."
    is NetworkError.Unknown ->
        apiError?.message ?: cause?.message ?: "Something went wrong. Please try again."
}
