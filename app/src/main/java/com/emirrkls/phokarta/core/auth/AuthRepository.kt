package com.emirrkls.phokarta.core.auth

import androidx.annotation.StringRes
import com.emirrkls.phokarta.R
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
        val refresh = sessionManager.refreshToken()
        if (refresh.isNullOrBlank()) {
            sessionManager.clearSession()
            return AuthState.LoggedOut
        }
        sessionManager.markLoading()
        when (val refreshed = safeApiCall(json) {
            authApi.refresh(RefreshRequestDto(refreshToken = refresh))
        }) {
            is RemoteResult.Failure -> {
                if (refreshed.error.isOfflineRetryable()) {
                    sessionManager.restoreFromStore()
                    return sessionManager.state.value
                }
                sessionManager.clearSession()
                return AuthState.LoggedOut
            }
            is RemoteResult.Success -> sessionManager.updateTokens(
                refreshed.value.accessToken,
                refreshed.value.refreshToken,
            )
        }
        return when (val me = safeApiCall(json) { meApi.profile() }) {
            is RemoteResult.Failure -> {
                if (me.error.isOfflineRetryable()) {
                    sessionManager.restoreFromStore()
                    return sessionManager.state.value
                }
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
    data class Error(@StringRes val message: Int) : AuthResult
}

fun UserProfileDto.toAuthenticatedUser() = AuthenticatedUser(
    id = id,
    email = email,
    username = username,
    displayName = displayName,
    bio = bio.orEmpty(),
    avatarUrl = avatarUrl.orEmpty(),
)

@StringRes
private fun NetworkError.toAuthMessage(): Int = when (this) {
    NetworkError.Connection -> R.string.error_offline
    NetworkError.Timeout -> R.string.error_timeout
    is NetworkError.Validation -> R.string.error_validation
    is NetworkError.Forbidden -> R.string.error_forbidden
    is NetworkError.NotFound -> R.string.error_not_found
    is NetworkError.Conflict -> R.string.error_conflict
    is NetworkError.Server -> R.string.error_server
    is NetworkError.Unknown -> R.string.error_unknown
}

private fun NetworkError.isOfflineRetryable(): Boolean = when (this) {
    NetworkError.Connection, NetworkError.Timeout, is NetworkError.Server -> true
    is NetworkError.Unknown -> status == 408 || status == 429 || status == null
    is NetworkError.Validation, is NetworkError.Forbidden,
    is NetworkError.NotFound, is NetworkError.Conflict -> false
}
