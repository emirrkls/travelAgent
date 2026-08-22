package com.emirrkls.phokarta.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emirrkls.phokarta.core.database.entity.CollectionEntity
import com.emirrkls.phokarta.core.database.entity.CollectionPlaceCrossRef
import com.emirrkls.phokarta.core.database.entity.VisitDimensionScoreEntity
import com.emirrkls.phokarta.core.database.entity.VisitEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TravelDatabaseTest {
    private lateinit var database: TravelDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, TravelDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun twoVisitsForTheSamePlaceAreAppended() = runBlocking {
        val dao = database.visitDao()
        val visitA = visit("visit-a", "p1", createdAt = 100L)
        val visitB = visit("visit-b", "p1", createdAt = 200L)

        dao.upsertVisitWithDimensions(visitA, emptyList())
        dao.upsertVisitWithDimensions(visitB, emptyList())

        val stored = dao.getVisitsForPlace("p1")
        assertEquals(2, stored.size)
        assertEquals(setOf("visit-a", "visit-b"), stored.map { it.visit.id }.toSet())
    }

    @Test
    fun visitDimensionsReloadAndMissingDimensionsStayMissing() = runBlocking {
        val dao = database.visitDao()
        val scoredVisit = visit("visit-scored", "p2", createdAt = 100L)
        dao.upsertVisitWithDimensions(
            scoredVisit,
            listOf(
                VisitDimensionScoreEntity(scoredVisit.id, "Food", 9.1),
                VisitDimensionScoreEntity(scoredVisit.id, "Service", 8.4),
                VisitDimensionScoreEntity(scoredVisit.id, "Value", 7.8),
            ),
        )
        dao.upsertVisitWithDimensions(visit("visit-no-scores", "p3", createdAt = 200L), emptyList())

        val scored = dao.getVisitsForPlace("p2").single()
        val unscored = dao.getVisitsForPlace("p3").single()
        assertEquals(mapOf("Food" to 9.1, "Service" to 8.4, "Value" to 7.8), scored.dimensions.associate { it.dimensionKey to it.score })
        assertEquals(emptyList<VisitDimensionScoreEntity>(), unscored.dimensions)
    }

    @Test
    fun savedPlaceTogglePersistsAndRemoves() = runBlocking {
        val dao = database.savedPlaceDao()

        dao.setSaved("p4", saved = true, nowEpochMillis = 100L)
        assertEquals(setOf("p4"), dao.observeSavedPlaceIds().first().toSet())

        dao.setSaved("p4", saved = false, nowEpochMillis = 200L)
        assertNull(dao.getSavedPlace("p4"))
        assertEquals(emptyList<String>(), dao.observeSavedPlaceIds().first())
    }

    @Test
    fun collectionMembershipReloadsAndRejectsDuplicates() = runBlocking {
        val dao = database.collectionDao()
        dao.upsertCollection(collection("collection-a"))

        dao.insertCollectionPlace(CollectionPlaceCrossRef("collection-a", "p1"))
        dao.insertCollectionPlace(CollectionPlaceCrossRef("collection-a", "p2"))
        dao.insertCollectionPlace(CollectionPlaceCrossRef("collection-a", "p1"))

        val stored = dao.getCollectionWithPlaceIds("collection-a")
        assertEquals(setOf("p1", "p2"), stored?.placeIds?.toSet())
        assertEquals(2, dao.countCollectionPlaces("collection-a"))
    }

    @Test
    fun deletingVisitCascadesToDimensionScores() = runBlocking {
        val dao = database.visitDao()
        val visit = visit("visit-delete", "p5", createdAt = 100L)
        dao.upsertVisitWithDimensions(
            visit,
            listOf(VisitDimensionScoreEntity(visit.id, "Room", 8.9)),
        )

        dao.deleteVisit(visit)

        assertEquals(emptyList<Any>(), dao.getVisitsForPlace("p5"))
    }

    private fun visit(id: String, placeId: String, createdAt: Long) = VisitEntity(
        id = id,
        userId = "user-test",
        placeId = placeId,
        visitedAtEpochDay = 20_000L,
        overallRating = 8.5,
        publicReview = "Review",
        privateMemory = "Memory",
        visibility = "PUBLIC",
        verificationStatus = "UNVERIFIED",
        createdAtEpochMillis = createdAt,
    )

    private fun collection(id: String) = CollectionEntity(
        id = id,
        userId = "user-test",
        title = "Test collection",
        description = "",
        visibility = "PRIVATE",
        coverImage = "",
        createdAtEpochMillis = 100L,
        updatedAtEpochMillis = 100L,
    )
}
