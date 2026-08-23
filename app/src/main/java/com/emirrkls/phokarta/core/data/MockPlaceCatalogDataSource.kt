package com.emirrkls.phokarta.core.data

import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MockPlaceCatalogDataSource @Inject constructor() {
    val currentUser: User = Companion.currentUser

    fun observePlaces(): Flow<List<Place>> = flowOf(mockPlaces)
    suspend fun getPlace(id: String): Place? = mockPlaces.firstOrNull { it.id == id }

    companion object {
        const val CURRENT_USER_ID = "user-emir"

        val currentUser = User(
            CURRENT_USER_ID, "emir.roams", "Emircan Kaya",
            "https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=300",
            "Chasing clear water, tiny kitchens and roads with no rush.", 18, 6, 1240, 386,
            listOf("Beach", "Food", "Nature", "Hidden Gems", "Design Hotels"),
        )

        val mockPlaces = listOf(
            place("p1", "Sarnıç Cove", PlaceCategory.BEACH, "Bodrum", "Muğla", 9.2, 9.5, "3 friends loved the sunset", "https://images.unsplash.com/photo-1507525428034-b723cf961d3e?w=1200", "A tucked-away cove where pine-covered hills meet glassy Aegean water.", 3, mapOf("Sea" to 9.4, "Atmosphere" to 9.1, "Service" to 8.0, "Cleanliness" to 9.0, "Value" to 7.8, "Crowd" to 8.5)),
            place("p2", "Mimoza Table", PlaceCategory.RESTAURANT, "Yalıkavak", "Muğla", 8.9, 9.3, "Ece and 4 friends visited", "https://images.unsplash.com/photo-1414235077428-338989a2e8c0?w=1200", "Seasonal Aegean plates served in a breezy stone courtyard.", 4, mapOf("Food" to 9.3, "Service" to 8.7, "Atmosphere" to 9.4, "Value" to 7.7, "Presentation" to 9.1)),
            place("p3", "Kaktüs Coffee Lab", PlaceCategory.CAFE, "Alaçatı", "İzmir", 8.6, 8.8, "Popular with design lovers", "https://images.unsplash.com/photo-1501339847302-ac426a4a7cbb?w=1200", "A sunlit micro-roastery behind Alaçatı's quieter lanes.", 2, mapOf("Food" to 8.4, "Service" to 8.8, "Atmosphere" to 9.2, "Value" to 8.0, "Presentation" to 8.9)),
            place("p4", "Avlu 1923", PlaceCategory.RESTAURANT, "İstanbul", "İstanbul", 9.0, 9.1, "Ahmet saved this", "https://images.unsplash.com/photo-1517248135467-4c7edcad34c4?w=1200", "A modern neighborhood kitchen rooted in Anatolian produce.", 3, mapOf("Food" to 9.2, "Service" to 8.9, "Atmosphere" to 9.0, "Value" to 8.4, "Presentation" to 9.3)),
            place("p5", "Taş Otel Kaş", PlaceCategory.HOTEL, "Kaş", "Antalya", 8.8, 9.0, "2 friends stayed here", "https://images.unsplash.com/photo-1566073771259-6a8506099945?w=1200", "Calm rooms, bougainvillea balconies and a view over the old harbor.", 3, mapOf("Cleanliness" to 9.1, "Location" to 9.6, "Room" to 8.5, "Service" to 9.0, "Breakfast" to 8.8, "Value" to 8.0)),
            place("p6", "Limon Roof", PlaceCategory.NIGHTLIFE, "Çeşme", "İzmir", 8.4, 8.7, "Trending among friends", "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=1200", "Low-key rooftop sessions with citrus cocktails and Aegean night air.", 3, mapOf("Drinks" to 8.7, "Music" to 9.0, "Atmosphere" to 9.1, "Service" to 7.9, "Value" to 7.4)),
            place("p7", "Lycian Sunset Trail", PlaceCategory.NATURE, "Fethiye", "Muğla", 9.3, 9.4, "Loved by hikers you follow", "https://images.unsplash.com/photo-1464822759023-fed622ff2c3b?w=1200", "A coastal path through cedar and ancient stone, ending above the sea.", 1, mapOf("Scenery" to 9.7, "Access" to 7.9, "Cleanliness" to 8.8, "Tranquility" to 9.5)),
            place("p8", "Kelebek Bay Club", PlaceCategory.BEACH, "Kaş", "Antalya", 8.7, 9.0, "Saved by 7 friends", "https://images.unsplash.com/photo-1473116763249-2faaef81ccda?w=1200", "A relaxed swimming deck and small kitchen hidden below the coastal road.", 3, mapOf("Sea" to 9.3, "Atmosphere" to 8.9, "Service" to 8.2, "Cleanliness" to 8.8, "Value" to 7.6, "Crowd" to 8.1)),
            place("p9", "Perge After Hours", PlaceCategory.ATTRACTION, "Antalya", "Antalya", 9.1, 8.9, "A hidden-gem pick", "https://images.unsplash.com/photo-1524230572899-a752b3835840?w=1200", "A small-group twilight walk through the ancient city's monumental streets.", 2, mapOf("Experience" to 9.5, "Access" to 8.3, "Atmosphere" to 9.6, "Value" to 8.5)),
            place("p10", "Rüzgâr Sailing", PlaceCategory.ACTIVITY, "Türkbükü", "Muğla", 8.9, 9.2, "Ahmet rated this 9.4", "https://images.unsplash.com/photo-1530789253388-582c481c54b0?w=1200", "An unhurried afternoon sail between quiet northern Bodrum coves.", 4, mapOf("Experience" to 9.4, "Safety" to 9.2, "Guide" to 9.1, "Value" to 7.9)),
        )

        private fun place(
            id: String,
            name: String,
            category: PlaceCategory,
            city: String,
            region: String,
            score: Double,
            friends: Double,
            signal: String,
            image: String,
            description: String,
            price: Int,
            breakdown: Map<String, Double>,
        ) = Place(
            id, name, description, category, listOf(category.label),
            37.03 + id.drop(1).toInt() * .01,
            27.42 + id.drop(1).toInt() * .02,
            city, region, "Türkiye", "$city, $region", image, listOf(image, image), price, score, friends,
            (score + friends) / 2, 120 + id.drop(1).toInt() * 83,
            breakdown.mapKeys { RatingDimension.fromStoredKey(it.key)!! }, signal,
        )
    }
}
