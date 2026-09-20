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

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `visit_drafts` (
                `userId` TEXT NOT NULL,
                `placeId` TEXT NOT NULL,
                `overallScore` REAL NOT NULL,
                `publicReview` TEXT NOT NULL,
                `privateMemory` TEXT NOT NULL,
                `visitedAtEpochDay` INTEGER NOT NULL,
                `visibility` TEXT NOT NULL,
                `dimensionsExpanded` INTEGER NOT NULL,
                `createdAtEpochMillis` INTEGER NOT NULL,
                `updatedAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`userId`, `placeId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_visit_drafts_updatedAtEpochMillis` " +
                "ON `visit_drafts` (`updatedAtEpochMillis`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `visit_draft_dimension_scores` (
                `userId` TEXT NOT NULL,
                `placeId` TEXT NOT NULL,
                `dimensionKey` TEXT NOT NULL,
                `score` REAL NOT NULL,
                PRIMARY KEY(`userId`, `placeId`, `dimensionKey`),
                FOREIGN KEY(`userId`, `placeId`) REFERENCES `visit_drafts`(`userId`, `placeId`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_visit_draft_dimension_scores_userId_placeId` " +
                "ON `visit_draft_dimension_scores` (`userId`, `placeId`)",
        )
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `pending_mutations` (`mutationId` TEXT NOT NULL, `userId` TEXT NOT NULL, `type` TEXT NOT NULL, `resourceKey` TEXT NOT NULL, `state` TEXT NOT NULL, `generation` INTEGER NOT NULL, `desiredSaved` INTEGER, `attemptCount` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, `lastErrorCategory` TEXT, PRIMARY KEY(`mutationId`))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_mutations_userId_state_createdAtEpochMillis` ON `pending_mutations` (`userId`, `state`, `createdAtEpochMillis`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_mutations_userId_type_resourceKey` ON `pending_mutations` (`userId`, `type`, `resourceKey`)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `pending_visit_payloads` (`mutationId` TEXT NOT NULL, `placeId` TEXT NOT NULL, `visitedAtEpochDay` INTEGER NOT NULL, `overallRating` REAL NOT NULL, `publicReview` TEXT NOT NULL, `privateMemory` TEXT NOT NULL, `visibility` TEXT NOT NULL, PRIMARY KEY(`mutationId`), FOREIGN KEY(`mutationId`) REFERENCES `pending_mutations`(`mutationId`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `pending_visit_dimension_scores` (`mutationId` TEXT NOT NULL, `dimensionKey` TEXT NOT NULL, `score` REAL NOT NULL, PRIMARY KEY(`mutationId`, `dimensionKey`), FOREIGN KEY(`mutationId`) REFERENCES `pending_mutations`(`mutationId`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_visit_dimension_scores_mutationId` ON `pending_visit_dimension_scores` (`mutationId`)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `pending_visit_photos` (`mutationId` TEXT NOT NULL, `position` INTEGER NOT NULL, `url` TEXT NOT NULL, PRIMARY KEY(`mutationId`, `position`), FOREIGN KEY(`mutationId`) REFERENCES `pending_mutations`(`mutationId`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_visit_photos_mutationId` ON `pending_visit_photos` (`mutationId`)")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `visit_draft_photos` (
                `ownerUserId` TEXT NOT NULL, `placeId` TEXT NOT NULL, `position` INTEGER NOT NULL,
                `clientMediaId` TEXT NOT NULL, `localRelativePath` TEXT NOT NULL,
                `contentType` TEXT NOT NULL, `byteSize` INTEGER NOT NULL, `width` INTEGER,
                `height` INTEGER, `remoteMediaId` TEXT, `uploadState` TEXT NOT NULL,
                `failureCategory` TEXT, `legacyUrl` TEXT,
                PRIMARY KEY(`ownerUserId`, `placeId`, `position`),
                FOREIGN KEY(`ownerUserId`, `placeId`) REFERENCES `visit_drafts`(`userId`, `placeId`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_visit_draft_photos_ownerUserId_placeId` ON `visit_draft_photos` (`ownerUserId`, `placeId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_visit_draft_photos_clientMediaId` ON `visit_draft_photos` (`clientMediaId`)")

        db.execSQL("ALTER TABLE `pending_visit_photos` RENAME TO `pending_visit_photos_v6`")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `pending_visit_photos` (
                `mutationId` TEXT NOT NULL, `position` INTEGER NOT NULL, `ownerUserId` TEXT NOT NULL,
                `clientMediaId` TEXT NOT NULL, `localRelativePath` TEXT, `contentType` TEXT,
                `byteSize` INTEGER, `width` INTEGER, `height` INTEGER, `remoteMediaId` TEXT,
                `uploadState` TEXT NOT NULL, `failureCategory` TEXT, `legacyUrl` TEXT,
                PRIMARY KEY(`mutationId`, `position`),
                FOREIGN KEY(`mutationId`) REFERENCES `pending_mutations`(`mutationId`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
        )
        db.execSQL(
            """INSERT INTO `pending_visit_photos`
                (`mutationId`,`position`,`ownerUserId`,`clientMediaId`,`localRelativePath`,
                 `contentType`,`byteSize`,`width`,`height`,`remoteMediaId`,`uploadState`,
                 `failureCategory`,`legacyUrl`)
               SELECT p.`mutationId`,p.`position`,m.`userId`,
                      lower(hex(randomblob(4)))||'-'||lower(hex(randomblob(2)))||'-4'||
                      substr(lower(hex(randomblob(2))),2)||'-a'||substr(lower(hex(randomblob(2))),2)||
                      '-'||lower(hex(randomblob(6))),
                      NULL,NULL,NULL,NULL,NULL,NULL,'READY_REMOTE',NULL,p.`url`
               FROM `pending_visit_photos_v6` p
               JOIN `pending_mutations` m ON m.`mutationId`=p.`mutationId`""",
        )
        db.execSQL(
            """UPDATE `pending_mutations`
               SET `state`='FAILED_PERMANENT',
                   `lastErrorCategory`='LEGACY_MEDIA_RESELECT_REQUIRED'
               WHERE `type`='PUBLISH_VISIT'
                 AND EXISTS (
                     SELECT 1 FROM `pending_visit_photos_v6` p
                     WHERE p.`mutationId`=`pending_mutations`.`mutationId`
                 )""",
        )
        db.execSQL("DROP TABLE `pending_visit_photos_v6`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_visit_photos_mutationId` ON `pending_visit_photos` (`mutationId`)")

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `visit_media` (
                `ownerUserId` TEXT NOT NULL, `visitId` TEXT NOT NULL, `position` INTEGER NOT NULL,
                `mediaId` TEXT NOT NULL, `accessUrl` TEXT, `accessUrlExpiresAtEpochMillis` INTEGER,
                PRIMARY KEY(`ownerUserId`, `visitId`, `position`),
                FOREIGN KEY(`visitId`) REFERENCES `visits`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_visit_media_visitId` ON `visit_media` (`visitId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_visit_media_ownerUserId_mediaId` ON `visit_media` (`ownerUserId`, `mediaId`)")
    }
}

/** Additive native Experience publication. Existing Visit drafts/queue rows stay V1. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `pending_mutations` ADD COLUMN `payloadVersion` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `visit_drafts` ADD COLUMN `payloadVersion` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `visit_drafts` ADD COLUMN `primaryExperienceCode` TEXT")
        db.execSQL("ALTER TABLE `visit_drafts` ADD COLUMN `rawExperienceLabel` TEXT")
        db.execSQL("ALTER TABLE `visit_drafts` ADD COLUMN `overallFeelingCode` TEXT")
        db.execSQL("ALTER TABLE `visit_drafts` ADD COLUMN `companionCode` TEXT")
        db.execSQL("ALTER TABLE `visit_drafts` ADD COLUMN `timeOfDayCode` TEXT")
        db.execSQL("ALTER TABLE `visit_drafts` ADD COLUMN `vibeCodes` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `visit_drafts` ADD COLUMN `practicalSignalCodes` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `visit_drafts` ADD COLUMN `title` TEXT")
        db.execSQL("ALTER TABLE `visit_drafts` ADD COLUMN `titleSource` TEXT NOT NULL DEFAULT 'GENERATED'")
        db.execSQL("ALTER TABLE `visit_drafts` ADD COLUMN `story` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `visit_drafts` ADD COLUMN `tip` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `visit_draft_dimension_scores` ADD COLUMN `semanticStateCode` TEXT")
        db.execSQL("ALTER TABLE `visit_draft_dimension_scores` ADD COLUMN `templateVersion` INTEGER")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `pending_experience_v2_payloads` (
                `mutationId` TEXT NOT NULL, `placeId` TEXT NOT NULL,
                `visitedAtEpochDay` INTEGER NOT NULL, `primaryExperienceCode` TEXT NOT NULL,
                `rawExperienceLabel` TEXT, `overallFeelingCode` TEXT NOT NULL,
                `companionCode` TEXT, `timeOfDayCode` TEXT, `vibeCodes` TEXT NOT NULL,
                `practicalSignalCodes` TEXT NOT NULL, `title` TEXT, `titleSource` TEXT NOT NULL,
                `story` TEXT NOT NULL, `tip` TEXT NOT NULL, `privateMemory` TEXT NOT NULL,
                `visibility` TEXT NOT NULL, PRIMARY KEY(`mutationId`),
                FOREIGN KEY(`mutationId`) REFERENCES `pending_mutations`(`mutationId`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `pending_experience_v2_dimensions` (
                `mutationId` TEXT NOT NULL, `dimensionKey` TEXT NOT NULL,
                `semanticStateCode` TEXT NOT NULL, `templateVersion` INTEGER NOT NULL,
                PRIMARY KEY(`mutationId`, `dimensionKey`),
                FOREIGN KEY(`mutationId`) REFERENCES `pending_mutations`(`mutationId`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_experience_v2_dimensions_mutationId` ON `pending_experience_v2_dimensions` (`mutationId`)")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `visit_drafts` ADD COLUMN `originAcknowledgementId` TEXT")
        db.execSQL("ALTER TABLE `pending_experience_v2_payloads` ADD COLUMN `originAcknowledgementId` TEXT")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `planned_experiences` (`ownerUserId` TEXT NOT NULL, `experienceId` TEXT NOT NULL, `title` TEXT NOT NULL, `placeId` TEXT NOT NULL, `placeName` TEXT NOT NULL, `primaryExperienceCode` TEXT NOT NULL, `feelingCode` TEXT NOT NULL, `authorName` TEXT NOT NULL, `imageUrl` TEXT, `plannedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`ownerUserId`, `experienceId`))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_planned_experiences_ownerUserId_plannedAtEpochMillis` ON `planned_experiences` (`ownerUserId`, `plannedAtEpochMillis`)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `experience_acknowledgements` (`ownerUserId` TEXT NOT NULL, `id` TEXT NOT NULL, `sourceExperienceId` TEXT, `sourceAvailable` INTEGER NOT NULL, `placeId` TEXT NOT NULL, `placeName` TEXT NOT NULL, `placeCity` TEXT NOT NULL, `placeRegion` TEXT NOT NULL, `placeCountry` TEXT NOT NULL, `primaryExperienceCode` TEXT NOT NULL, `rawExperienceLabel` TEXT, `acknowledgedAtEpochMillis` INTEGER NOT NULL, `convertedExperienceId` TEXT, PRIMARY KEY(`ownerUserId`, `id`))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_experience_acknowledgements_ownerUserId_acknowledgedAtEpochMillis` ON `experience_acknowledgements` (`ownerUserId`, `acknowledgedAtEpochMillis`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_experience_acknowledgements_ownerUserId_sourceExperienceId` ON `experience_acknowledgements` (`ownerUserId`, `sourceExperienceId`)")
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `conversation_entries` (`ownerUserId` TEXT NOT NULL, `id` TEXT NOT NULL, `experienceId` TEXT NOT NULL, `parentEntryId` TEXT, `type` TEXT NOT NULL, `body` TEXT NOT NULL, `authorId` TEXT NOT NULL, `authorUsername` TEXT NOT NULL, `authorDisplayName` TEXT NOT NULL, `authorAvatarUrl` TEXT, `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, `edited` INTEGER NOT NULL, `experienceAuthor` INTEGER NOT NULL, `ownedByViewer` INTEGER NOT NULL, `reportableByViewer` INTEGER NOT NULL, `syncState` TEXT NOT NULL, `clientMutationId` TEXT, PRIMARY KEY(`ownerUserId`, `id`))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversation_entries_ownerUserId_experienceId_parentEntryId_createdAt` ON `conversation_entries` (`ownerUserId`, `experienceId`, `parentEntryId`, `createdAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversation_entries_ownerUserId_clientMutationId` ON `conversation_entries` (`ownerUserId`, `clientMutationId`)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `pending_conversation_payloads` (`mutationId` TEXT NOT NULL, `experienceId` TEXT NOT NULL, `targetEntryId` TEXT, `parentEntryId` TEXT, `entryType` TEXT, `body` TEXT, `localEntryId` TEXT, PRIMARY KEY(`mutationId`), FOREIGN KEY(`mutationId`) REFERENCES `pending_mutations`(`mutationId`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
    }
}
