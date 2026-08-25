package com.emirrkls.phokarta.core.sync

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emirrkls.phokarta.core.auth.AuthenticatedUser
import com.emirrkls.phokarta.core.auth.SessionManager
import com.emirrkls.phokarta.core.auth.TokenStore
import com.emirrkls.phokarta.core.data.ActivityFeedInvalidator
import com.emirrkls.phokarta.core.data.RoomLocalUserStateDataSource
import com.emirrkls.phokarta.core.database.TravelDatabase
import com.emirrkls.phokarta.core.database.entity.MediaUploadState
import com.emirrkls.phokarta.core.database.entity.MutationStateValue
import com.emirrkls.phokarta.core.database.entity.MutationTypeValue
import com.emirrkls.phokarta.core.database.entity.PendingMutationEntity
import com.emirrkls.phokarta.core.database.entity.PendingVisitPayloadEntity
import com.emirrkls.phokarta.core.database.entity.PendingVisitPhotoEntity
import com.emirrkls.phokarta.core.media.VisitMediaStore
import com.emirrkls.phokarta.core.network.NetworkError
import com.emirrkls.phokarta.core.network.RemoteResult
import com.emirrkls.phokarta.core.network.model.*
import com.emirrkls.phokarta.core.network.source.*
import com.emirrkls.phokarta.core.time.EpochClock
import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaSyncCrashInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: TravelDatabase
    private lateinit var session: SessionManager
    private lateinit var store: VisitMediaStore
    private lateinit var media: StatefulMediaRemote
    private lateinit var uploader: CountingUploader
    private lateinit var visits: SuccessfulVisits

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
        database = openDatabase()
        session = SessionManager(
            TokenStore(context.getSharedPreferences("media-crash-test", Context.MODE_PRIVATE)),
        )
        session.setAuthenticated(
            AuthenticatedUser(USER, "u@test", "u", "U", "", ""),
            "access",
            "refresh",
        )
        store = VisitMediaStore(context)
        media = StatefulMediaRemote()
        uploader = CountingUploader()
        visits = SuccessfulVisits()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DB_NAME)
        File(context.filesDir, "visit-media/$USER").deleteRecursively()
    }

    @Test
    fun crashAfterPutReusesClientAndServerMediaIdentityAfterDatabaseReopen() = runTest {
        seedPhoto(MediaUploadState.LOCAL_ONLY, remoteMediaId = null)
        media.failNextConfirm = true

        val first = engine().drain()

        assertEquals(true, first.retryableFailure)
        assertEquals(1, uploader.putCount)
        val afterPut = database.pendingMutationDao().getVisitPhotos(MUTATION).single()
        assertEquals(MediaUploadState.INTENT_CREATED, afterPut.uploadState)
        assertEquals(MEDIA, afterPut.remoteMediaId)

        reopenDatabase()
        database.pendingMutationDao().retry(MUTATION, 3_000)
        val second = engine().drain()

        assertFalse(second.retryableFailure)
        assertEquals(setOf(CLIENT_MEDIA), media.clientIds.toSet())
        assertEquals(setOf(MEDIA), media.returnedMediaIds.toSet())
        assertEquals(2, uploader.putCount) // same presigned object key; re-PUT is safe
        assertNull(database.pendingMutationDao().get(MUTATION))
    }

    @Test
    fun crashAfterServerConfirmReadySkipsPutAndReusesSameIdentityOnRestart() = runTest {
        seedPhoto(MediaUploadState.INTENT_CREATED, remoteMediaId = MEDIA)
        // Represents a process death after confirm returned READY but before Room was updated.
        media.statusByClient[CLIENT_MEDIA] = "READY"
        reopenDatabase()

        val result = engine().drain()

        assertFalse(result.retryableFailure)
        assertEquals(listOf(CLIENT_MEDIA), media.clientIds)
        assertEquals(listOf(MEDIA), media.returnedMediaIds)
        assertEquals(0, uploader.putCount)
        assertNull(database.pendingMutationDao().get(MUTATION))
    }

    private suspend fun seedPhoto(uploadState: String, remoteMediaId: String?) {
        val relative = "visit-media/$USER/$CLIENT_MEDIA.jpg"
        val file = store.resolveOwned(USER, relative)!!
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        database.withTransaction {
            database.pendingMutationDao().insertMutation(
                PendingMutationEntity(
                    MUTATION, USER, MutationTypeValue.PUBLISH_VISIT, MUTATION,
                    MutationStateValue.PENDING, 1, null, 0, 1_000, 1_000, null,
                ),
            )
            database.pendingMutationDao().insertVisitPayload(
                PendingVisitPayloadEntity(
                    MUTATION, PLACE, 20_000, 8.0, "review", "memory", "PRIVATE",
                ),
            )
            database.pendingMutationDao().insertVisitPhotos(
                listOf(
                    PendingVisitPhotoEntity(
                        mutationId = MUTATION,
                        position = 0,
                        ownerUserId = USER,
                        clientMediaId = CLIENT_MEDIA,
                        localRelativePath = relative,
                        contentType = "image/jpeg",
                        byteSize = 3,
                        width = 1,
                        height = 1,
                        remoteMediaId = remoteMediaId,
                        uploadState = uploadState,
                        failureCategory = null,
                        legacyUrl = null,
                    ),
                ),
            )
        }
    }

    private fun engine() = MutationSyncEngine(
        database,
        database.pendingMutationDao(),
        database.savedPlaceDao(),
        visits,
        object : SavedPlaceRemoteDataSource {
            override suspend fun list(page: Int, size: Int) = error("unused")
            override suspend fun save(placeId: String) = error("unused")
            override suspend fun remove(placeId: String) = error("unused")
        },
        RoomLocalUserStateDataSource(
            database.visitDao(), database.savedPlaceDao(), database.collectionDao(), session,
        ),
        session,
        EpochClock { 2_000 },
        ActivityFeedInvalidator(),
        media,
        uploader,
        store,
    )

    private fun openDatabase(): TravelDatabase =
        Room.databaseBuilder(context, TravelDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()

    private fun reopenDatabase() {
        database.close()
        database = openDatabase()
    }

    private class StatefulMediaRemote : MediaRemoteDataSource {
        val clientIds = mutableListOf<String>()
        val returnedMediaIds = mutableListOf<String>()
        val statusByClient = mutableMapOf<String, String>()
        var failNextConfirm = false

        override suspend fun createIntent(
            request: MediaUploadIntentRequestDto,
        ): RemoteResult<MediaUploadIntentResponseDto> {
            clientIds += request.clientMediaId
            val status = statusByClient.getOrPut(request.clientMediaId) { "PENDING_UPLOAD" }
            returnedMediaIds += MEDIA
            return RemoteResult.Success(
                MediaUploadIntentResponseDto(
                    MEDIA,
                    status,
                    uploadUrl = if (status == "READY") null else "https://storage.test/$MEDIA",
                ),
            )
        }

        override suspend fun confirm(mediaId: String): RemoteResult<MediaStateDto> {
            if (failNextConfirm) {
                failNextConfirm = false
                return RemoteResult.Failure(NetworkError.Connection)
            }
            statusByClient[CLIENT_MEDIA] = "READY"
            return RemoteResult.Success(MediaStateDto(mediaId, "READY"))
        }

        override suspend fun access(mediaId: String) = error("unused")
    }

    private class CountingUploader : DirectMediaUploader(OkHttpClient()) {
        var putCount = 0
        override suspend fun put(
            url: String,
            headers: Map<String, String>,
            file: File,
            contentType: String,
            byteSize: Long,
        ): DirectUploadResult {
            putCount++
            return DirectUploadResult.Success
        }
    }

    private class SuccessfulVisits : VisitRemoteDataSource {
        override suspend fun create(request: CreateVisitDto) = RemoteResult.Success(
            VisitOwnerDto(
                VISIT,
                PlaceSummaryDto(
                    PLACE, "Place", PlaceCategoryDto.BEACH, "", "Bodrum", "Muğla", "Türkiye",
                    37.0, 27.0, 2, 8.0, 1,
                ),
                "2024-10-04",
                8.0,
                emptyList(),
                "review",
                "memory",
                emptyList(),
                VisibilityDto.PRIVATE,
                VerificationStatusDto.UNVERIFIED,
                media = listOf(VisitMediaDto(MEDIA, 0)),
            ),
        )
        override suspend fun ownerVisits(page: Int, size: Int) = error("unused")
        override suspend fun publicReviews(placeId: String, scope: String?, page: Int, size: Int) =
            error("unused")
        override suspend fun publicActivity(scope: String?, page: Int, size: Int) = error("unused")
        override suspend fun friendsSummary(placeId: String) = error("unused")
    }

    companion object {
        private const val DB_NAME = "media-sync-crash.db"
        private const val USER = "11111111-1111-1111-1111-111111111111"
        private const val PLACE = "22222222-2222-2222-2222-222222222222"
        private const val MUTATION = "33333333-3333-3333-3333-333333333333"
        private const val CLIENT_MEDIA = "44444444-4444-4444-4444-444444444444"
        private const val MEDIA = "55555555-5555-5555-5555-555555555555"
        private const val VISIT = "66666666-6666-6666-6666-666666666666"
    }
}
