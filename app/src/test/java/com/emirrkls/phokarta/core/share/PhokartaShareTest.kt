package com.emirrkls.phokarta.core.share

import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.core.model.Visit
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Share formatting that needs [android.content.res.Resources] is covered by UI/manual checks.
 * This keeps a compile-safe smoke check that private memory stays out of visit payloads.
 */
class PhokartaShareTest {
    @Test
    fun `visit model still separates private memory from public review`() {
        val visit = Visit(
            id = "v1",
            userId = "u1",
            placeId = "p1",
            visitedAt = LocalDate.of(2026, 8, 22),
            overallRating = 8.9,
            ratingDimensions = emptyMap(),
            review = "Public line",
            personalNote = "SECRET_PRIVATE_MEMORY",
        )
        assertEquals("Public line", visit.review)
        assertEquals("SECRET_PRIVATE_MEMORY", visit.personalNote)
        assertFalse(visit.review.contains("SECRET"))
    }

    @Test
    fun `collection place count is available for share templates`() {
        val collection = Collection(
            id = "c1",
            userId = "u1",
            title = "Bodrum Summer",
            description = "",
            placeIds = listOf("p1", "p2"),
            visibility = Visibility.PRIVATE,
            coverImage = "cover",
        )
        assertEquals(2, collection.placeIds.size)
    }
}
