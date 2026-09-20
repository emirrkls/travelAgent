package com.emirrkls.phokarta.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.emirrkls.phokarta.core.database.entity.ConversationEntryEntity
import com.emirrkls.phokarta.core.database.entity.PendingConversationPayloadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversation_entries WHERE ownerUserId = :userId AND experienceId = :experienceId ORDER BY createdAt DESC, id DESC")
    fun observe(userId: String, experienceId: String): Flow<List<ConversationEntryEntity>>

    @Query("SELECT * FROM conversation_entries WHERE ownerUserId = :userId AND experienceId = :experienceId ORDER BY createdAt DESC, id DESC")
    suspend fun entries(userId: String, experienceId: String): List<ConversationEntryEntity>

    @Query("SELECT * FROM conversation_entries WHERE ownerUserId = :userId AND id = :entryId LIMIT 1")
    suspend fun entry(userId: String, entryId: String): ConversationEntryEntity?

    @Upsert
    suspend fun upsertEntries(values: List<ConversationEntryEntity>)

    @Upsert
    suspend fun upsertEntry(value: ConversationEntryEntity)

    @Query("DELETE FROM conversation_entries WHERE ownerUserId = :userId AND experienceId = :experienceId AND syncState = 'SYNCED'")
    suspend fun deleteSyncedSnapshot(userId: String, experienceId: String)

    @Query("DELETE FROM conversation_entries WHERE ownerUserId = :userId AND (id = :entryId OR parentEntryId = :entryId)")
    suspend fun deleteThread(userId: String, entryId: String)

    @Query("DELETE FROM conversation_entries WHERE ownerUserId = :userId AND id = :entryId")
    suspend fun deleteEntry(userId: String, entryId: String)

    @Query("UPDATE conversation_entries SET body = :body, edited = 1, updatedAt = :updatedAt, syncState = :syncState WHERE ownerUserId = :userId AND id = :entryId")
    suspend fun updateBody(userId: String, entryId: String, body: String, updatedAt: String, syncState: String)

    @Query("UPDATE conversation_entries SET syncState = :state WHERE ownerUserId = :userId AND clientMutationId = :mutationId")
    suspend fun markState(userId: String, mutationId: String, state: String)

    @Query("UPDATE conversation_entries SET syncState = :state, clientMutationId = :mutationId WHERE ownerUserId = :userId AND id = :entryId")
    suspend fun markEntryState(userId: String, entryId: String, mutationId: String, state: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPayload(value: PendingConversationPayloadEntity)

    @Query("SELECT * FROM pending_conversation_payloads WHERE mutationId = :mutationId")
    suspend fun payload(mutationId: String): PendingConversationPayloadEntity?

    @Query("DELETE FROM conversation_entries WHERE ownerUserId = :userId")
    suspend fun deleteEntriesForUser(userId: String)
}
