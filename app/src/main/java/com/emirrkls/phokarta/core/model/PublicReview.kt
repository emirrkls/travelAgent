package com.emirrkls.phokarta.core.model

import java.time.LocalDate

data class PublicReviewAuthor(
    val userId: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
)

data class PublicReviewMedia(
    val mediaId: String,
    val order: Int,
    val accessUrl: String?,
    val accessUrlExpiresAtEpochMillis: Long?,
)

data class PublicReview(
    val id: String,
    val placeId: String,
    val author: PublicReviewAuthor,
    val overallScore: Double,
    val publicReview: String,
    val visitDate: LocalDate,
    val photos: List<String> = emptyList(),
    val media: List<PublicReviewMedia> = emptyList(),
    val verificationStatus: VerificationStatus = VerificationStatus.UNVERIFIED,
)

data class PublicReviewPage(
    val reviews: List<PublicReview>,
    val page: Int,
    val totalPages: Int,
    val totalElements: Long,
    val hasNext: Boolean,
)
