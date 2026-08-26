package com.emirrkls.phokarta.ui.presentation

import androidx.annotation.StringRes
import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.core.data.TravelError
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

fun formatDistance(distanceMeters: Double, locale: Locale = Locale.getDefault()): String =
    if (distanceMeters < 1_000.0) {
        // Unit labels stay short SI abbreviations; number is locale-aware.
        val meters = distanceMeters.coerceAtLeast(0.0).roundToInt()
        "$meters m"
    } else {
        val km = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }.format(distanceMeters / 1_000.0)
        "$km km"
    }

@StringRes
fun TravelError.toUserMessageRes(): Int = when (this) {
    is TravelError.Offline -> R.string.error_offline
    is TravelError.Timeout -> R.string.error_timeout
    is TravelError.Validation -> R.string.error_validation
    is TravelError.Forbidden -> R.string.error_forbidden
    is TravelError.PolicyAcceptanceRequired -> R.string.error_policy_acceptance_required
    is TravelError.NotFound -> R.string.error_not_found
    is TravelError.Conflict -> R.string.error_conflict
    is TravelError.Server -> if (status == 429) R.string.report_rate_limited else R.string.error_server
    is TravelError.Unknown -> R.string.error_unknown
}

@StringRes
fun TravelError.toFollowMessageRes(): Int = when (this) {
    is TravelError.Conflict, is TravelError.Forbidden -> R.string.relationship_unavailable
    else -> toUserMessageRes()
}

@StringRes
fun TravelError.toBlockMessageRes(): Int = when (this) {
    is TravelError.Offline -> R.string.block_offline
    else -> R.string.block_failed
}

@StringRes
fun TravelError.toReportMessageRes(): Int = when (this) {
    is TravelError.Offline -> R.string.report_offline
    is TravelError.Server -> if (status == 429) R.string.report_rate_limited else R.string.report_failed
    else -> R.string.report_failed
}
