package com.emirrkls.phokarta.ui.presentation

import com.emirrkls.phokarta.core.data.TravelError
import java.util.Locale
import kotlin.math.roundToInt

fun formatDistance(distanceMeters: Double): String =
    if (distanceMeters < 1_000.0) {
        "${distanceMeters.coerceAtLeast(0.0).roundToInt()} m"
    } else {
        String.format(Locale.US, "%.1f km", distanceMeters / 1_000.0)
    }

fun TravelError.toUserMessage(): String = when (this) {
    is TravelError.Offline -> "You appear to be offline. Check your connection and try again."
    is TravelError.Timeout -> "The request took too long. Please try again."
    is TravelError.Validation -> "That request could not be completed."
    is TravelError.Forbidden -> "This content isn't available."
    is TravelError.NotFound -> "We couldn’t find what you were looking for."
    is TravelError.Conflict -> "This changed elsewhere. Refresh and try again."
    is TravelError.Server -> "The service is temporarily unavailable. Please try again."
    is TravelError.Unknown -> "Something went wrong. Please try again."
}
