package com.emirrkls.phokarta.core.network

import com.emirrkls.phokarta.core.network.model.ApiErrorDto
import com.emirrkls.phokarta.core.network.model.CreateVisitDto
import com.emirrkls.phokarta.core.network.model.DimensionScoreDto
import com.emirrkls.phokarta.core.network.model.PageResponseDto
import com.emirrkls.phokarta.core.network.model.PlaceCategoryDto
import com.emirrkls.phokarta.core.network.model.PlaceSummaryDto
import com.emirrkls.phokarta.core.network.model.PublicVisitDto
import com.emirrkls.phokarta.core.network.model.RatingDimensionDto
import com.emirrkls.phokarta.core.network.model.VerificationStatusDto
import com.emirrkls.phokarta.core.network.model.VisibilityDto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `place page fixture preserves contract fields`() {
        val fixture = """
            {
              "content": [{
                "id": "20000000-0000-0000-0000-000000000001",
                "name": "Fixture Beach",
                "category": "BEACH",
                "coverImage": "https://example.test/beach.jpg",
                "city": "Antalya",
                "region": "Mediterranean",
                "country": "Turkey",
                "latitude": 36.8969,
                "longitude": 30.7133,
                "priceLevel": 2,
                "averageScore": null,
                "ratingCount": 0
              }],
              "page": 0,
              "size": 20,
              "totalElements": 1,
              "totalPages": 1,
              "hasNext": false
            }
        """.trimIndent()

        val page = json.decodeFromString<PageResponseDto<PlaceSummaryDto>>(fixture)
        val place = page.content.single()

        assertEquals("20000000-0000-0000-0000-000000000001", place.id)
        assertEquals(PlaceCategoryDto.BEACH, place.category)
        assertNull(place.averageScore)
        assertEquals(36.8969, place.latitude, 0.0)
        assertEquals(30.7133, place.longitude, 0.0)
        assertEquals(1L, page.totalElements)
        assertEquals(1, page.totalPages)
        assertEquals(false, page.hasNext)
    }

    @Test
    fun `create visit serializes exact names date and uppercase typed values`() {
        val request = CreateVisitDto(
            placeId = "20000000-0000-0000-0000-000000000001",
            visitedAt = "2026-08-22",
            overallRating = 9.1,
            dimensions = listOf(DimensionScoreDto(RatingDimensionDto.CLEANLINESS, 9.4)),
            publicReview = "Public note",
            privateMemory = "Private note",
            photos = emptyList(),
            visibility = VisibilityDto.PUBLIC,
        )

        val encoded = json.parseToJsonElement(json.encodeToString(request)).jsonObject

        assertFalse(encoded.containsKey("userId"))
        assertEquals("2026-08-22", encoded.getValue("visitedAt").jsonPrimitive.content)
        assertEquals("Public note", encoded.getValue("publicReview").jsonPrimitive.content)
        assertEquals("Private note", encoded.getValue("privateMemory").jsonPrimitive.content)
        assertEquals("PUBLIC", encoded.getValue("visibility").jsonPrimitive.content)
        assertEquals(
            "CLEANLINESS",
            encoded.getValue("dimensions").jsonArray.single().jsonObject
                .getValue("key").jsonPrimitive.content,
        )
    }

    @Test
    fun `create visit serializes each visibility enum as uppercase API value`() {
        listOf(
            VisibilityDto.PUBLIC to "PUBLIC",
            VisibilityDto.FRIENDS to "FRIENDS",
            VisibilityDto.PRIVATE to "PRIVATE",
        ).forEach { (visibility, expected) ->
            val request = CreateVisitDto(
                placeId = "20000000-0000-0000-0000-000000000001",
                visitedAt = "2026-08-22",
                overallRating = 8.0,
                dimensions = null,
                publicReview = null,
                privateMemory = null,
                photos = null,
                visibility = visibility,
            )
            val encoded = json.parseToJsonElement(json.encodeToString(request)).jsonObject
            assertEquals(expected, encoded.getValue("visibility").jsonPrimitive.content)
        }
    }

    @Test
    fun `public visit serialization cannot expose private memory while create request can carry it`() {
        val publicVisit = PublicVisitDto(
            id = "30000000-0000-0000-0000-000000000001",
            placeId = "20000000-0000-0000-0000-000000000001",
            placeName = "Fixture Beach",
            userId = "11111111-1111-1111-1111-111111111111",
            username = "demo",
            displayName = "Demo User",
            avatarUrl = null,
            visitedAt = "2026-08-22",
            overallRating = 9.1,
            publicReview = "Visible review",
            photos = emptyList(),
            verificationStatus = VerificationStatusDto.UNVERIFIED,
        )
        val createRequest = CreateVisitDto(
            placeId = publicVisit.placeId,
            visitedAt = publicVisit.visitedAt,
            overallRating = publicVisit.overallRating,
            dimensions = null,
            publicReview = publicVisit.publicReview,
            privateMemory = "Owner-only memory",
            photos = null,
            visibility = VisibilityDto.PUBLIC,
        )

        val publicJson = json.parseToJsonElement(json.encodeToString(publicVisit)).jsonObject
        val createJson = json.parseToJsonElement(json.encodeToString(createRequest)).jsonObject

        assertFalse(publicJson.containsKey("privateMemory"))
        assertFalse(createJson.containsKey("userId"))
        assertEquals("Visible review", publicJson.getValue("publicReview").jsonPrimitive.content)
        assertEquals("Owner-only memory", createJson.getValue("privateMemory").jsonPrimitive.content)
    }

    @Test
    fun `api error fixture parses field errors`() {
        val fixture = """
            {
              "timestamp": "2026-08-22T18:00:00Z",
              "status": 400,
              "code": "VALIDATION_ERROR",
              "message": "Request validation failed",
              "path": "/api/v1/visits",
              "fieldErrors": {
                "visitedAt": "must be a date in the past or in the present"
              }
            }
        """.trimIndent()

        val error = json.decodeFromString<ApiErrorDto>(fixture)

        assertEquals(400, error.status)
        assertEquals("VALIDATION_ERROR", error.code)
        assertEquals(
            "must be a date in the past or in the present",
            error.fieldErrors["visitedAt"],
        )
    }
}
