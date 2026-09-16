package com.emirrkls.phokarta.core.network.mapper

import com.emirrkls.phokarta.core.model.DimensionStateCode
import com.emirrkls.phokarta.core.model.ExperienceClassification
import com.emirrkls.phokarta.core.model.ExperienceFamily
import com.emirrkls.phokarta.core.model.ExperienceMediaKind
import com.emirrkls.phokarta.core.model.ExperienceTitleSource
import com.emirrkls.phokarta.core.model.FeelingProvenance
import com.emirrkls.phokarta.core.model.OverallFeelingCode
import com.emirrkls.phokarta.core.model.PracticalSignalCode
import com.emirrkls.phokarta.core.model.PrimaryExperienceCode
import com.emirrkls.phokarta.core.model.VibeCode
import com.emirrkls.phokarta.core.network.model.ExperienceV2Dto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ExperienceV2MappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `native fixture maps persisted title context and ordered media`() {
        val dto = json.decodeFromString<ExperienceV2Dto>(nativeFixture())
        val experience = dto.toDomain()

        assertEquals(ExperienceClassification.NATIVE_V2, experience.classification)
        assertEquals("Persisted sunset title", experience.title)
        assertEquals(ExperienceTitleSource.GENERATED, experience.titleSource)
        assertEquals(OverallFeelingCode.BAYILDIM, experience.feeling.code)
        assertEquals(FeelingProvenance.EXPLICIT, experience.feeling.provenance)
        assertEquals(PrimaryExperienceCode.GUN_BATIMI, experience.primaryExperience.code)
        assertEquals(ExperienceFamily.SCENERY_AND_MOMENT, experience.primaryExperience.family)
        assertEquals(listOf(VibeCode.CALM, VibeCode.ROMANTIC), experience.vibes)
        assertEquals(listOf(PracticalSignalCode.ARRIVE_EARLY), experience.practicalSignals)
        assertEquals(DimensionStateCode.VERY_GOOD, experience.dimensions.single().semanticState)
        assertEquals(listOf(0, 1), experience.media.map { it.position })
        assertEquals(listOf(ExperienceMediaKind.LEGACY_URL, ExperienceMediaKind.MANAGED), experience.media.map { it.kind })
    }

    @Test
    fun `unknown server codes degrade safely without exposing ignored private memory`() {
        val dto = json.decodeFromString<ExperienceV2Dto>(
            nativeFixture()
                .replace("GUN_BATIMI", "FUTURE_PRIMARY")
                .replace("SCENERY_AND_MOMENT", "FUTURE_FAMILY")
                .replace("CALM", "FUTURE_VIBE")
                .replace("ARRIVE_EARLY", "FUTURE_SIGNAL")
                .replace("VERY_GOOD", "FUTURE_STATE"),
        )
        val experience = dto.toDomain()

        assertEquals(PrimaryExperienceCode.UNKNOWN, experience.primaryExperience.code)
        assertEquals(ExperienceFamily.UNKNOWN, experience.primaryExperience.family)
        assertEquals(VibeCode.UNKNOWN, experience.vibes.first())
        assertEquals(PracticalSignalCode.UNKNOWN, experience.practicalSignals.single())
        assertEquals(DimensionStateCode.UNKNOWN, experience.dimensions.single().semanticState)
        assertFalse(experience.story.contains("owner-only"))
    }

    @Test
    fun `legacy compatibility state does not fabricate canonical primary or title source`() {
        val dto = json.decodeFromString<ExperienceV2Dto>(
            nativeFixture()
                .replace("NATIVE_V2", "LEGACY_COMPATIBILITY")
                .replace("\"titleSource\": \"GENERATED\",", "\"titleSource\": null,")
                .replace("\"titlePersisted\": true", "\"titlePersisted\": false")
                .replace("GUN_BATIMI", "UNKNOWN_LEGACY")
                .replace("\"canonical\": true", "\"canonical\": false")
                .replace("\"family\": \"SCENERY_AND_MOMENT\"", "\"family\": null"),
        ).toDomain()

        assertEquals(ExperienceClassification.LEGACY_COMPATIBILITY, dto.classification)
        assertEquals(PrimaryExperienceCode.UNKNOWN_LEGACY, dto.primaryExperience.code)
        assertFalse(dto.primaryExperience.canonical)
        assertNull(dto.primaryExperience.family)
        assertNull(dto.titleSource)
        assertFalse(dto.titlePersisted)
    }

    private fun nativeFixture() = """
        {
          "id": "30000000-0000-0000-0000-000000000001",
          "classification": "NATIVE_V2",
          "author": {
            "id": "11111111-1111-1111-1111-111111111111",
            "username": "author",
            "displayName": "Author",
            "avatarUrl": null
          },
          "place": {
            "id": "20000000-0000-0000-0000-000000000001",
            "name": "Foça",
            "category": "BEACH",
            "city": "İzmir",
            "region": "Aegean",
            "country": "Türkiye",
            "coverImage": "https://images.test/cover.jpg"
          },
          "experiencedAt": "2026-09-01",
          "title": "Persisted sunset title",
          "titleSource": "GENERATED",
          "titlePersisted": true,
          "story": "Native story",
          "tip": "Arrive early",
          "feeling": {
            "code": "BAYILDIM",
            "source": "EXPLICIT",
            "compatibilityNumericRating": 10.0
          },
          "primaryExperience": {
            "code": "GUN_BATIMI",
            "canonical": true,
            "family": "SCENERY_AND_MOMENT",
            "rawLabel": null
          },
          "companion": "PARTNER",
          "timeOfDay": "EVENING",
          "vibes": ["CALM", "ROMANTIC"],
          "practicalSignals": ["ARRIVE_EARLY"],
          "dimensions": [{
            "key": "SCENERY",
            "numericScore": 10.0,
            "semanticState": "VERY_GOOD",
            "templateVersion": 1
          }],
          "media": [{
            "kind": "MANAGED",
            "position": 1,
            "id": "40000000-0000-0000-0000-000000000001",
            "url": "https://media.test/signed",
            "accessExpiresAt": "2026-09-16T12:00:00Z"
          }, {
            "kind": "LEGACY_URL",
            "position": 0,
            "id": null,
            "url": "https://legacy.test/0.jpg",
            "accessExpiresAt": null
          }],
          "visibility": "PUBLIC",
          "taxonomyVersion": 1,
          "privateMemory": "owner-only"
        }
    """.trimIndent()
}
