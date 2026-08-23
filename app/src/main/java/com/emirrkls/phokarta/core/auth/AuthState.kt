package com.emirrkls.phokarta.core.auth

data class AuthenticatedUser(
    val id: String,
    val email: String,
    val username: String,
    val displayName: String,
    val bio: String,
    val avatarUrl: String,
)

sealed interface AuthState {
    data object Loading : AuthState
    data object LoggedOut : AuthState
    data class Authenticated(val user: AuthenticatedUser) : AuthState
}
