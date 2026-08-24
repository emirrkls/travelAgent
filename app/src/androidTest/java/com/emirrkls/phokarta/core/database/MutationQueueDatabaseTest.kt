package com.emirrkls.phokarta.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emirrkls.phokarta.core.database.entity.MutationStateValue
import com.emirrkls.phokarta.core.database.entity.MutationTypeValue
import com.emirrkls.phokarta.core.database.entity.PendingMutationEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MutationQueueDatabaseTest {
    private lateinit var database: TravelDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), TravelDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    @Test fun claimFailureRetryAndSuccessDeletion() = runTest {
        val dao = database.pendingMutationDao()
        dao.insertMutation(mutation("m1", USER_A, 10))
        assertEquals(1, dao.claim("m1", 20))
        assertEquals(MutationStateValue.SYNCING, dao.get("m1")?.state)
        assertEquals(1, dao.markFailure("m1", 1, MutationStateValue.FAILED_RETRYABLE, "TIMEOUT", 30))
        assertEquals(1, dao.retry("m1", 40))
        assertEquals(MutationStateValue.PENDING, dao.get("m1")?.state)
        assertEquals(1, dao.deleteIfGeneration("m1", 1))
        assertNull(dao.get("m1"))
    }

    @Test fun staleSyncingRecoveryAndAccountFiltering() = runTest {
        val dao = database.pendingMutationDao()
        dao.insertMutation(mutation("a", USER_A, 10).copy(state = MutationStateValue.SYNCING))
        dao.insertMutation(mutation("b", USER_B, 10))
        assertEquals(1, dao.recoverStaleSyncing(50, 100))
        assertEquals(listOf("a"), dao.eligible(USER_A, 20).map { it.mutationId })
        assertEquals(listOf("b"), dao.eligible(USER_B, 20).map { it.mutationId })
    }

    @Test fun staleSavedAckCannotDeleteNewGeneration() = runTest {
        val dao = database.pendingMutationDao()
        dao.insertMutation(mutation("saved", USER_A, 10).copy(
            type = MutationTypeValue.SET_SAVED_STATE, desiredSaved = true,
        ))
        dao.upsertMutation(requireNotNull(dao.get("saved")).copy(
            generation = 2, desiredSaved = false, state = MutationStateValue.PENDING,
        ))
        assertEquals(0, dao.deleteIfGeneration("saved", 1))
        assertEquals(false, dao.get("saved")?.desiredSaved)
    }

    private fun mutation(id: String, user: String, now: Long) = PendingMutationEntity(
        id, user, MutationTypeValue.PUBLISH_VISIT, "place", MutationStateValue.PENDING,
        1, null, 0, now, now, null,
    )

    companion object {
        const val USER_A = "11111111-1111-1111-1111-111111111111"
        const val USER_B = "22222222-2222-2222-2222-222222222222"
    }
}
