package com.emirrkls.phokarta.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Controlled pre-production reset: mock IDs have no semantic 1:1 backend mapping.
        // Place references persisted after v2 must be backend UUID strings.
        db.execSQL("DELETE FROM visit_dimension_scores")
        db.execSQL("DELETE FROM visits")
        db.execSQL("DELETE FROM saved_places")
        db.execSQL("DELETE FROM collection_places")
        db.execSQL("DELETE FROM collections")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `cached_places` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `category` TEXT NOT NULL,
                `coverImage` TEXT NOT NULL,
                `city` TEXT NOT NULL,
                `region` TEXT NOT NULL,
                `country` TEXT NOT NULL,
                `latitude` REAL NOT NULL,
                `longitude` REAL NOT NULL,
                `priceLevel` INTEGER NOT NULL,
                `averageScore` REAL,
                `ratingCount` INTEGER NOT NULL,
                `updatedAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )""".trimIndent(),
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Prior saved_places had no owner column (single demo-user era). Drop and recreate
        // with composite ownership key; orphaned demo rows are not attributed to any account.
        db.execSQL("DROP TABLE IF EXISTS `saved_places`")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `saved_places` (
                `ownerUserId` TEXT NOT NULL,
                `placeId` TEXT NOT NULL,
                `savedAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`ownerUserId`, `placeId`)
            )
            """.trimIndent(),
        )
    }
}
