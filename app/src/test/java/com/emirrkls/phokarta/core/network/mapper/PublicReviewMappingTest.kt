package com.emirrkls.phokarta.core.network.mapper

import com.emirrkls.phokarta.core.model.PublicReview
import com.emirrkls.phokarta.core.network.model.PublicVisitDto
import com.emirrkls.phokarta.core.network.model.VerificationStatusDto
import com.emirrkls.phokarta.core.network.model.VisitMediaDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime

class PublicReviewMappingTest {
    private val dto = PublicVisitDto(
        id = "30000000-0000-0000-0000-000000000001",
        placeId = "20000000-0000-0000-0000-000000000003",
        placeName = "Sarnıç Cove",
        userId = "11111111-1111-1111-1111-111111111111",
        username = "emir_demo",
        displayName = "Emir Kaya",
        avatarUrl = null,
        visitedAt = "2026-08-12",
        overallRating = 9.2,
        publicReview = "Great atmosphere and very good service.",
        photos = emptyList(),
        verificationStatus = VerificationStatusDto.UNVERIFIED,
    )

    @Test
    fun `maps public visit dto to public review without private fields`() {
        val review: PublicReview = dto.toPublicReview()

        assertEquals(dto.id.lowercase(), review.id)
        assertEquals(dto.placeId.lowercase(), review.placeId)
        assertEquals("Emir Kaya", review.author.displayName)
        assertEquals(9.2, review.overallScore, 0.001)
        assertEquals("Great atmosphere and very good service.", review.publicReview)
        assertEquals(LocalDate.of(2026, 8, 12), review.visitDate)
    }

    @Test
    fun `public review model has no private memory fields`() {
        val fields = PublicReview::class.java.declaredFields.map { it.name }.toSet()
        assertFalse(fields.contains("privateMemory"))
        assertFalse(fields.contains("personalNote"))
    }

    @Test
    fun `empty public review text is preserved`() {
        val ratingOnly = dto.copy(publicReview = "")
        assertTrue(ratingOnly.toPublicReview().publicReview.isEmpty())
    }

    @Test
    fun `public and friend review descriptors retain media identity order and signed access expiry`() {
        val review = dto.copy(
            photos = listOf("https://legacy.test/fallback.jpg"),
            media = listOf(
                VisitMediaDto("media-b", 2, "https://signed.test/b", "2026-08-25T12:02:00Z"),
                VisitMediaDto("media-a", 0, "https://signed.test/a", "2026-08-25T12:00:00Z"),
            ),
        ).toPublicReview()

        assertEquals(listOf("media-a", "media-b"), review.media.map { it.mediaId })
        assertEquals(listOf(0, 2), review.media.map { it.order })
        assertEquals(listOf("https://signed.test/a", "https://signed.test/b"), review.media.map { it.accessUrl })
        assertEquals(
            OffsetDateTime.parse("2026-08-25T12:00:00Z").toInstant().toEpochMilli(),
            review.media.first().accessUrlExpiresAtEpochMillis,
        )
        assertEquals(listOf("https://legacy.test/fallback.jpg"), review.photos)
    }
}
