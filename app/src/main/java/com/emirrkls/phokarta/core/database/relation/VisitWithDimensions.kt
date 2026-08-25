package com.emirrkls.phokarta.core.database.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.emirrkls.phokarta.core.database.entity.VisitDimensionScoreEntity
import com.emirrkls.phokarta.core.database.entity.VisitEntity
import com.emirrkls.phokarta.core.database.entity.VisitMediaEntity

data class VisitWithDimensions(
    @Embedded val visit: VisitEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "visitId",
    )
    val dimensions: List<VisitDimensionScoreEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "visitId",
    )
    val media: List<VisitMediaEntity> = emptyList(),
)
