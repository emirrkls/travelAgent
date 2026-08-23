package com.emirrkls.phokarta.core.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenStore {
    private val prefs: SharedPreferences

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ),
    )

    /** JVM unit tests: in-memory or other SharedPreferences without Android Keystore. */
    internal constructor(prefs: SharedPreferences) {
        this.prefs = prefs
    }

    @Synchronized
    fun saveSession(
        accessToken: String,
        refreshToken: String,
        user: AuthenticatedUser,
    ) {
        prefs.edit()
            .putString(KEY_ACCESS, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .putString(KEY_USER_ID, user.id)
            .putString(KEY_EMAIL, user.email)
            .putString(KEY_USERNAME, user.username)
            .putString(KEY_DISPLAY_NAME, user.displayName)
            .putString(KEY_BIO, user.bio)
            .putString(KEY_AVATAR, user.avatarUrl)
            .apply()
    }

    @Synchronized
    fun updateTokens(accessToken: String, refreshToken: String) {
        prefs.edit()
            .putString(KEY_ACCESS, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .apply()
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
    }

    fun accessToken(): String? = prefs.getString(KEY_ACCESS, null)?.takeIf { it.isNotBlank() }
    fun refreshToken(): String? = prefs.getString(KEY_REFRESH, null)?.takeIf { it.isNotBlank() }

    fun readUser(): AuthenticatedUser? {
        val id = prefs.getString(KEY_USER_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        return AuthenticatedUser(
            id = id,
            email = prefs.getString(KEY_EMAIL, "").orEmpty(),
            username = prefs.getString(KEY_USERNAME, "").orEmpty(),
            displayName = prefs.getString(KEY_DISPLAY_NAME, "").orEmpty(),
            bio = prefs.getString(KEY_BIO, "").orEmpty(),
            avatarUrl = prefs.getString(KEY_AVATAR, "").orEmpty(),
        )
    }

    companion object {
        private const val FILE_NAME = "phokarta_secure_session"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_EMAIL = "email"
        private const val KEY_USERNAME = "username"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_BIO = "bio"
        private const val KEY_AVATAR = "avatar_url"
    }
}
