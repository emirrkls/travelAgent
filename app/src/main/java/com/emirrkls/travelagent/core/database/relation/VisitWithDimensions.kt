package com.emirrkls.travelagent.core.database.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.emirrkls.travelagent.core.database.entity.VisitDimensionScoreEntity
import com.emirrkls.travelagent.core.database.entity.VisitEntity

data class VisitWithDimensions(
    @Embedded val visit: VisitEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "visitId",
    )
    val dimensions: List<VisitDimensionScoreEntity>,
)
