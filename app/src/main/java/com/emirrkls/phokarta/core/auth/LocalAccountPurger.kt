package com.emirrkls.phokarta.core.auth

import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import com.emirrkls.phokarta.core.database.TravelDatabase
import com.emirrkls.phokarta.core.database.dao.CollectionDao
import com.emirrkls.phokarta.core.database.dao.PendingMutationDao
import com.emirrkls.phokarta.core.database.dao.SavedPlaceDao
import com.emirrkls.phokarta.core.database.dao.VisitDao
import com.emirrkls.phokarta.core.database.dao.VisitDraftDao
import com.emirrkls.phokarta.core.media.VisitMediaStore
import com.emirrkls.phokarta.core.sync.WorkManagerMutationSyncScheduler
import android.content.Context
import androidx.room.withTransaction
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

/**
 * Removes one account's local product data after the server has confirmed deletion
 * (or after a terminal auth failure that means the account is gone).
 *
 * Order: cancel sync → Room rows → local media files → image cache.
 * Callers capture [userId] before clearing the session/tokens.
 */
interface LocalAccountPurger {
    suspend fun purge(userId: String)
    fun purgeBlocking(userId: String)
}

object NoOpLocalAccountPurger : LocalAccountPurger {
    override suspend fun purge(userId: String) = Unit
    override fun purgeBlocking(userId: String) = Unit
}

@Singleton
@OptIn(ExperimentalCoilApi::class)
class RoomLocalAccountPurger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: TravelDatabase,
    private val visitDao: VisitDao,
    private val visitDraftDao: VisitDraftDao,
    private val savedPlaceDao: SavedPlaceDao,
    private val collectionDao: CollectionDao,
    private val pendingMutationDao: PendingMutationDao,
    private val visitMediaStore: VisitMediaStore,
) : LocalAccountPurger {

    override suspend fun purge(userId: String) {
        runCatching {
            WorkManager.getInstance(context)
                .cancelUniqueWork(WorkManagerMutationSyncScheduler.WORK_NAME)
        }
        database.withTransaction {
            visitDraftDao.deleteDraftsForUser(userId)
            pendingMutationDao.deleteMutationsForUser(userId)
            visitDao.deleteVisitsForUser(userId)
            savedPlaceDao.deleteSavedPlacesForOwner(userId)
            collectionDao.deleteCollectionPlacesForOwner(userId)
            collectionDao.deleteCollectionsForOwner(userId)
        }
        visitMediaStore.deleteAllOwned(userId)
        val imageLoader = context.imageLoader
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
    }

    override fun purgeBlocking(userId: String) {
        runBlocking { purge(userId) }
    }
}
