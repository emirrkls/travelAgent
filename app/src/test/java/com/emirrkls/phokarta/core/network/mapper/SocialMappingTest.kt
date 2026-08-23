package com.emirrkls.phokarta.core.network.mapper

import com.emirrkls.phokarta.core.network.model.PublicUserProfileDto
import com.emirrkls.phokarta.core.network.model.RelationshipStateDto
import com.emirrkls.phokarta.core.network.model.UserSummaryDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialMappingTest {
    @Test
    fun mapsRelationshipAndPublicProfileWithoutPrivateFields() {
        val dto = PublicUserProfileDto(
            id = "22222222-2222-2222-2222-222222222222",
            username = "ahmetgoes",
            displayName = "Ahmet Deniz",
            avatarUrl = "https://example.com/a.jpg",
            bio = "Hello",
            cityCount = 3,
            countryCount = 2,
            followerCount = 10,
            followingCount = 4,
            friendCount = 1,
            relationship = RelationshipStateDto(
                isFollowing = true,
                followsYou = true,
                isFriend = true,
            ),
        )
        val profile = dto.toDomain()
        assertEquals("22222222-2222-2222-2222-222222222222", profile.id)
        assertEquals("ahmetgoes", profile.username)
        assertEquals("Ahmet Deniz", profile.displayName)
        assertEquals("https://example.com/a.jpg", profile.avatarUrl)
        assertEquals("Hello", profile.bio)
        assertEquals(10L, profile.followerCount)
        assertEquals(4L, profile.followingCount)
        assertEquals(1L, profile.friendCount)
        assertTrue(profile.relationship!!.isFriend)
        assertFalse(profile.javaClass.declaredFields.any { it.name.equals("email", true) })
    }

    @Test
    fun mapsUserSummaryRelationship() {
        val summary = UserSummaryDto(
            id = "33333333-3333-3333-3333-333333333333",
            username = "selin",
            displayName = "Selin",
            avatarUrl = null,
            relationship = RelationshipStateDto(false, true, false),
        ).toDomain()
        assertEquals("", summary.avatarUrl)
        assertFalse(summary.relationship!!.isFollowing)
        assertTrue(summary.relationship!!.followsYou)
        assertFalse(summary.relationship!!.isFriend)
    }

    @Test
    fun nullRelationshipStaysNull() {
        val summary = UserSummaryDto(
            id = "33333333-3333-3333-3333-333333333333",
            username = "selin",
            displayName = "Selin",
        ).toDomain()
        assertNull(summary.relationship)
    }
}
