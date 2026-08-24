package com.emirrkls.phokarta.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emirrkls.phokarta.core.auth.AuthenticatedUser
import com.emirrkls.phokarta.core.auth.SessionManager
import com.emirrkls.phokarta.core.auth.TokenStore
import com.emirrkls.phokarta.core.database.TravelDatabase
import com.emirrkls.phokarta.core.database.entity.MutationTypeValue
import com.emirrkls.phokarta.core.database.entity.VisitDraftEntity
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.core.time.EpochClock
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineMutationRepositoryInstrumentedTest {
    private lateinit var database: TravelDatabase
    private lateinit var session: SessionManager
    private lateinit var scheduler: RecordingScheduler
    private lateinit var repository: RoomOfflineMutationRepository

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, TravelDatabase::class.java)
            .allowMainThreadQueries().build()
        session = SessionManager(TokenStore(context.getSharedPreferences("mutation-test", Context.MODE_PRIVATE)))
        scheduler = RecordingScheduler()
        repository = RoomOfflineMutationRepository(
            database, database.pendingMutationDao(), database.visitDraftDao(), database.savedPlaceDao(),
            session, EpochClock { 1_000L }, scheduler,
        )
        login(USER_A)
    }

    @After fun tearDown() = database.close()

    @Test fun draftToPendingVisitIsAtomicAndPayloadIsExact() = runTest {
        database.visitDraftDao().upsertDraft(VisitDraftEntity(
            USER_A, PLACE, 9f, "draft", "secret", 20_000, "FRIENDS", false, 10, 10,
        ))
        val visit = Visit("local", USER_A, PLACE, LocalDate.of(2026, 8, 20), 9.0,
            mapOf(RatingDimension.SEA to 9.5), "review", "private memory",
            photos = listOf("https://example.test/photo.jpg"), visibility = Visibility.FRIENDS)

        val mutationId = repository.commitVisit(visit)
        val pending = database.pendingMutationDao().getVisit(mutationId)!!

        assertNull(database.visitDraftDao().getDraft(USER_A, PLACE))
        assertEquals("private memory", pending.payload.privateMemory)
        assertEquals("FRIENDS", pending.payload.visibility)
        assertEquals(9.5, pending.dimensions.single().score, 0.0)
        assertEquals("https://example.test/photo.jpg", pending.photos.single().url)
        assertEquals(mutationId, repository.observePendingVisits().first().single().mutationId)
        assertEquals(1, scheduler.calls)
    }

    @Test fun savedRapidToggleCoalescesToOneLatestGeneration() = runTest {
        assertTrue(repository.toggleSaved(PLACE))
        assertFalse(repository.toggleSaved(PLACE))
        assertTrue(repository.toggleSaved(PLACE))

        val rows = database.pendingMutationDao().observeForUser(USER_A).first()
            .filter { it.type == MutationTypeValue.SET_SAVED_STATE }
        assertEquals(1, rows.size)
        assertEquals(true, rows.single().desiredSaved)
        assertEquals(3, rows.single().generation)
        assertTrue(database.savedPlaceDao().getSavedPlace(USER_A, PLACE) != null)
    }

    @Test fun logoutAndAccountSwitchRetainButHideOriginalQueue() = runTest {
        repository.toggleSaved(PLACE)
        session.clearSession()
        login(USER_B)
        assertTrue(database.pendingMutationDao().eligible(USER_B, 20).isEmpty())
        assertEquals(1, database.pendingMutationDao().eligible(USER_A, 20).size)
    }

    private fun login(id: String) = session.setAuthenticated(
        AuthenticatedUser(id, "$id@test.local", id, id, "", ""), "access", "refresh",
    )

    private class RecordingScheduler : MutationSyncScheduler {
        var calls = 0
        override fun schedule() { calls++ }
    }

    companion object {
        const val USER_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val USER_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val PLACE = "20000000-0000-0000-0000-000000000003"
    }
}
