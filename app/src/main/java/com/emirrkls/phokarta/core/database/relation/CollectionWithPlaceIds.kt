package com.emirrkls.phokarta.core.database.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.emirrkls.phokarta.core.database.entity.CollectionEntity
import com.emirrkls.phokarta.core.database.entity.CollectionPlaceCrossRef

data class CollectionWithPlaceIds(
    @Embedded val collection: CollectionEntity,
    @Relation(
        entity = CollectionPlaceCrossRef::class,
        parentColumn = "id",
        entityColumn = "collectionId",
        projection = ["placeId"],
    )
    val placeIds: List<String>,
)
