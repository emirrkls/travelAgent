package com.emirrkls.phokarta.core

import com.emirrkls.phokarta.core.database.entity.VisitDimensionScoreEntity
import com.emirrkls.phokarta.core.database.entity.VisitEntity
import com.emirrkls.phokarta.core.database.mapper.toDimensionEntities
import com.emirrkls.phokarta.core.database.mapper.toDomain
import com.emirrkls.phokarta.core.database.mapper.toEntity
import com.emirrkls.phokarta.core.database.relation.VisitWithDimensions
import com.emirrkls.phokarta.core.model.VerificationStatus
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.core.model.Visibility
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DatabaseMapperTest {
    @Test
    fun `visit maps through Room boundary without losing domain data`() {
        val visit = Visit(
            id = "visit-map",
            userId = "user-1",
            placeId = "p1",
            visitedAt = LocalDate.of(2026, 8, 22),
            overallRating = 9.4,
            ratingDimensions = mapOf("Sea" to 9.7, "Value" to 8.2),
            review = "Clear water.",
            personalNote = "Return before noon.",
            visibility = Visibility.FRIENDS,
            verificationStatus = VerificationStatus.LOCATION_CONFIRMED,
        )

        val stored = VisitWithDimensions(
            visit = visit.toEntity(createdAtEpochMillis = 123L),
            dimensions = visit.toDimensionEntities(),
        )

        assertEquals(visit, stored.toDomain())
    }

    @Test
    fun `missing dimension rows remain missing`() {
        val stored = VisitWithDimensions(
            visit = VisitEntity(
                id = "visit-empty",
                userId = "user-1",
                placeId = "p2",
                visitedAtEpochDay = LocalDate.of(2026, 1, 2).toEpochDay(),
                overallRating = 7.5,
                publicReview = "",
                privateMemory = "",
                visibility = Visibility.PUBLIC.name,
                verificationStatus = VerificationStatus.UNVERIFIED.name,
                createdAtEpochMillis = 123L,
            ),
            dimensions = emptyList<VisitDimensionScoreEntity>(),
        )

        assertEquals(emptyMap<String, Double>(), stored.toDomain().ratingDimensions)
    }
}
