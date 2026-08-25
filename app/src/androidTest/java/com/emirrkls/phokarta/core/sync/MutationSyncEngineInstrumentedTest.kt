package com.emirrkls.phokarta.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emirrkls.phokarta.core.auth.AuthenticatedUser
import com.emirrkls.phokarta.core.auth.SessionManager
import com.emirrkls.phokarta.core.auth.TokenStore
import com.emirrkls.phokarta.core.data.RoomVisitDraftRepository
import com.emirrkls.phokarta.core.data.ActivityFeedInvalidator
import com.emirrkls.phokarta.core.data.RoomLocalUserStateDataSource
import com.emirrkls.phokarta.core.database.TravelDatabase
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.core.network.NetworkError
import com.emirrkls.phokarta.core.network.RemoteResult
import com.emirrkls.phokarta.core.network.model.*
import com.emirrkls.phokarta.core.network.source.SavedPlaceRemoteDataSource
import com.emirrkls.phokarta.core.network.source.VisitRemoteDataSource
import com.emirrkls.phokarta.core.time.EpochClock
import com.emirrkls.phokarta.core.media.MediaFileMutationLock
import com.emirrkls.phokarta.core.media.VisitMediaStore
import com.emirrkls.phokarta.core.network.source.MediaRemoteDataSource
import com.emirrkls.phokarta.core.network.source.DirectMediaUploader
import com.emirrkls.phokarta.core.network.model.MediaUploadIntentRequestDto
import com.emirrkls.phokarta.core.network.model.MediaUploadIntentResponseDto
import com.emirrkls.phokarta.core.network.model.MediaStateDto
import com.emirrkls.phokarta.core.network.model.MediaAccessDto
import okhttp3.OkHttpClient
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MutationSyncEngineInstrumentedTest {
    private lateinit var database: TravelDatabase
    private lateinit var session: SessionManager
    private lateinit var queue: RoomOfflineMutationRepository
    private lateinit var visits: FakeVisits
    private lateinit var saved: FakeSaved
    private lateinit var engine: MutationSyncEngine

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, TravelDatabase::class.java)
            .allowMainThreadQueries().build()
        session = SessionManager(TokenStore(context.getSharedPreferences("sync-engine-test", Context.MODE_PRIVATE)))
        session.setAuthenticated(AuthenticatedUser(USER, "u@test", "u", "U", "", ""), "access", "refresh")
        queue = RoomOfflineMutationRepository(
            database, database.pendingMutationDao(), database.visitDraftDao(),
            RoomVisitDraftRepository(
                database.visitDraftDao(), session, EpochClock { 1_000 }, VisitMediaStore(context),
                MediaFileMutationLock(),
            ),
            database.savedPlaceDao(),
            session, EpochClock { 1_000 }, object : MutationSyncScheduler { override fun schedule() = Unit },
            VisitMediaStore(context),
        )
        visits = FakeVisits()
        saved = FakeSaved()
        engine = MutationSyncEngine(
            database, database.pendingMutationDao(), database.savedPlaceDao(), visits, saved,
            RoomLocalUserStateDataSource(database.visitDao(), database.savedPlaceDao(), database.collectionDao(), session),
            session, EpochClock { 2_000 }, ActivityFeedInvalidator(),
            object : MediaRemoteDataSource {
                override suspend fun createIntent(request: MediaUploadIntentRequestDto) =
                    RemoteResult.Success(MediaUploadIntentResponseDto("unused", "READY"))
                override suspend fun confirm(mediaId: String) =
                    RemoteResult.Success(MediaStateDto(mediaId, "READY"))
                override suspend fun access(mediaId: String) =
                    RemoteResult.Success(MediaAccessDto("https://example.test/$mediaId", "2026-08-25T12:00:00Z"))
            },
            DirectMediaUploader(OkHttpClient()),
            VisitMediaStore(context),
        )
    }

    @After fun tearDown() = database.close()

    @Test fun lostResponseRetryReusesMutationIdAndReconcilesCanonicalVisit() = runTest {
        val mutationId = queue.commitVisit(visit())
        visits.result = RemoteResult.Failure(NetworkError.Connection)
        assertTrue(engine.drain().retryableFailure)
        assertEquals(mutationId, visits.requests.single().clientMutationId)

        visits.result = RemoteResult.Success(ownerVisit())
        assertFalse(engine.drain().retryableFailure)
        assertEquals(listOf(mutationId, mutationId), visits.requests.map { it.clientMutationId })
        assertEquals(listOf(VISIT_ID), database.visitDao().observeVisitsWithDimensions(USER).first().map { it.visit.id })
        assertTrue(database.pendingMutationDao().eligible(USER, 20).isEmpty())
    }

    @Test fun visitFailureDoesNotBlockIndependentSavedMutation() = runTest {
        queue.commitVisit(visit())
        queue.toggleSaved(PLACE)
        visits.result = RemoteResult.Failure(NetworkError.Timeout)
        saved.result = RemoteResult.Success(SavedPlaceDto(summary(), "2026-08-24T10:00:00Z"))

        val result = engine.drain()

        assertTrue(result.retryableFailure)
        assertEquals(2, result.processed)
        assertTrue(database.savedPlaceDao().getSavedPlace(USER, PLACE) != null)
        assertEquals(1, database.pendingMutationDao().eligible(USER, 20).size)
    }

    @Test fun legacyPhotoIsFailedPermanentlyWithoutCallingCreateVisit() = runTest {
        val mutationId = queue.commitVisit(
            visit().copy(photos = listOf("https://legacy.invalid/photo.jpg")),
        )

        val result = engine.drain()

        assertFalse(result.retryableFailure)
        assertTrue(visits.requests.isEmpty())
        val mutation = database.pendingMutationDao().get(mutationId)!!
        assertEquals("FAILED_PERMANENT", mutation.state)
        assertEquals("LEGACY_MEDIA_RESELECT_REQUIRED", mutation.lastErrorCategory)
    }

    private fun visit() = Visit(
        "local", USER, PLACE, LocalDate.of(2026, 8, 20), 8.5, emptyMap(),
        "review", "private", visibility = Visibility.PRIVATE,
    )

    private fun ownerVisit() = VisitOwnerDto(
        VISIT_ID, summary(), "2026-08-20", 8.5, emptyList(), "review", "private",
        emptyList(), VisibilityDto.PRIVATE, VerificationStatusDto.UNVERIFIED,
    )

    private fun summary() = PlaceSummaryDto(
        PLACE, "Place", PlaceCategoryDto.BEACH, "", "Bodrum", "Muğla", "Türkiye",
        37.0, 27.0, 2, 8.0, 1,
    )

    private class FakeVisits : VisitRemoteDataSource {
        val requests = mutableListOf<CreateVisitDto>()
        var result: RemoteResult<VisitOwnerDto> = RemoteResult.Failure(NetworkError.Connection)
        override suspend fun create(request: CreateVisitDto): RemoteResult<VisitOwnerDto> {
            requests += request
            return result
        }
        override suspend fun ownerVisits(page: Int, size: Int): RemoteResult<PageResponseDto<VisitOwnerDto>> = error("unused")
        override suspend fun publicReviews(placeId: String, scope: String?, page: Int, size: Int): RemoteResult<PageResponseDto<PublicVisitDto>> = error("unused")
        override suspend fun publicActivity(scope: String?, page: Int, size: Int): RemoteResult<PageResponseDto<PublicActivityDto>> = error("unused")
        override suspend fun friendsSummary(placeId: String): RemoteResult<FriendPlaceSummaryDto> = error("unused")
    }

    private class FakeSaved : SavedPlaceRemoteDataSource {
        var result: RemoteResult<SavedPlaceDto> = RemoteResult.Failure(NetworkError.Connection)
        override suspend fun list(page: Int, size: Int): RemoteResult<PageResponseDto<SavedPlaceDto>> = error("unused")
        override suspend fun save(placeId: String): RemoteResult<SavedPlaceDto> = result
        override suspend fun remove(placeId: String): RemoteResult<Unit> = RemoteResult.Success(Unit)
    }

    companion object {
        const val USER = "11111111-1111-1111-1111-111111111111"
        const val PLACE = "20000000-0000-0000-0000-000000000001"
        const val VISIT_ID = "30000000-0000-0000-0000-000000000001"
    }
}
