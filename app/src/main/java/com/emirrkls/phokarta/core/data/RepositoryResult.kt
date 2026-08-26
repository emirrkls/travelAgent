package com.emirrkls.phokarta.core.data

import com.emirrkls.phokarta.core.network.NetworkError

sealed interface RepositoryResult<out T> {
    data class Success<T>(val value: T) : RepositoryResult<T>
    data class Failure(val error: TravelError) : RepositoryResult<Nothing>
}

sealed interface TravelError {
    val message: String?

    data class Offline(override val message: String? = null) : TravelError
    data class Timeout(override val message: String? = null) : TravelError
    data class Validation(override val message: String? = null) : TravelError
    data class Forbidden(override val message: String? = null) : TravelError
    data class NotFound(override val message: String? = null) : TravelError
    data class Conflict(override val message: String? = null) : TravelError
    data class Server(val status: Int, override val message: String? = null) : TravelError
    data class Unknown(override val message: String? = null) : TravelError
}

internal fun NetworkError.toTravelError(): TravelError = when (this) {
    NetworkError.Connection -> TravelError.Offline()
    NetworkError.Timeout -> TravelError.Timeout()
    is NetworkError.Validation -> TravelError.Validation(apiError?.message)
    is NetworkError.Unauthorized -> TravelError.Unknown(apiError?.message)
    is NetworkError.Forbidden -> TravelError.Forbidden(apiError?.message)
    is NetworkError.NotFound -> TravelError.NotFound(apiError?.message)
    is NetworkError.Conflict -> TravelError.Conflict(apiError?.message)
    is NetworkError.Server -> TravelError.Server(status, apiError?.message)
    is NetworkError.Unknown -> if (status == 429) {
        TravelError.Server(429, apiError?.message)
    } else {
        TravelError.Unknown(apiError?.message ?: cause?.message)
    }
}
