package com.emirrkls.phokarta.core.auth

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emirrkls.phokarta.core.database.TravelDatabase
import com.emirrkls.phokarta.core.database.entity.CollectionEntity
import com.emirrkls.phokarta.core.database.entity.CollectionPlaceCrossRef
import com.emirrkls.phokarta.core.database.entity.MutationStateValue
import com.emirrkls.phokarta.core.database.entity.MutationTypeValue
import com.emirrkls.phokarta.core.database.entity.PendingMutationEntity
import com.emirrkls.phokarta.core.database.entity.SavedPlaceEntity
import com.emirrkls.phokarta.core.database.entity.VisitDraftEntity
import com.emirrkls.phokarta.core.database.entity.VisitEntity
import com.emirrkls.phokarta.core.media.VisitMediaStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountDeletionLocalPurgeInstrumentedTest {
    private lateinit var database: TravelDatabase
    private lateinit var context: Context
    private lateinit var store: VisitMediaStore
    private lateinit var purger: RoomLocalAccountPurger

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, TravelDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = VisitMediaStore(context)
        purger = RoomLocalAccountPurger(
            context,
            database,
            database.visitDao(),
            database.visitDraftDao(),
            database.savedPlaceDao(),
            database.collectionDao(),
            database.pendingMutationDao(),
            store,
        )
    }

    @After
    fun tearDown() {
        File(context.filesDir, "visit-media").deleteRecursively()
        database.close()
    }

    @Test
    fun purgeRemovesOwnerDataAndFilesButLeavesOtherUser() = runBlocking {
        seedUser(USER_A, "place-a")
        seedUser(USER_B, "place-b")
        val aFile = File(context.filesDir, "visit-media/${USER_A}/keep-a.bin")
        val bFile = File(context.filesDir, "visit-media/${USER_B}/keep-b.bin")
        aFile.parentFile?.mkdirs()
        bFile.parentFile?.mkdirs()
        aFile.writeText("a")
        bFile.writeText("b")

        purger.purge(USER_A)

        assertTrue(database.visitDao().getVisitsForPlace(USER_A, "place-a").isEmpty())
        assertEquals(0, database.visitDraftDao().getDimensionScores(USER_A, "place-a").size)
        assertEquals(null, database.visitDraftDao().getDraft(USER_A, "place-a"))
        assertEquals(null, database.savedPlaceDao().getSavedPlace(USER_A, "place-a"))
        assertEquals(null, database.collectionDao().getCollectionWithPlaceIds(USER_A, "col-a"))
        assertEquals(emptyList<PendingMutationEntity>(), database.pendingMutationDao().eligible(USER_A, 10))
        assertFalse(aFile.exists())
        assertFalse(File(context.filesDir, "visit-media/$USER_A").exists())

        assertEquals(1, database.visitDao().getVisitsForPlace(USER_B, "place-b").size)
        assertEquals(true, database.visitDraftDao().hasDraft(USER_B, "place-b"))
        assertEquals("place-b", database.savedPlaceDao().getSavedPlace(USER_B, "place-b")?.placeId)
        assertEquals("col-$USER_B", database.collectionDao().getCollectionWithPlaceIds(USER_B, "col-$USER_B")?.collection?.id)
        assertEquals(1, database.pendingMutationDao().eligible(USER_B, 10).size)
        assertTrue(bFile.exists())
    }

    private suspend fun seedUser(userId: String, placeId: String) {
        database.visitDao().upsertVisitWithDimensions(
            VisitEntity(
                id = "visit-$userId",
                userId = userId,
                placeId = placeId,
                visitedAtEpochDay = 20_000L,
                overallRating = 8.0,
                publicReview = "review",
                privateMemory = "secret",
                visibility = "PRIVATE",
                verificationStatus = "UNVERIFIED",
                createdAtEpochMillis = 1L,
            ),
            emptyList(),
        )
        database.visitDraftDao().upsertDraft(
            VisitDraftEntity(userId, placeId, 8f, "draft", "note", 20_000, "PRIVATE", false, 1, 1),
        )
        database.savedPlaceDao().insertSavedPlace(SavedPlaceEntity(userId, placeId, 1L))
        database.collectionDao().upsertCollection(
            CollectionEntity("col-$userId", userId, "list", "", "PRIVATE", "", 1, 1),
        )
        database.collectionDao().insertCollectionPlace(
            CollectionPlaceCrossRef("col-$userId", placeId),
        )
        database.pendingMutationDao().insertMutation(
            PendingMutationEntity(
                mutationId = "mut-$userId",
                userId = userId,
                type = MutationTypeValue.SET_SAVED_STATE,
                resourceKey = placeId,
                state = MutationStateValue.PENDING,
                generation = 1,
                desiredSaved = true,
                attemptCount = 0,
                createdAtEpochMillis = 1,
                updatedAtEpochMillis = 1,
                lastErrorCategory = null,
            ),
        )
    }

    companion object {
        private const val USER_A = "user-a-id"
        private const val USER_B = "user-b-id"
    }
}
