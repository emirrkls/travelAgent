package com.emirrkls.phokarta.core.network.source

import com.emirrkls.phokarta.BuildConfig
import com.emirrkls.phokarta.core.network.RemoteResult
import com.emirrkls.phokarta.core.network.api.MediaApi
import com.emirrkls.phokarta.core.network.model.MediaStateDto
import com.emirrkls.phokarta.core.network.model.MediaUploadIntentRequestDto
import com.emirrkls.phokarta.core.network.model.MediaUploadIntentResponseDto
import com.emirrkls.phokarta.core.network.model.MediaAccessDto
import com.emirrkls.phokarta.core.network.safeApiCall
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import com.emirrkls.phokarta.core.network.UploadHttpClient

interface MediaRemoteDataSource {
    suspend fun createIntent(request: MediaUploadIntentRequestDto): RemoteResult<MediaUploadIntentResponseDto>
    suspend fun confirm(mediaId: String): RemoteResult<MediaStateDto>
    suspend fun access(mediaId: String): RemoteResult<MediaAccessDto>
}

class RetrofitMediaRemoteDataSource @Inject constructor(
    private val api: MediaApi,
    private val json: Json,
) : MediaRemoteDataSource {
    override suspend fun createIntent(request: MediaUploadIntentRequestDto) =
        safeApiCall(json) { api.createUploadIntent(request) }

    override suspend fun confirm(mediaId: String) =
        safeApiCall(json) { api.confirm(mediaId) }

    override suspend fun access(mediaId: String) =
        safeApiCall(json) { api.access(mediaId) }
}

sealed interface DirectUploadResult {
    data object Success : DirectUploadResult
    data class Retryable(val category: String) : DirectUploadResult
    data class Permanent(val category: String) : DirectUploadResult
}

open class DirectMediaUploader @Inject constructor(
    @UploadHttpClient private val uploadClient: OkHttpClient,
) {
    open suspend fun put(
        url: String,
        headers: Map<String, String>,
        file: File,
        contentType: String,
        byteSize: Long,
    ): DirectUploadResult = withContext(Dispatchers.IO) {
        val scheme = runCatching { java.net.URI(url).scheme?.lowercase() }.getOrNull()
        if (scheme != "https" && !(BuildConfig.DEBUG && scheme == "http")) {
            return@withContext DirectUploadResult.Permanent("INSECURE_UPLOAD_URL")
        }
        val body = object : RequestBody() {
            override fun contentType() = contentType.toMediaType()
            override fun contentLength() = byteSize
            override fun writeTo(sink: BufferedSink) {
                file.source().use { source -> sink.writeAll(source) }
            }
        }
        val request = runCatching {
            Request.Builder().url(url).put(body).apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
        }.getOrElse { return@withContext DirectUploadResult.Permanent("INVALID_UPLOAD_URL") }
        try {
            uploadClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> DirectUploadResult.Success
                    response.code == 408 || response.code == 429 || response.code >= 500 ->
                        DirectUploadResult.Retryable("UPLOAD_HTTP_${response.code}")
                    response.code == 401 || response.code == 403 ->
                        DirectUploadResult.Retryable("UPLOAD_URL_EXPIRED")
                    else -> DirectUploadResult.Permanent("UPLOAD_REJECTED")
                }
            }
        } catch (_: java.net.SocketTimeoutException) {
            DirectUploadResult.Retryable("UPLOAD_TIMEOUT")
        } catch (_: java.io.IOException) {
            DirectUploadResult.Retryable("UPLOAD_CONNECTION")
        }
    }
}
