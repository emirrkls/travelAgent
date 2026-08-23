package com.emirrkls.phokarta.core.auth

import com.emirrkls.phokarta.BuildConfig
import com.emirrkls.phokarta.core.network.model.RefreshRequestDto
import com.emirrkls.phokarta.core.network.model.TokenPairDto
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route

/**
 * Single-flight 401 refresh: concurrent authentications await one refresh attempt.
 * Uses a dedicated OkHttp client without this authenticator to avoid deadlock.
 */
@Singleton
class TokenAuthenticator : Authenticator {
    private val sessionManager: SessionManager
    private val json: Json
    private val apiBaseUrl: String

    private val refreshing = AtomicBoolean(false)
    @Volatile private var inFlight: CompletableFuture<String?>? = null

    @Inject
    constructor(
        sessionManager: SessionManager,
        json: Json,
    ) : this(sessionManager, json, BuildConfig.PHOKARTA_API_BASE_URL)

    internal constructor(
        sessionManager: SessionManager,
        json: Json,
        apiBaseUrl: String,
    ) {
        this.sessionManager = sessionManager
        this.json = json
        this.apiBaseUrl = apiBaseUrl.trimEnd('/') + "/"
    }

    private val refreshClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null
        if (response.request.url.encodedPath.contains("/api/v1/auth/")) return null

        val refreshToken = sessionManager.refreshToken() ?: run {
            sessionManager.clearSession()
            return null
        }

        val newAccess = awaitRefresh(refreshToken) ?: return null
        return response.request.newBuilder()
            .header("Authorization", "Bearer $newAccess")
            .build()
    }

    private fun awaitRefresh(refreshToken: String): String? {
        while (true) {
            val existing = inFlight
            if (existing != null) {
                return runCatching { existing.get() }.getOrNull()
            }
            if (refreshing.compareAndSet(false, true)) {
                val future = CompletableFuture<String?>()
                inFlight = future
                try {
                    val access = performRefresh(refreshToken)
                    future.complete(access)
                    return access
                } catch (t: Throwable) {
                    future.complete(null)
                    sessionManager.clearSession()
                    return null
                } finally {
                    inFlight = null
                    refreshing.set(false)
                }
            }
        }
    }

    private fun performRefresh(refreshToken: String): String? {
        val body = json.encodeToString(
            RefreshRequestDto.serializer(),
            RefreshRequestDto(refreshToken),
        ).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(apiBaseUrl + "api/v1/auth/refresh")
            .post(body)
            .build()
        refreshClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                sessionManager.clearSession()
                return null
            }
            val payload = response.body?.string() ?: return null
            val tokens = json.decodeFromString(TokenPairDto.serializer(), payload)
            sessionManager.updateTokens(tokens.accessToken, tokens.refreshToken)
            return tokens.accessToken
        }
    }

    private fun responseCount(response: Response): Int {
        var result = 1
        var prior = response.priorResponse
        while (prior != null) {
            result++
            prior = prior.priorResponse
        }
        return result
    }
}
