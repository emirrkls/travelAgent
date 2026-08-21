package com.emirrkls.travelagent.core.model

import java.time.LocalDate

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

    val ratingDimensions: List<String>
        get() = when (this) {
            BEACH -> listOf("Sea", "Atmosphere", "Service", "Cleanliness", "Value", "Crowd")
            RESTAURANT, CAFE -> listOf("Food", "Service", "Atmosphere", "Value", "Presentation")
            HOTEL -> listOf("Cleanliness", "Location", "Room", "Service", "Breakfast", "Value")
            BAR, NIGHTLIFE -> listOf("Drinks", "Music", "Atmosphere", "Service", "Value")
            ATTRACTION -> listOf("Experience", "Access", "Atmosphere", "Value")
            ACTIVITY -> listOf("Experience", "Safety", "Guide", "Value")
            NATURE -> listOf("Scenery", "Access", "Cleanliness", "Tranquility")
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
    val communityScore: Double,
    val friendsScore: Double,
    val similarUsersScore: Double,
    val ratingCount: Int,
    val ratingBreakdown: Map<String, Double>,
    val friendSignal: String? = null,
)

enum class Visibility { PRIVATE, FRIENDS, PUBLIC }
enum class VerificationStatus { UNVERIFIED, LOCATION_CONFIRMED }

data class Visit(
    val id: String,
    val userId: String,
    val placeId: String,
    val visitedAt: LocalDate,
    val overallRating: Double,
    val ratingDimensions: Map<String, Double>,
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
