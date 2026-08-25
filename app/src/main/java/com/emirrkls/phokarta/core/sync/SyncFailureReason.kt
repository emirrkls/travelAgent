package com.emirrkls.phokarta.core.sync

import androidx.annotation.StringRes
import com.emirrkls.phokarta.R

/**
 * Stable internal classification for permanent sync failures.
 * Mapped from [PendingMutationEntity.lastErrorCategory]; never persist localized strings.
 */
enum class SyncFailureReason {
    LEGACY_MEDIA_RESELECT_REQUIRED,
    VALIDATION,
    FORBIDDEN,
    NOT_FOUND,
    IDEMPOTENCY_CONFLICT,
    UNKNOWN_PERMANENT,
    ;

    @StringRes
    fun labelRes(): Int = when (this) {
        LEGACY_MEDIA_RESELECT_REQUIRED -> R.string.sync_failure_legacy_media_reselect
        VALIDATION -> R.string.sync_failure_validation
        FORBIDDEN -> R.string.sync_failure_forbidden
        NOT_FOUND -> R.string.sync_failure_not_found
        IDEMPOTENCY_CONFLICT -> R.string.sync_failure_idempotency
        UNKNOWN_PERMANENT -> R.string.sync_failure_generic
    }

    companion object {
        fun fromCategory(category: String?): SyncFailureReason? {
            if (category.isNullOrBlank()) return null
            return when (category) {
                "LEGACY_MEDIA_RESELECT_REQUIRED" -> LEGACY_MEDIA_RESELECT_REQUIRED
                "VALIDATION" -> VALIDATION
                "FORBIDDEN" -> FORBIDDEN
                "NOT_FOUND" -> NOT_FOUND
                "CONFLICT" -> IDEMPOTENCY_CONFLICT
                "MISSING_PAYLOAD", "INVALID_RESPONSE", "UNKNOWN_TYPE" -> UNKNOWN_PERMANENT
                else -> if (category.startsWith("HTTP_")) UNKNOWN_PERMANENT else UNKNOWN_PERMANENT
            }
        }
    }
}
