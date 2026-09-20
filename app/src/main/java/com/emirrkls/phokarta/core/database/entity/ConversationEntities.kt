package com.emirrkls.phokarta.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "conversation_entries",
    primaryKeys = ["ownerUserId", "id"],
    indices = [
        Index(value = ["ownerUserId", "experienceId", "parentEntryId", "createdAt"]),
        Index(value = ["ownerUserId", "clientMutationId"]),
    ],
)
data class ConversationEntryEntity(
    val ownerUserId: String,
    val id: String,
    val experienceId: String,
    val parentEntryId: String?,
    val type: String,
    val body: String,
    val authorId: String,
    val authorUsername: String,
    val authorDisplayName: String,
    val authorAvatarUrl: String?,
    val createdAt: String,
    val updatedAt: String,
    val edited: Boolean,
    val experienceAuthor: Boolean,
    val ownedByViewer: Boolean,
    val reportableByViewer: Boolean,
    val syncState: String,
    val clientMutationId: String?,
)

@Entity(
    tableName = "pending_conversation_payloads",
    foreignKeys = [ForeignKey(
        entity = PendingMutationEntity::class,
        parentColumns = ["mutationId"],
        childColumns = ["mutationId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class PendingConversationPayloadEntity(
    @androidx.room.PrimaryKey val mutationId: String,
    val experienceId: String,
    val targetEntryId: String?,
    val parentEntryId: String?,
    val entryType: String?,
    val body: String?,
    val localEntryId: String?,
)
