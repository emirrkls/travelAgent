package com.emirrkls.phokarta.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emirrkls.phokarta.core.auth.AuthenticatedUser
import com.emirrkls.phokarta.core.auth.SessionManager
import com.emirrkls.phokarta.core.auth.TokenStore
import com.emirrkls.phokarta.core.data.RoomVisitDraftRepository
import com.emirrkls.phokarta.core.database.TravelDatabase
import com.emirrkls.phokarta.core.database.entity.MutationStateValue
import com.emirrkls.phokarta.core.database.entity.MutationTypeValue
import com.emirrkls.phokarta.core.database.entity.PendingMutationEntity
import com.emirrkls.phokarta.core.database.entity.PendingVisitDimensionScoreEntity
import com.emirrkls.phokarta.core.database.entity.PendingVisitPayloadEntity
import com.emirrkls.phokarta.core.database.entity.PendingVisitPhotoEntity
import com.emirrkls.phokarta.core.database.entity.VisitDraftEntity
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.core.time.EpochClock
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FailedVisitRecoveryRepositoryTest {
    private lateinit var database: TravelDatabase
    private lateinit var session: SessionManager
    private lateinit var draftRepository: RoomVisitDraftRepository
    private lateinit var repository: RoomOfflineMutationRepository

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, TravelDatabase::class.java)
            .allowMainThreadQueries().build()
        session = SessionManager(TokenStore(context.getSharedPreferences("recovery-test", Context.MODE_PRIVATE)))
        draftRepository = RoomVisitDraftRepository(
            database.visitDraftDao(), session, EpochClock { 5_000L },
        )
        repository = RoomOfflineMutationRepository(
            database,
            database.pendingMutationDao(),
            database.visitDraftDao(),
            draftRepository,
            database.savedPlaceDao(),
            session,
            EpochClock { 5_000L },
            object : MutationSyncScheduler { override fun schedule() = Unit },
        )
        login(USER_A)
    }

    @After fun tearDown() = database.close()

    @Test fun recoverCopiesPayloadDeletesMutationAndCreatesDraft() = runTest {
        seedFailedPermanent(MUTATION_M1, overall = 8.0, review = "Recover me", memory = "secret")

        assertEquals(RecoverFailedVisitResult.SUCCESS, repository.recoverFailedVisitForEditing(MUTATION_M1))
        assertNull(database.pendingMutationDao().get(MUTATION_M1))
        val draft = draftRepository.getDraft(PLACE)!!
        assertEquals(8f, draft.overallScore)
        assertEquals("Recover me", draft.publicReview)
        assertEquals("secret", draft.privateMemory)
        assertEquals(Visibility.FRIENDS, draft.visibility)
    }

    @Test fun recoverPreservesPhotosInSessionOverlay() = runTest {
        insertFailedVisit(
            MUTATION_M1,
            photos = listOf("https://example.test/photo.jpg"),
        )
        repository.recoverFailedVisitForEditing(MUTATION_M1)
        assertEquals(listOf("https://example.test/photo.jpg"), draftRepository.getDraft(PLACE)!!.photos)
    }

    @Test fun publishAfterRecoveryUsesNewMutationId() = runTest {
        insertFailedVisit(MUTATION_M1)
        repository.recoverFailedVisitForEditing(MUTATION_M1)
        val draft = draftRepository.getDraft(PLACE)!!
        val m2 = repository.commitVisit(
            Visit(
                "local", USER_A, PLACE, draft.visitDate, draft.overallScore.toDouble(),
                draft.dimensions.mapValues { it.value.toDouble() },
                draft.publicReview, draft.privateMemory,
                photos = draft.photos, visibility = draft.visibility,
            ),
        )
        assertNotEquals(MUTATION_M1, m2)
    }

    @Test fun existingMeaningfulDraftReturnsConflictUnlessReplace() = runTest {
        insertFailedVisit(MUTATION_M1)
        database.visitDraftDao().upsertDraft(
            VisitDraftEntity(USER_A, PLACE, 7f, "existing", "", 10, "PUBLIC", false, 10, 10),
        )
        assertEquals(
            RecoverFailedVisitResult.EXISTING_DRAFT_CONFLICT,
            repository.recoverFailedVisitForEditing(MUTATION_M1),
        )
        assertEquals(
            RecoverFailedVisitResult.SUCCESS,
            repository.recoverFailedVisitForEditing(MUTATION_M1, replaceExisting = true),
        )
        assertEquals("review", draftRepository.getDraft(PLACE)!!.publicReview)
    }

    @Test fun wrongUserCannotRecoverOrRemove() = runTest {
        insertFailedVisit(MUTATION_M1)
        login(USER_B)
        assertEquals(RecoverFailedVisitResult.NOT_OWNER, repository.recoverFailedVisitForEditing(MUTATION_M1))
        assertEquals(RemoveFailedVisitResult.NOT_OWNER, repository.removeFailedVisit(MUTATION_M1))
        login(USER_A)
        assertEquals(MUTATION_M1, database.pendingMutationDao().get(MUTATION_M1)?.mutationId)
    }

    @Test fun invalidStateRejectsRecoveryAndRemove() = runTest {
        insertFailedVisit(MUTATION_M1, state = MutationStateValue.PENDING)
        assertEquals(RecoverFailedVisitResult.INVALID_STATE, repository.recoverFailedVisitForEditing(MUTATION_M1))
        assertEquals(RemoveFailedVisitResult.INVALID_STATE, repository.removeFailedVisit(MUTATION_M1))
    }

    @Test fun removeDeletesFailedVisitOnly() = runTest {
        insertFailedVisit(MUTATION_M1)
        assertEquals(RemoveFailedVisitResult.SUCCESS, repository.removeFailedVisit(MUTATION_M1))
        assertNull(database.pendingMutationDao().get(MUTATION_M1))
        assertNull(draftRepository.getDraft(PLACE))
    }

    @Test fun staleWorkerDoesNotSyncDeletedMutation() = runTest {
        insertFailedVisit(MUTATION_M1)
        repository.recoverFailedVisitForEditing(MUTATION_M1)
        assertTrue(database.pendingMutationDao().eligible(USER_A, 20).isEmpty())
        assertNull(database.pendingMutationDao().getVisit(MUTATION_M1))
    }

    private suspend fun seedFailedPermanent(
        mutationId: String,
        overall: Double = 8.5,
        review: String = "review",
        memory: String = "private",
    ) {
        insertFailedVisit(mutationId, overall, review, memory)
    }

    private suspend fun insertFailedVisit(
        mutationId: String,
        overall: Double = 8.5,
        review: String = "review",
        memory: String = "private",
        state: String = MutationStateValue.FAILED_PERMANENT,
        photos: List<String> = emptyList(),
    ) {
        val dao = database.pendingMutationDao()
        dao.insertMutation(
            PendingMutationEntity(
                mutationId, USER_A, MutationTypeValue.PUBLISH_VISIT, mutationId,
                state, 1, null, 1, 1_000, 1_000, "VALIDATION",
            ),
        )
        dao.insertVisitPayload(
            PendingVisitPayloadEntity(
                mutationId, PLACE, LocalDate.of(2026, 8, 20).toEpochDay(),
                overall, review, memory, Visibility.FRIENDS.name,
            ),
        )
        dao.insertVisitDimensions(
            listOf(PendingVisitDimensionScoreEntity(mutationId, RatingDimension.SEA.name, 9.0)),
        )
        if (photos.isNotEmpty()) {
            dao.insertVisitPhotos(photos.mapIndexed { index, url ->
                PendingVisitPhotoEntity(mutationId, index, url)
            })
        }
    }

    private fun login(id: String) = session.setAuthenticated(
        AuthenticatedUser(id, "$id@test.local", id, id, "", ""), "access", "refresh",
    )

    companion object {
        const val USER_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val USER_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val PLACE = "20000000-0000-0000-0000-000000000003"
        const val MUTATION_M1 = "11111111-1111-1111-1111-111111111111"
    }
}
