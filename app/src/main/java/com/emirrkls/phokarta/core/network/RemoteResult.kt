package com.emirrkls.phokarta.core.network

import com.emirrkls.phokarta.core.network.model.ApiErrorDto
import java.io.InterruptedIOException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.Response

sealed interface RemoteResult<out T> {
    data class Success<T>(val value: T) : RemoteResult<T>
    data class Failure(val error: NetworkError) : RemoteResult<Nothing>
}

sealed interface NetworkError {
    data object Connection : NetworkError
    data object Timeout : NetworkError
    data class Validation(val apiError: ApiErrorDto?) : NetworkError
    data class Unauthorized(val apiError: ApiErrorDto?) : NetworkError
    data class Forbidden(val apiError: ApiErrorDto?) : NetworkError
    data class NotFound(val apiError: ApiErrorDto?) : NetworkError
    data class Conflict(val apiError: ApiErrorDto?) : NetworkError
    data class Server(val status: Int, val apiError: ApiErrorDto?) : NetworkError
    data class Unknown(
        val status: Int? = null,
        val apiError: ApiErrorDto? = null,
        val cause: Throwable? = null,
    ) : NetworkError
}

suspend fun <T : Any> safeApiCall(
    json: Json,
    request: suspend () -> Response<T>,
): RemoteResult<T> = executeApiCall(json, request) { response ->
    response.body()?.let { RemoteResult.Success(it) }
        ?: RemoteResult.Failure(NetworkError.Unknown(status = response.code()))
}

suspend fun safeUnitApiCall(
    json: Json,
    request: suspend () -> Response<Unit>,
): RemoteResult<Unit> = executeApiCall(json, request) {
    RemoteResult.Success(Unit)
}

private suspend fun <T, R> executeApiCall(
    json: Json,
    request: suspend () -> Response<T>,
    onSuccess: (Response<T>) -> RemoteResult<R>,
): RemoteResult<R> {
    return try {
        val response = request()
        if (response.isSuccessful) {
            onSuccess(response)
        } else {
            RemoteResult.Failure(response.toNetworkError(json))
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: SocketTimeoutException) {
        RemoteResult.Failure(NetworkError.Timeout)
    } catch (_: InterruptedIOException) {
        RemoteResult.Failure(NetworkError.Timeout)
    } catch (_: IOException) {
        RemoteResult.Failure(NetworkError.Connection)
    } catch (error: SerializationException) {
        RemoteResult.Failure(NetworkError.Unknown(cause = error))
    } catch (error: Exception) {
        RemoteResult.Failure(NetworkError.Unknown(cause = error))
    }
}

private fun Response<*>.toNetworkError(json: Json): NetworkError {
    val apiError = errorBody()?.string()?.takeIf(String::isNotBlank)?.let { body ->
        runCatching { json.decodeFromString<ApiErrorDto>(body) }.getOrNull()
    }
    return when (code()) {
        400, 422 -> NetworkError.Validation(apiError)
        401 -> NetworkError.Unauthorized(apiError)
        403 -> NetworkError.Forbidden(apiError)
        404 -> NetworkError.NotFound(apiError)
        409 -> NetworkError.Conflict(apiError)
        in 500..599 -> NetworkError.Server(code(), apiError)
        else -> NetworkError.Unknown(status = code(), apiError = apiError)
    }
}
