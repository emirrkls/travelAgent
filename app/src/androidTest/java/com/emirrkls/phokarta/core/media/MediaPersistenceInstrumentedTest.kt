package com.emirrkls.phokarta.core.media

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emirrkls.phokarta.core.auth.AuthenticatedUser
import com.emirrkls.phokarta.core.auth.SessionManager
import com.emirrkls.phokarta.core.auth.TokenStore
import com.emirrkls.phokarta.core.data.RoomVisitDraftRepository
import com.emirrkls.phokarta.core.database.TravelDatabase
import com.emirrkls.phokarta.core.database.entity.*
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.core.network.RemoteResult
import com.emirrkls.phokarta.core.network.model.*
import com.emirrkls.phokarta.core.network.source.MediaRemoteDataSource
import com.emirrkls.phokarta.core.sync.*
import com.emirrkls.phokarta.core.time.EpochClock
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaPersistenceInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: TravelDatabase
    private lateinit var session: SessionManager
    private lateinit var store: VisitMediaStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
        database = openDatabase()
        session = SessionManager(
            TokenStore(context.getSharedPreferences("media-persistence-test", Context.MODE_PRIVATE)),
        )
        session.setAuthenticated(
            AuthenticatedUser(USER, "u@test", "u", "U", "", ""),
            "access",
            "refresh",
        )
        store = VisitMediaStore(context)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DB_NAME)
        context.filesDir.resolve("visit-media").deleteRecursively()
        VisitMediaStore.afterDurableFileCreated = null
    }

    @Test
    fun draftPhotoAndFieldsSurviveDatabaseAndRepositoryRecreation() = runTest {
        val repository = draftRepository()
        repository.saveDraft(
            PLACE,
            com.emirrkls.phokarta.feature.rating.VisitDraft(
                overallScore = 9f,
                publicReview = "review",
                privateMemory = "memory",
                visitDate = LocalDate.of(2026, 8, 20),
            ),
            USER,
        )
        database.visitDraftDao().upsertPhotos(listOf(draftPhoto()))

        reopenDatabase()
        val restored = draftRepository().getDraft(PLACE)!!

        assertEquals(9f, restored.overallScore)
        assertEquals("review", restored.publicReview)
        assertEquals("memory", restored.privateMemory)
        assertEquals(LocalDate.of(2026, 8, 20), restored.visitDate)
        assertEquals(listOf(RELATIVE_PATH), restored.photos)
    }

    @Test
    fun failedPhotoRecoverySurvivesRecreationAndM2KeepsImmutableSnapshot() = runTest {
        seedFailedMutationWithPhoto()
        val m1Repository = offlineRepository()

        assertEquals(
            RecoverFailedVisitResult.SUCCESS,
            m1Repository.recoverFailedVisitForEditing(M1),
        )
        reopenDatabase()
        val recreatedDrafts = draftRepository()
        val restored = recreatedDrafts.getDraft(PLACE)!!
        assertEquals(listOf(RELATIVE_PATH), restored.photos)

        val m2 = offlineRepository(recreatedDrafts).commitVisit(
            Visit(
                "local",
                USER,
                PLACE,
                restored.visitDate,
                restored.overallScore.toDouble(),
                emptyMap(),
                restored.publicReview,
                restored.privateMemory,
                photos = restored.photos,
                visibility = restored.visibility,
            ),
        )

        assertNotEquals(M1, m2)
        val pending = database.pendingMutationDao().getVisitPhotos(m2).single()
        assertEquals(CLIENT_MEDIA, pending.clientMediaId)
        assertEquals(RELATIVE_PATH, pending.localRelativePath)
        assertEquals(MediaUploadState.LOCAL_ONLY, pending.uploadState)
        assertTrue(store.resolveOwned(USER, RELATIVE_PATH)!!.isFile)
    }

    @Test
    fun legacyPhotoRecoverySurvivesRecreationAndM2UsesReselectedManagedMedia() = runTest {
        val legacyUrl = "https://legacy.invalid/photo.jpg"
        seedFailedLegacyMutation(legacyUrl)
        assertEquals(
            RecoverFailedVisitResult.SUCCESS,
            offlineRepository().recoverFailedVisitForEditing(M1),
        )

        reopenDatabase()
        val recreatedDrafts = draftRepository()
        val restored = recreatedDrafts.getDraft(PLACE)!!
        assertEquals("review", restored.publicReview)
        assertEquals("memory", restored.privateMemory)
        assertEquals(listOf(legacyUrl), restored.photos)

        recreatedDrafts.removePhoto(PLACE, legacyUrl, USER)
        val managed = draftPhoto()
        store.resolveOwned(USER, RELATIVE_PATH)!!.apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        database.visitDraftDao().upsertPhotos(listOf(managed))
        val m2 = offlineRepository(recreatedDrafts).commitVisit(
            Visit(
                "local", USER, PLACE, restored.visitDate, restored.overallScore.toDouble(),
                emptyMap(), restored.publicReview, restored.privateMemory,
                photos = listOf(RELATIVE_PATH), visibility = restored.visibility,
            ),
        )

        val pending = database.pendingMutationDao().getVisit(m2)!!
        assertEquals("review", pending.payload.publicReview)
        assertEquals("memory", pending.payload.privateMemory)
        assertEquals(null, pending.photos.single().legacyUrl)
        assertEquals(RELATIVE_PATH, pending.photos.single().localRelativePath)
    }

    @Test
    fun expiredCanonicalAccessRefreshIsOwnerScopedAndPersisted() = runTest {
        database.visitDao().upsertVisit(
            VisitEntity(
                VISIT, USER, PLACE, 20_000, 8.0, "", "", "PRIVATE", "UNVERIFIED", 1,
            ),
        )
        database.visitDao().upsertMedia(
            listOf(VisitMediaEntity(USER, VISIT, 0, MEDIA, "https://old.test/x", 10)),
        )
        val remote = object : MediaRemoteDataSource {
            var calls = 0
            override suspend fun access(mediaId: String): RemoteResult<MediaAccessDto> {
                calls++
                return RemoteResult.Success(
                    MediaAccessDto("https://fresh.test/x", "2026-08-25T12:00:00Z"),
                )
            }
            override suspend fun createIntent(request: MediaUploadIntentRequestDto) = error("unused")
            override suspend fun confirm(mediaId: String) = error("unused")
        }
        val repository = MediaAccessRepository(
            database.visitDao(), remote, session, EpochClock { 1_000 },
        )

        assertEquals("https://fresh.test/x", repository.accessUrl(VISIT, MEDIA))
        assertEquals(
            "https://fresh.test/x",
            database.visitDao().getMedia(USER, VISIT, MEDIA)!!.accessUrl,
        )
        assertEquals(1, remote.calls)

        session.setAuthenticated(
            AuthenticatedUser(OTHER_USER, "b@test", "b", "B", "", ""),
            "access-b",
            "refresh-b",
        )
        assertEquals(null, repository.accessUrl(VISIT, MEDIA))
        assertEquals(1, remote.calls)
    }

    @Test
    fun reconciliationRemovesOnlyUnreferencedMediaFilesAcrossAccounts() = runTest {
        val now = System.currentTimeMillis()
        val userAPath = "visit-media/$USER/a.jpg"
        val userBPath = "visit-media/$OTHER_USER/b.jpg"
        val orphanPath = "visit-media/$USER/orphan.jpg"
        val partialPath = "visit-media/$OTHER_USER/interrupted.jpg.part"
        val outside = context.filesDir.resolve("outside-media.txt").apply { writeText("keep") }
        listOf(userAPath, userBPath, orphanPath, partialPath).forEach { relative ->
            context.filesDir.resolve(relative).apply {
                parentFile!!.mkdirs()
                writeBytes(byteArrayOf(1))
            }
        }
        markStale(context.filesDir.resolve(orphanPath), now)
        markStale(context.filesDir.resolve(partialPath), now)
        database.visitDraftDao().upsertDraft(
            VisitDraftEntity(USER, PLACE, 8f, "A", "", 20_000, "PRIVATE", false, 1, 1),
        )
        database.visitDraftDao().upsertDraft(
            VisitDraftEntity(OTHER_USER, OTHER_PLACE, 7f, "B", "", 20_001, "PRIVATE", false, 1, 1),
        )
        database.visitDraftDao().upsertPhotos(
            listOf(
                VisitDraftPhotoEntity(
                    USER, PLACE, 0, "client-a", userAPath, "image/jpeg", 1,
                    null, null, null, MediaUploadState.LOCAL_ONLY, null,
                ),
                VisitDraftPhotoEntity(
                    OTHER_USER, OTHER_PLACE, 0, "client-b", userBPath, "image/jpeg", 1,
                    null, null, null, MediaUploadState.LOCAL_ONLY, null,
                ),
            ),
        )
        session.setAuthenticated(
            AuthenticatedUser(OTHER_USER, "b@test", "b", "B", "", ""),
            "access-b",
            "refresh-b",
        )

        val result = reconciler(EpochClock { now }).reconcile()

        assertTrue(context.filesDir.resolve(userAPath).isFile)
        assertTrue(context.filesDir.resolve(userBPath).isFile)
        assertTrue(!context.filesDir.resolve(orphanPath).exists())
        assertTrue(!context.filesDir.resolve(partialPath).exists())
        assertTrue(outside.isFile)
        assertEquals(2, result.retainedFiles)
        assertEquals(2, result.removedFiles)
        outside.delete()
    }

    @Test
    fun reconciliationKeepsYoungUnreferencedAndPartFilesUntilGraceExpires() = runTest {
        val now = System.currentTimeMillis()
        val youngOrphan = context.filesDir.resolve("visit-media/$USER/young.jpg")
        val youngPart = context.filesDir.resolve("visit-media/$USER/young.jpg.part")
        listOf(youngOrphan, youngPart).forEach { file ->
            file.parentFile!!.mkdirs()
            file.writeBytes(byteArrayOf(1))
            assertTrue(file.setLastModified(now))
        }

        val result = reconciler(EpochClock { now }).reconcile()

        assertTrue(youngOrphan.isFile)
        assertTrue(youngPart.isFile)
        assertEquals(2, result.retainedFiles)
        assertEquals(0, result.removedFiles)
    }

    @Test
    fun concurrentImportAndReconcileKeepsFileAndRoomRow() = runBlocking {
        val now = System.currentTimeMillis()
        val lock = MediaFileMutationLock()
        val repository = RoomVisitDraftRepository(
            database.visitDraftDao(), session, EpochClock { now }, store, lock,
        )
        repository.saveDraft(
            PLACE,
            com.emirrkls.phokarta.feature.rating.VisitDraft(overallScore = 8f),
            USER,
        )
        val sourceUri = insertTestJpeg()
        val fileCreated = CountDownLatch(1)
        val finishImport = CountDownLatch(1)
        VisitMediaStore.afterDurableFileCreated = {
            fileCreated.countDown()
            check(finishImport.await(10, TimeUnit.SECONDS)) { "import resume timed out" }
        }
        val imported = AtomicReference<MediaImportResult?>(null)

        val importJob = async(Dispatchers.IO) {
            imported.set(repository.importPhoto(PLACE, sourceUri, USER))
        }
        assertTrue(
            "import never materialized a file; result=${imported.get()}",
            fileCreated.await(10, TimeUnit.SECONDS),
        )
        assertEquals(0, database.visitDraftDao().getPhotos(USER, PLACE).size)

        val overlapping = MediaFileReconciler(
            context,
            database.visitDraftDao(),
            database.pendingMutationDao(),
            store,
            lock,
            EpochClock { now + MediaFileReconciler.STALE_FILE_GRACE_MS + 5_000L },
        )
        val reconcileJob = async(Dispatchers.IO) { overlapping.reconcile() }
        finishImport.countDown()
        importJob.await()
        reconcileJob.await()

        val result = imported.get()
        assertTrue("expected success, got $result", result is MediaImportResult.Success)
        val photo = (result as MediaImportResult.Success).photo
        val importedFile = store.resolveOwned(USER, photo.localRelativePath)
        assertTrue(importedFile != null && importedFile.isFile)
        assertEquals(1, database.visitDraftDao().getPhotos(USER, PLACE).size)
        assertEquals(photo.clientMediaId, database.visitDraftDao().getPhotos(USER, PLACE).single().clientMediaId)

        val later = overlapping.reconcile()
        assertTrue(importedFile!!.isFile)
        assertEquals(1, database.visitDraftDao().getPhotos(USER, PLACE).size)
        assertEquals(0, later.removedFiles)
        context.contentResolver.delete(sourceUri, null, null)
        Unit
    }

    private fun draftPhoto() = VisitDraftPhotoEntity(
        USER, PLACE, 0, CLIENT_MEDIA, RELATIVE_PATH, "image/jpeg", 3,
        1, 1, null, MediaUploadState.LOCAL_ONLY, null,
    )

    private suspend fun seedFailedMutationWithPhoto() {
        val file = store.resolveOwned(USER, RELATIVE_PATH)!!
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        database.withTransaction {
            database.pendingMutationDao().insertMutation(
                PendingMutationEntity(
                    M1, USER, MutationTypeValue.PUBLISH_VISIT, M1,
                    MutationStateValue.FAILED_PERMANENT, 1, null, 1, 1, 1, "VALIDATION",
                ),
            )
            database.pendingMutationDao().insertVisitPayload(
                PendingVisitPayloadEntity(
                    M1, PLACE, LocalDate.of(2026, 8, 20).toEpochDay(),
                    8.0, "review", "memory", "PRIVATE",
                ),
            )
            database.pendingMutationDao().insertVisitPhotos(
                listOf(
                    PendingVisitPhotoEntity(
                        M1, 0, USER, CLIENT_MEDIA, RELATIVE_PATH, "image/jpeg", 3,
                        1, 1, null, MediaUploadState.LOCAL_ONLY, null, null,
                    ),
                ),
            )
        }
    }

    private suspend fun seedFailedLegacyMutation(legacyUrl: String) {
        database.withTransaction {
            database.pendingMutationDao().insertMutation(
                PendingMutationEntity(
                    M1, USER, MutationTypeValue.PUBLISH_VISIT, M1,
                    MutationStateValue.FAILED_PERMANENT, 1, null, 0, 1, 1,
                    MediaFailureCategory.LEGACY_MEDIA_RESELECT_REQUIRED,
                ),
            )
            database.pendingMutationDao().insertVisitPayload(
                PendingVisitPayloadEntity(
                    M1, PLACE, LocalDate.of(2026, 8, 20).toEpochDay(),
                    8.0, "review", "memory", "PRIVATE",
                ),
            )
            database.pendingMutationDao().insertVisitPhotos(
                listOf(PendingVisitPhotoEntity(M1, 0, legacyUrl)),
            )
        }
    }

    private fun draftRepository(
        mediaStore: VisitMediaStore = store,
        lock: MediaFileMutationLock = MediaFileMutationLock(),
    ) = RoomVisitDraftRepository(
        database.visitDraftDao(), session, EpochClock { 5_000 }, mediaStore, lock,
    )

    private fun reconciler(
        clock: EpochClock,
        lock: MediaFileMutationLock = MediaFileMutationLock(),
        mediaStore: VisitMediaStore = store,
    ) = MediaFileReconciler(
        context,
        database.visitDraftDao(),
        database.pendingMutationDao(),
        mediaStore,
        lock,
        clock,
    )

    private fun markStale(file: File, now: Long) {
        val staleAt = now - MediaFileReconciler.STALE_FILE_GRACE_MS - 5_000L
        assertTrue(file.setLastModified(staleAt))
        assertTrue(now - file.lastModified() >= MediaFileReconciler.STALE_FILE_GRACE_MS)
    }

    @Suppress("DEPRECATION")
    private fun insertTestJpeg(): android.net.Uri {
        val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        val inserted = android.provider.MediaStore.Images.Media.insertImage(
            context.contentResolver,
            bitmap,
            "phokarta-race-${System.nanoTime()}",
            null,
        )
        require(!inserted.isNullOrBlank()) { "MediaStore insertImage failed" }
        return android.net.Uri.parse(inserted)
    }

    private fun offlineRepository(
        drafts: RoomVisitDraftRepository = draftRepository(),
    ) = RoomOfflineMutationRepository(
        database,
        database.pendingMutationDao(),
        database.visitDraftDao(),
        drafts,
        database.savedPlaceDao(),
        session,
        EpochClock { 5_000 },
        object : MutationSyncScheduler { override fun schedule() = Unit },
        store,
    )

    private fun openDatabase() =
        Room.databaseBuilder(context, TravelDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()

    private fun reopenDatabase() {
        database.close()
        database = openDatabase()
    }

    companion object {
        private const val DB_NAME = "media-persistence.db"
        private const val USER = "11111111-1111-1111-1111-111111111111"
        private const val OTHER_USER = "99999999-9999-9999-9999-999999999999"
        private const val PLACE = "22222222-2222-2222-2222-222222222222"
        private const val OTHER_PLACE = "22222222-2222-2222-2222-222222222223"
        private const val M1 = "33333333-3333-3333-3333-333333333333"
        private const val CLIENT_MEDIA = "44444444-4444-4444-4444-444444444444"
        private const val VISIT = "55555555-5555-5555-5555-555555555555"
        private const val MEDIA = "66666666-6666-6666-6666-666666666666"
        private const val RELATIVE_PATH =
            "visit-media/11111111-1111-1111-1111-111111111111/44444444-4444-4444-4444-444444444444.jpg"
    }
}
