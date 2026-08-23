package com.emirrkls.phokarta.core.share

import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.core.model.Visit
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PhokartaShareTest {
    @Test
    fun `place text includes score when available`() {
        assertEquals(
            "Sarnıç Cove · Bodrum — 9.2 on Phokarta",
            PhokartaShare.placeText("Sarnıç Cove", "Bodrum", 9.2),
        )
    }

    @Test
    fun `place text omits score when missing`() {
        assertEquals(
            "Sarnıç Cove · Bodrum on Phokarta",
            PhokartaShare.placeText("Sarnıç Cove", "Bodrum", null),
        )
    }

    @Test
    fun `visit text never includes private memory`() {
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
        val text = PhokartaShare.visitText("Sarnıç Cove", visit)
        assertEquals("Sarnıç Cove — 8.9 on Phokarta", text)
        assertFalse(text.contains("SECRET_PRIVATE_MEMORY"))
        assertFalse(text.contains("Public line"))
    }

    @Test
    fun `collection text uses place count`() {
        val collection = Collection(
            id = "c1",
            userId = "u1",
            title = "Bodrum Summer",
            description = "",
            placeIds = listOf("p1", "p2"),
            visibility = Visibility.PRIVATE,
            coverImage = "cover",
        )
        assertEquals("Bodrum Summer · 2 places on Phokarta", PhokartaShare.collectionText(collection))
    }
}
