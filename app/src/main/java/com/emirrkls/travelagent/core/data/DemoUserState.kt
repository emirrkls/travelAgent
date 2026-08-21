package com.emirrkls.travelagent.core.data

import com.emirrkls.travelagent.core.model.Collection
import com.emirrkls.travelagent.core.model.Visit
import com.emirrkls.travelagent.core.model.Visibility
import java.time.LocalDate

object DemoUserState {
    val visits = listOf(
        Visit(
            id = "visit-old-1",
            userId = MockPlaceCatalogDataSource.CURRENT_USER_ID,
            placeId = "p4",
            visitedAt = LocalDate.of(2026, 5, 18),
            overallRating = 8.8,
            ratingDimensions = mapOf("Food" to 9.2, "Service" to 8.1, "Atmosphere" to 9.0),
            review = "Breakfast in the courtyard was worth the early start.",
            personalNote = "Order the herb omelette.",
        ),
        Visit(
            id = "visit-old-2",
            userId = MockPlaceCatalogDataSource.CURRENT_USER_ID,
            placeId = "p7",
            visitedAt = LocalDate.of(2025, 9, 4),
            overallRating = 9.1,
            ratingDimensions = mapOf("Scenery" to 9.6, "Access" to 7.8, "Tranquility" to 9.4),
            review = "A quiet trail with a cinematic finish.",
            personalNote = "Return at sunset.",
        ),
    )

    val savedPlaceIds = listOf("p2", "p5", "p8")

    val collections = listOf(
        Collection(
            id = "c1",
            userId = MockPlaceCatalogDataSource.CURRENT_USER_ID,
            title = "Bodrum, slowly",
            description = "Swims, long lunches and places worth the detour.",
            placeIds = listOf("p1", "p2", "p10"),
            visibility = Visibility.PUBLIC,
            coverImage = "https://images.unsplash.com/photo-1507525428034-b723cf961d3e?w=1200",
        ),
        Collection(
            id = "c2",
            userId = MockPlaceCatalogDataSource.CURRENT_USER_ID,
            title = "Want to Go",
            description = "The ever-growing shortlist.",
            placeIds = listOf("p5", "p8", "p9"),
            visibility = Visibility.PRIVATE,
            coverImage = "https://images.unsplash.com/photo-1566073771259-6a8506099945?w=1200",
        ),
        Collection(
            id = "c3",
            userId = "u3",
            title = "Istanbul date tables",
            description = "Warm rooms and memorable menus.",
            placeIds = listOf("p4", "p3"),
            visibility = Visibility.PUBLIC,
            coverImage = "https://images.unsplash.com/photo-1517248135467-4c7edcad34c4?w=1200",
        ),
        Collection(
            id = "c4",
            userId = MockPlaceCatalogDataSource.CURRENT_USER_ID,
            title = "Hidden Aegean",
            description = "Quiet finds from İzmir to Kaş.",
            placeIds = listOf("p3", "p7", "p8"),
            visibility = Visibility.FRIENDS,
            coverImage = "https://images.unsplash.com/photo-1464822759023-fed622ff2c3b?w=1200",
        ),
    )
}
