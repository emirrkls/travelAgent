package com.emirrkls.phokarta.core.network.mapper

import com.emirrkls.phokarta.core.model.DimensionStateCode
import com.emirrkls.phokarta.core.model.FollowRequestV2
import com.emirrkls.phokarta.core.model.OverallFeelingCode
import com.emirrkls.phokarta.core.model.PracticalSignalCode
import com.emirrkls.phokarta.core.model.PrivacySocialStateStore
import com.emirrkls.phokarta.core.model.ProfileVisibilityV2
import com.emirrkls.phokarta.core.model.RelationshipActionState
import com.emirrkls.phokarta.core.model.UserSummary
import com.emirrkls.phokarta.core.network.model.PlaceAggregateV2Dto
import com.emirrkls.phokarta.core.network.model.ProfileV2Dto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyV2MappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `pending relationship maps without pretending to follow`() {
        val profile = decodeProfile(identityOnlyFixture()).toDomain()
        assertEquals(RelationshipActionState.REQUEST_PENDING, profile.relationship?.state)
        assertTrue(profile.relationship!!.isPending)
        assertFalse(profile.relationship!!.isFriend)
    }

    @Test
    fun `private identity-only response keeps protected metrics absent`() {
        val profile = decodeProfile(identityOnlyFixture()).toDomain()
        assertEquals(ProfileVisibilityV2.PRIVATE, profile.visibility)
        assertTrue(profile.isIdentityOnly)
        assertNull(profile.cityCount)
        assertNull(profile.followerCount)
        assertNull(profile.visibleExperienceCount)
    }

    @Test
    fun `owner full profile keeps visible count`() {
        val profile = decodeProfile(fullFixture()).toDomain()
        assertTrue(profile.fullProfile)
        assertEquals(8L, profile.visibleExperienceCount)
        assertEquals(3L, profile.friendCount)
    }

    @Test
    fun `friend requires explicit mutual approved state`() {
        val following = decodeProfile(fullFixture().replace("FRIENDS", "FOLLOWING")).toDomain()
        val friends = decodeProfile(fullFixture()).toDomain()
        assertFalse(following.relationship!!.isFriend)
        assertTrue(friends.relationship!!.isFriend)
    }

    @Test
    fun `block invalidation removes directional relationship hints`() {
        val blocked = decodeProfile(fullFixture()).toDomain().relationship!!.invalidatedByBlock()
        assertEquals(RelationshipActionState.UNAVAILABLE, blocked.state)
        assertFalse(blocked.followsYou)
        assertFalse(blocked.canFollow)
    }

    @Test
    fun `aggregate maps separate counts and compatibility foundations`() {
        val aggregate = json.decodeFromString<PlaceAggregateV2Dto>(aggregateFixture()).toDomain()
        assertEquals(1L, aggregate.visibleExperienceCount)
        assertEquals(4L, aggregate.communityContributionCount)
        assertEquals(OverallFeelingCode.BAYILDIM, aggregate.feelings.first().code)
        assertEquals(DimensionStateCode.VERY_GOOD, aggregate.dimensions.single().semanticDistribution.single().state)
        assertEquals(1L, aggregate.dimensions.single().legacyNumericContributionCount)
        assertEquals(PracticalSignalCode.ARRIVE_EARLY, aggregate.practicalSignals.single().code)
        assertEquals(4L, aggregate.practicalSignals.single().eligibleContributionDenominator)
    }

    @Test
    fun `unknown privacy relationship and aggregate codes degrade safely`() {
        val profile = decodeProfile(identityOnlyFixture()
            .replace("PRIVATE", "FUTURE_PRIVACY")
            .replace("REQUEST_PENDING", "FUTURE_RELATIONSHIP")).toDomain()
        val aggregate = json.decodeFromString<PlaceAggregateV2Dto>(aggregateFixture()
            .replace("BAYILDIM", "FUTURE_FEELING")
            .replace("VERY_GOOD", "FUTURE_STATE")
            .replace("ARRIVE_EARLY", "FUTURE_SIGNAL")).toDomain()
        assertEquals(ProfileVisibilityV2.UNKNOWN, profile.visibility)
        assertEquals(RelationshipActionState.UNKNOWN, profile.relationship?.state)
        assertEquals(OverallFeelingCode.UNKNOWN, aggregate.feelings.first().code)
        assertEquals(DimensionStateCode.UNKNOWN, aggregate.dimensions.single().semanticDistribution.single().state)
        assertEquals(PracticalSignalCode.UNKNOWN, aggregate.practicalSignals.single().code)
    }

    @Test
    fun `account switch clears privacy and request state`() {
        val store = PrivacySocialStateStore()
        store.activate("11111111-1111-1111-1111-111111111111")
        store.replace(
            decodeProfile(fullFixture()).toDomain(),
            listOf(FollowRequestV2(
                id = "33333333-3333-3333-3333-333333333333",
                requester = UserSummary("22222222-2222-2222-2222-222222222222", "A", "a", ""),
                status = "PENDING",
                createdAt = "2026-09-16T10:00:00Z",
                resolvedAt = null,
            )),
        )
        store.activate("44444444-4444-4444-4444-444444444444")
        assertNull(store.profile)
        assertTrue(store.incomingRequests.isEmpty())
    }

    private fun decodeProfile(value: String) = json.decodeFromString<ProfileV2Dto>(value)

    private fun identityOnlyFixture() = """
        {
          "id":"22222222-2222-2222-2222-222222222222",
          "username":"private_user",
          "displayName":"Private User",
          "avatarUrl":null,
          "bio":"Short bio",
          "profileVisibility":"PRIVATE",
          "fullProfile":false,
          "relationship":{"state":"REQUEST_PENDING","followsYou":false,"canFollow":false,"canCancelRequest":true},
          "cityCount":null,
          "countryCount":null,
          "followerCount":null,
          "followingCount":null,
          "friendCount":null,
          "visibleExperienceCount":null
        }
    """.trimIndent()

    private fun fullFixture() = identityOnlyFixture()
        .replace("\"fullProfile\":false", "\"fullProfile\":true")
        .replace("REQUEST_PENDING", "FRIENDS")
        .replace("\"cityCount\":null", "\"cityCount\":5")
        .replace("\"countryCount\":null", "\"countryCount\":2")
        .replace("\"followerCount\":null", "\"followerCount\":10")
        .replace("\"followingCount\":null", "\"followingCount\":4")
        .replace("\"friendCount\":null", "\"friendCount\":3")
        .replace("\"visibleExperienceCount\":null", "\"visibleExperienceCount\":8")

    private fun aggregateFixture() = """
        {
          "place":{"id":"20000000-0000-0000-0000-000000000001","name":"Foça","category":"BEACH","city":"İzmir","region":"Aegean","country":"Türkiye","coverImage":""},
          "visibleExperienceCount":1,
          "communityContributionCount":4,
          "feelings":[{"code":"BAYILDIM","contributionCount":2}],
          "dimensions":[{"key":"SCENERY","contributionCount":2,"numericAverage":8.0,"legacyNumericContributionCount":1,"semanticDistribution":[{"state":"VERY_GOOD","contributionCount":1}]}],
          "practicalSignals":[{"code":"ARRIVE_EARLY","contributionCount":1,"eligibleContributionDenominator":4}]
        }
    """.trimIndent()
}
