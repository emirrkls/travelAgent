package com.emirrkls.phokarta.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emirrkls.phokarta.core.auth.AuthenticatedUser
import com.emirrkls.phokarta.core.auth.SessionManager
import com.emirrkls.phokarta.core.auth.TokenStore
import com.emirrkls.phokarta.core.database.TravelDatabase
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.core.time.EpochClock
import com.emirrkls.phokarta.feature.rating.VisitDraft
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisitDraftRepositoryInstrumentedTest {
    private lateinit var database: TravelDatabase
    private lateinit var sessionManager: SessionManager
    private var nowMillis = 10_000L
    private lateinit var repository: RoomVisitDraftRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, TravelDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessionManager = SessionManager(
            TokenStore(context.getSharedPreferences("draft-repo-test", Context.MODE_PRIVATE)),
        )
        repository = RoomVisitDraftRepository(
            dao = database.visitDraftDao(),
            sessionManager = sessionManager,
            clock = EpochClock { nowMillis },
        )
        loginAs(USER_A)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun userAndPlaceIsolation() = runBlocking {
        repository.saveDraft(
            PLACE_X,
            VisitDraft(
                overallScore = 8.5f,
                dimensions = mapOf(RatingDimension.SEA to 9f),
                publicReview = "A at X",
                privateMemory = "secret",
                visitDate = LocalDate.of(2026, 6, 1),
                visibility = Visibility.FRIENDS,
            ),
            USER_A,
        )
        repository.saveDraft(PLACE_Y, VisitDraft(publicReview = "A at Y"), USER_A)

        loginAs(USER_B)
        assertNull(repository.getDraft(PLACE_X))
        assertFalse(repository.hasDraft(PLACE_X))
        assertFalse(repository.observeHasDraft(PLACE_X).first())

        loginAs(USER_A)
        val restored = repository.getDraft(PLACE_X)!!
        assertEquals("A at X", restored.publicReview)
        assertEquals("secret", restored.privateMemory)
        assertEquals(Visibility.FRIENDS, restored.visibility)
        assertEquals(9f, restored.dimensions[RatingDimension.SEA]!!, 0.01f)
        assertEquals("A at Y", repository.getDraft(PLACE_Y)!!.publicReview)
    }

    @Test
    fun createdAtPreservedOnUpdateAndExpiryRemovesStale() = runBlocking {
        repository.saveDraft(PLACE_X, VisitDraft(publicReview = "first"), USER_A)
        val created = database.visitDraftDao().getDraft(USER_A, PLACE_X)!!.createdAtEpochMillis
        nowMillis = 11_000L
        repository.saveDraft(PLACE_X, VisitDraft(publicReview = "second"), USER_A)
        val updated = database.visitDraftDao().getDraft(USER_A, PLACE_X)!!
        assertEquals(created, updated.createdAtEpochMillis)
        assertEquals(11_000L, updated.updatedAtEpochMillis)

        nowMillis = updated.updatedAtEpochMillis + VisitDraftRepository.EXPIRY_MS + 1
        assertNull(repository.getDraft(PLACE_X))
        assertFalse(repository.hasDraft(PLACE_X))
    }

    @Test
    fun logoutKeepsDraftsForOriginalUser() = runBlocking {
        repository.saveDraft(PLACE_X, VisitDraft(publicReview = "mine"), USER_A)
        sessionManager.clearSession()
        assertNull(repository.getDraft(PLACE_X))

        loginAs(USER_A)
        assertEquals("mine", repository.getDraft(PLACE_X)!!.publicReview)
    }

    private fun loginAs(userId: String) {
        sessionManager.setAuthenticated(
            AuthenticatedUser(
                id = userId,
                email = "$userId@test.local",
                username = userId,
                displayName = userId,
                bio = "",
                avatarUrl = "",
            ),
            accessToken = "access",
            refreshToken = "refresh",
        )
    }

    companion object {
        private const val USER_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        private const val USER_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        private const val PLACE_X = "20000000-0000-0000-0000-000000000003"
        private const val PLACE_Y = "20000000-0000-0000-0000-000000000099"
    }
}
