package com.emirrkls.travelagent.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "collection_places",
    primaryKeys = ["collectionId", "placeId"],
    indices = [Index("placeId")],
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CollectionPlaceCrossRef(
    val collectionId: String,
    val placeId: String,
)
