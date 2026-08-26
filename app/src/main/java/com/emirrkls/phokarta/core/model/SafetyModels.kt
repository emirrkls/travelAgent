package com.emirrkls.phokarta.core.model

enum class ReportTargetType {
    USER,
    VISIT,
}

enum class ReportReason {
    SPAM,
    HARASSMENT,
    HATE_OR_ABUSE,
    SEXUAL_CONTENT,
    VIOLENCE_OR_THREAT,
    IMPERSONATION,
    PRIVACY,
    OTHER,
}

data class BlockedUser(
    val userId: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val blockedAt: String,
)

data class BlockedUserPage(
    val items: List<BlockedUser>,
    val page: Int,
    val totalPages: Int,
    val totalElements: Long,
    val hasNext: Boolean,
)

data class SubmittedReport(
    val id: String,
    val targetType: ReportTargetType,
    val reason: ReportReason,
    val status: String,
)
