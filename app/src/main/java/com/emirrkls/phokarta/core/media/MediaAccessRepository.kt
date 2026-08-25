package com.emirrkls.phokarta.core.media

import com.emirrkls.phokarta.BuildConfig
import com.emirrkls.phokarta.core.auth.SessionManager
import com.emirrkls.phokarta.core.database.dao.VisitDao
import com.emirrkls.phokarta.core.network.RemoteResult
import com.emirrkls.phokarta.core.network.source.MediaRemoteDataSource
import com.emirrkls.phokarta.core.time.EpochClock
import java.net.URI
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaAccessRepository @Inject constructor(
    private val visits: VisitDao,
    private val remote: MediaRemoteDataSource,
    private val session: SessionManager,
    private val clock: EpochClock,
) {
    suspend fun accessUrl(visitId: String, mediaId: String): String? {
        val owner = session.currentUserId() ?: return null
        val cached = visits.getMedia(owner, visitId, mediaId) ?: return null
        if (
            cached.accessUrl != null &&
            (cached.accessUrlExpiresAtEpochMillis ?: 0L) > clock.nowMillis() + REFRESH_SKEW_MS
        ) {
            return cached.accessUrl
        }
        val response = when (val result = remote.access(mediaId)) {
            is RemoteResult.Failure -> return cached.accessUrl
                ?.takeIf { (cached.accessUrlExpiresAtEpochMillis ?: 0L) > clock.nowMillis() }
            is RemoteResult.Success -> result.value
        }
        val scheme = runCatching { URI(response.url).scheme?.lowercase() }.getOrNull()
        if (scheme != "https" && !(BuildConfig.DEBUG && scheme == "http")) return null
        val expiresAt = runCatching { OffsetDateTime.parse(response.expiresAt).toInstant().toEpochMilli() }
            .getOrNull() ?: return null
        if (expiresAt <= clock.nowMillis()) return null
        if (visits.updateMediaAccess(owner, visitId, mediaId, response.url, expiresAt) != 1) return null
        return response.url
    }

    companion object {
        private const val REFRESH_SKEW_MS = 30_000L
    }
}
