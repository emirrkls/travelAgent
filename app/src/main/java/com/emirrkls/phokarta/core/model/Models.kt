package com.emirrkls.phokarta.core.model

import java.time.LocalDate

enum class RatingDimension(val apiKey: String, val label: String) {
    SEA("SEA", "Sea"),
    ATMOSPHERE("ATMOSPHERE", "Atmosphere"),
    SERVICE("SERVICE", "Service"),
    CLEANLINESS("CLEANLINESS", "Cleanliness"),
    VALUE("VALUE", "Value"),
    CROWD("CROWD", "Crowd"),
    FOOD("FOOD", "Food"),
    PRESENTATION("PRESENTATION", "Presentation"),
    LOCATION("LOCATION", "Location"),
    ROOM("ROOM", "Room"),
    BREAKFAST("BREAKFAST", "Breakfast"),
    DRINKS("DRINKS", "Drinks"),
    MUSIC("MUSIC", "Music"),
    EXPERIENCE("EXPERIENCE", "Experience"),
    ACCESS("ACCESS", "Access"),
    SAFETY("SAFETY", "Safety"),
    GUIDE("GUIDE", "Guide"),
    SCENERY("SCENERY", "Scenery"),
    TRANQUILITY("TRANQUILITY", "Tranquility");

    companion object {
        fun fromStoredKey(key: String): RatingDimension? =
            entries.firstOrNull { it.apiKey == key.uppercase() || it.label.equals(key, ignoreCase = true) }
    }
}

enum class PlaceCategory(val label: String) {
    BEACH("Beach"),
    RESTAURANT("Food"),
    CAFE("Cafe"),
    HOTEL("Hotel"),
    BAR("Bar"),
    NIGHTLIFE("Nightlife"),
    ATTRACTION("Culture"),
    ACTIVITY("Activity"),
    NATURE("Nature");

    val ratingDimensions: List<RatingDimension>
        get() = when (this) {
            BEACH -> listOf(RatingDimension.SEA, RatingDimension.ATMOSPHERE, RatingDimension.SERVICE, RatingDimension.CLEANLINESS, RatingDimension.VALUE, RatingDimension.CROWD)
            RESTAURANT, CAFE -> listOf(RatingDimension.FOOD, RatingDimension.SERVICE, RatingDimension.ATMOSPHERE, RatingDimension.VALUE, RatingDimension.PRESENTATION)
            HOTEL -> listOf(RatingDimension.CLEANLINESS, RatingDimension.LOCATION, RatingDimension.ROOM, RatingDimension.SERVICE, RatingDimension.BREAKFAST, RatingDimension.VALUE)
            BAR, NIGHTLIFE -> listOf(RatingDimension.DRINKS, RatingDimension.MUSIC, RatingDimension.ATMOSPHERE, RatingDimension.SERVICE, RatingDimension.VALUE)
            ATTRACTION -> listOf(RatingDimension.EXPERIENCE, RatingDimension.ACCESS, RatingDimension.ATMOSPHERE, RatingDimension.VALUE)
            ACTIVITY -> listOf(RatingDimension.EXPERIENCE, RatingDimension.SAFETY, RatingDimension.GUIDE, RatingDimension.VALUE)
            NATURE -> listOf(RatingDimension.SCENERY, RatingDimension.ACCESS, RatingDimension.CLEANLINESS, RatingDimension.TRANQUILITY)
        }
}

data class User(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String,
    val bio: String,
    val cityCount: Int,
    val countryCount: Int,
    val followersCount: Int,
    val followingCount: Int,
    val travelTaste: List<String>,
)

data class Place(
    val id: String,
    val name: String,
    val description: String,
    val category: PlaceCategory,
    val subcategories: List<String>,
    val latitude: Double,
    val longitude: Double,
    val city: String,
    val region: String,
    val country: String,
    val address: String,
    val coverImage: String,
    val photos: List<String>,
    val priceLevel: Int,
    val communityScore: Double?,
    val friendsScore: Double?,
    val similarUsersScore: Double?,
    val ratingCount: Int,
    val ratingBreakdown: Map<RatingDimension, Double>,
    val friendSignal: String? = null,
)

data class NearbyPlace(
    val place: Place,
    val distanceMeters: Double,
)

enum class Visibility { PRIVATE, FRIENDS, PUBLIC }
enum class VerificationStatus { UNVERIFIED, LOCATION_CONFIRMED }

data class Visit(
    val id: String,
    val userId: String,
    val placeId: String,
    val visitedAt: LocalDate,
    val overallRating: Double,
    val ratingDimensions: Map<RatingDimension, Double>,
    val review: String,
    val personalNote: String,
    val photos: List<String> = emptyList(),
    val visibility: Visibility = Visibility.PUBLIC,
    val verificationStatus: VerificationStatus = VerificationStatus.UNVERIFIED,
)

data class Collection(
    val id: String,
    val userId: String,
    val title: String,
    val description: String,
    val placeIds: List<String>,
    val visibility: Visibility,
    val coverImage: String,
)

data class ActivityItem(
    val id: String,
    val user: User,
    val message: String,
    val timeLabel: String,
    val placeId: String? = null,
    val collectionId: String? = null,
)
