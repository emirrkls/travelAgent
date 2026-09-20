package com.emirrkls.phokarta.core.database

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TravelDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TravelDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun deleteDatabase() {
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun migration1To2ClearsPrototypeStateAndAcceptsBackendUuids() {
        helper.createDatabase(TEST_DATABASE, 1).apply {
            insertPrototypeState()
            close()
        }

        helper.runMigrationsAndValidate(TEST_DATABASE, 2, true, MIGRATION_1_2).apply {
            USER_STATE_TABLES.forEach { table ->
                assertEquals("$table should be empty", 0, rowCount(table))
            }

            insertCanonicalStateV2()

            USER_STATE_TABLES.forEach { table ->
                assertEquals("$table should accept canonical rows", 1, rowCount(table))
            }
            close()
        }
    }

    @Test
    fun migration2To3PreservesUserStateAndAddsPlaceCache() {
        helper.createDatabase(TEST_DATABASE, 2).apply {
            insertCanonicalStateV2()
            close()
        }

        helper.runMigrationsAndValidate(TEST_DATABASE, 3, true, MIGRATION_2_3).apply {
            USER_STATE_TABLES.forEach { table ->
                assertEquals("$table should remain intact", 1, rowCount(table))
            }
            execSQL(
                """INSERT INTO cached_places
                    (id, name, category, coverImage, city, region, country, latitude, longitude,
                     priceLevel, averageScore, ratingCount, updatedAtEpochMillis)
                    VALUES ('30000000-0000-4000-8000-000000000003', 'Cached place', 'BEACH', '',
                            'Bodrum', 'Muğla', 'Türkiye', 37.0, 27.4, 2, NULL, 0, 200)""".trimIndent(),
            )
            assertEquals(1, rowCount("cached_places"))
            close()
        }
    }

    @Test
    fun migration3To4RecreatesSavedPlacesWithOwnerCompositeKey() {
        helper.createDatabase(TEST_DATABASE, 3).apply {
            insertCanonicalStateV2()
            execSQL(
                """INSERT INTO cached_places
                    (id, name, category, coverImage, city, region, country, latitude, longitude,
                     priceLevel, averageScore, ratingCount, updatedAtEpochMillis)
                    VALUES ('30000000-0000-4000-8000-000000000003', 'Cached place', 'BEACH', '',
                            'Bodrum', 'Muğla', 'Türkiye', 37.0, 27.4, 2, NULL, 0, 200)""".trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DATABASE, 4, true, MIGRATION_3_4).apply {
            assertEquals(0, rowCount("saved_places"))
            assertEquals(1, rowCount("visits"))
            assertEquals(1, rowCount("collections"))
            assertEquals(1, rowCount("cached_places"))

            val ownerId = "20000000-0000-4000-8000-000000000002"
            val placeId = "30000000-0000-4000-8000-000000000003"
            execSQL(
                "INSERT INTO saved_places (ownerUserId, placeId, savedAtEpochMillis) VALUES (?, ?, 300)",
                arrayOf<Any>(ownerId, placeId),
            )
            assertEquals(1, rowCount("saved_places"))

            query("PRAGMA table_info(saved_places)").use { cursor ->
                val columns = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                }
                assertEquals(listOf("ownerUserId", "placeId", "savedAtEpochMillis"), columns)
            }
            assertFalse(hasColumn("saved_places", "userId"))
            close()
        }
    }

    @Test
    fun migration4To5AddsVisitDraftTablesWithoutDestroyingExistingData() {
        helper.createDatabase(TEST_DATABASE, 4).apply {
            insertCanonicalStateV4()
            close()
        }

        helper.runMigrationsAndValidate(TEST_DATABASE, 5, true, MIGRATION_4_5).apply {
            assertEquals(1, rowCount("visits"))
            assertEquals(1, rowCount("visit_dimension_scores"))
            assertEquals(1, rowCount("saved_places"))
            assertEquals(1, rowCount("collections"))
            assertEquals(1, rowCount("cached_places"))
            assertEquals(0, rowCount("visit_drafts"))
            assertEquals(0, rowCount("visit_draft_dimension_scores"))

            val userId = "20000000-0000-4000-8000-000000000002"
            val placeId = "30000000-0000-4000-8000-000000000003"
            execSQL(
                """
                INSERT INTO visit_drafts
                (userId, placeId, overallScore, publicReview, privateMemory, visitedAtEpochDay,
                 visibility, dimensionsExpanded, createdAtEpochMillis, updatedAtEpochMillis)
                VALUES (?, ?, 8.5, 'Draft review', 'Draft memory', 21000, 'FRIENDS', 0, 400, 400)
                """.trimIndent(),
                arrayOf<Any>(userId, placeId),
            )
            execSQL(
                """
                INSERT INTO visit_draft_dimension_scores (userId, placeId, dimensionKey, score)
                VALUES (?, ?, 'SEA', 9.0)
                """.trimIndent(),
                arrayOf<Any>(userId, placeId),
            )
            assertEquals(1, rowCount("visit_drafts"))
            assertEquals(1, rowCount("visit_draft_dimension_scores"))
            close()
        }
    }

    @Test
    fun migration5To6PreservesStateAndAddsTypedMutationQueue() {
        val userId = "20000000-0000-4000-8000-000000000002"
        val placeId = "30000000-0000-4000-8000-000000000003"
        helper.createDatabase(TEST_DATABASE, 5).apply {
            execSQL(
                """INSERT INTO visit_drafts
                    (userId, placeId, overallScore, publicReview, privateMemory, visitedAtEpochDay,
                     visibility, dimensionsExpanded, createdAtEpochMillis, updatedAtEpochMillis)
                    VALUES (?, ?, 8.5, 'Draft', 'Memory', 21000, 'PRIVATE', 0, 400, 400)""".trimIndent(),
                arrayOf<Any>(userId, placeId),
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DATABASE, 6, true, MIGRATION_5_6).apply {
            assertEquals(1, rowCount("visit_drafts"))
            assertEquals(0, rowCount("pending_mutations"))
            assertEquals(0, rowCount("pending_visit_payloads"))
            assertEquals(0, rowCount("pending_visit_dimension_scores"))
            assertEquals(0, rowCount("pending_visit_photos"))
            execSQL(
                """INSERT INTO pending_mutations
                    (mutationId, userId, type, resourceKey, state, generation, desiredSaved,
                     attemptCount, createdAtEpochMillis, updatedAtEpochMillis, lastErrorCategory)
                    VALUES ('m1', ?, 'SET_SAVED_STATE', ?, 'PENDING', 1, 1, 0, 500, 500, NULL)""".trimIndent(),
                arrayOf<Any>(userId, placeId),
            )
            assertEquals(1, rowCount("pending_mutations"))
            close()
        }
    }

    @Test
    fun migration6To7PreservesLegacyPhotosAndAddsDurableMediaTables() {
        val userId = "20000000-0000-4000-8000-000000000002"
        helper.createDatabase(TEST_DATABASE, 6).apply {
            execSQL(
                """INSERT INTO pending_mutations
                    (mutationId,userId,type,resourceKey,state,generation,desiredSaved,attemptCount,
                     createdAtEpochMillis,updatedAtEpochMillis,lastErrorCategory)
                   VALUES ('m1',?,'PUBLISH_VISIT','m1','PENDING',1,NULL,0,1,1,NULL)""",
                arrayOf<Any>(userId),
            )
            execSQL(
                """INSERT INTO pending_visit_photos (mutationId,position,url)
                   VALUES ('m1',0,'https://legacy.invalid/photo.jpg')""",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DATABASE, 7, true, MIGRATION_6_7).apply {
            assertEquals(1, rowCount("pending_visit_photos"))
            assertEquals(0, rowCount("visit_draft_photos"))
            assertEquals(0, rowCount("visit_media"))
            query(
                "SELECT ownerUserId, legacyUrl, uploadState FROM pending_visit_photos WHERE mutationId='m1'",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(userId, cursor.getString(0))
                assertEquals("https://legacy.invalid/photo.jpg", cursor.getString(1))
                assertEquals("READY_REMOTE", cursor.getString(2))
            }
            query(
                "SELECT state, lastErrorCategory FROM pending_mutations WHERE mutationId='m1'",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("FAILED_PERMANENT", cursor.getString(0))
                assertEquals("LEGACY_MEDIA_RESELECT_REQUIRED", cursor.getString(1))
            }
            close()
        }
    }

    @Test
    fun migration7To8PreservesV1RowsAndAddsVersionedV2Publication() {
        val userId = "20000000-0000-4000-8000-000000000002"
        val placeId = "30000000-0000-4000-8000-000000000003"
        helper.createDatabase(TEST_DATABASE, 7).apply {
            execSQL(
                """INSERT INTO visit_drafts
                    (userId,placeId,overallScore,publicReview,privateMemory,visitedAtEpochDay,
                     visibility,dimensionsExpanded,createdAtEpochMillis,updatedAtEpochMillis)
                   VALUES (?,?,8.0,'legacy story','memory',21000,'PUBLIC',0,1,1)""",
                arrayOf<Any>(userId, placeId),
            )
            execSQL(
                """INSERT INTO pending_mutations
                    (mutationId,userId,type,resourceKey,state,generation,desiredSaved,attemptCount,
                     createdAtEpochMillis,updatedAtEpochMillis,lastErrorCategory)
                   VALUES ('legacy-v1',?,'PUBLISH_VISIT','legacy-v1','PENDING',1,NULL,0,1,1,NULL)""",
                arrayOf<Any>(userId),
            )
            execSQL(
                """INSERT INTO pending_visit_payloads
                    (mutationId,placeId,visitedAtEpochDay,overallRating,publicReview,privateMemory,visibility)
                   VALUES ('legacy-v1',?,21000,8.0,'legacy story','memory','PUBLIC')""",
                arrayOf<Any>(placeId),
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DATABASE, 8, true, MIGRATION_7_8).apply {
            query("SELECT payloadVersion,type FROM pending_mutations WHERE mutationId='legacy-v1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
                assertEquals("PUBLISH_VISIT", cursor.getString(1))
            }
            query("SELECT payloadVersion,story,titleSource FROM visit_drafts WHERE userId=? AND placeId=?", arrayOf(userId, placeId)).use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
                assertEquals("", cursor.getString(1))
                assertEquals("GENERATED", cursor.getString(2))
            }
            assertEquals(0, rowCount("pending_experience_v2_payloads"))
            assertEquals(0, rowCount("pending_experience_v2_dimensions"))
            execSQL(
                """INSERT INTO pending_mutations
                    (mutationId,userId,type,resourceKey,state,generation,desiredSaved,attemptCount,
                     createdAtEpochMillis,updatedAtEpochMillis,lastErrorCategory,payloadVersion)
                   VALUES ('native-v2',?,'PUBLISH_EXPERIENCE_V2','native-v2','PENDING',1,NULL,0,2,2,NULL,2)""",
                arrayOf<Any>(userId),
            )
            execSQL(
                """INSERT INTO pending_experience_v2_payloads
                    (mutationId,placeId,visitedAtEpochDay,primaryExperienceCode,rawExperienceLabel,
                     overallFeelingCode,companionCode,timeOfDayCode,vibeCodes,practicalSignalCodes,
                     title,titleSource,story,tip,privateMemory,visibility)
                   VALUES ('native-v2',?,21000,'GUN_BATIMI',NULL,'BAYILDIM',NULL,'EVENING',
                           'CALM,SCENIC','ARRIVE_EARLY',NULL,'GENERATED','story','','memory','PUBLIC')""",
                arrayOf<Any>(placeId),
            )
            assertEquals(1, rowCount("pending_experience_v2_payloads"))
            close()
        }
    }

    @Test
    fun migration8To9PreservesQueuedPublicationAndAddsMilestoneState() {
        val userId = "20000000-0000-4000-8000-000000000002"
        val placeId = "30000000-0000-4000-8000-000000000003"
        helper.createDatabase(TEST_DATABASE, 8).apply {
            execSQL(
                """INSERT INTO visit_drafts
                    (userId,placeId,overallScore,publicReview,privateMemory,visitedAtEpochDay,
                     visibility,dimensionsExpanded,createdAtEpochMillis,updatedAtEpochMillis,
                     payloadVersion,vibeCodes,practicalSignalCodes,titleSource,story,tip)
                   VALUES (?,?,8.0,'','','21000','PUBLIC',0,1,1,2,'','','GENERATED','','')""",
                arrayOf<Any>(userId, placeId),
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DATABASE, 9, true, MIGRATION_8_9).apply {
            query("SELECT payloadVersion,originAcknowledgementId FROM visit_drafts WHERE userId=? AND placeId=?", arrayOf(userId, placeId)).use { cursor ->
                cursor.moveToFirst()
                assertEquals(2, cursor.getInt(0))
                assertEquals(null, cursor.getString(1))
            }
            assertEquals(0, rowCount("planned_experiences"))
            assertEquals(0, rowCount("experience_acknowledgements"))
            close()
        }
    }

    @Test
    fun migration9To10PreservesExistingStateAndAddsAccountScopedConversationQueue() {
        val userId = "20000000-0000-4000-8000-000000000002"
        val experienceId = "10000000-0000-4000-8000-000000000001"
        helper.createDatabase(TEST_DATABASE, 9).apply {
            execSQL(
                """INSERT INTO planned_experiences
                    (ownerUserId,experienceId,title,placeId,placeName,primaryExperienceCode,
                     feelingCode,authorName,imageUrl,plannedAtEpochMillis)
                   VALUES (?,?,'Sunset','p1','Harbour','GUN_BATIMI','BAYILDIM','Ada',NULL,1)""",
                arrayOf<Any>(userId, experienceId),
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DATABASE, 10, true, MIGRATION_9_10).apply {
            assertEquals(1, rowCount("planned_experiences"))
            assertEquals(0, rowCount("conversation_entries"))
            assertEquals(0, rowCount("pending_conversation_payloads"))
            execSQL(
                """INSERT INTO pending_mutations
                    (mutationId,userId,type,resourceKey,state,generation,desiredSaved,attemptCount,
                     createdAtEpochMillis,updatedAtEpochMillis,lastErrorCategory,payloadVersion)
                   VALUES ('m-conversation',?,'CREATE_CONVERSATION_ROOT',?,'PENDING',1,NULL,0,1,1,NULL,1)""",
                arrayOf<Any>(userId, experienceId),
            )
            execSQL(
                """INSERT INTO pending_conversation_payloads
                    (mutationId,experienceId,targetEntryId,parentEntryId,entryType,body,localEntryId)
                   VALUES ('m-conversation',?,NULL,NULL,'QUESTION','When is it quiet?','local-1')""",
                arrayOf<Any>(experienceId),
            )
            execSQL(
                """INSERT INTO conversation_entries
                    (ownerUserId,id,experienceId,parentEntryId,type,body,authorId,authorUsername,
                     authorDisplayName,authorAvatarUrl,createdAt,updatedAt,edited,experienceAuthor,
                     ownedByViewer,reportableByViewer,syncState,clientMutationId)
                   VALUES (?,'local-1',?,NULL,'QUESTION','When is it quiet?',?,'ada','Ada',NULL,
                           '2026-09-20T10:00:00Z','2026-09-20T10:00:00Z',0,0,1,0,'PENDING','m-conversation')""",
                arrayOf<Any>(userId, experienceId, userId),
            )
            assertEquals(1, rowCount("pending_conversation_payloads"))
            assertEquals(1, rowCount("conversation_entries"))
            close()
        }
    }

    private fun SupportSQLiteDatabase.insertPrototypeState() {
        execSQL(
            """INSERT INTO visits
                (id, userId, placeId, visitedAtEpochDay, overallRating, publicReview, privateMemory,
                 visibility, verificationStatus, createdAtEpochMillis)
                VALUES ('visit-old-1', 'u1', 'p1', 20000, 8.5, 'Review', 'Memory',
                        'PUBLIC', 'UNVERIFIED', 100)""".trimIndent(),
        )
        execSQL(
            "INSERT INTO visit_dimension_scores (visitId, dimensionKey, score) " +
                "VALUES ('visit-old-1', 'Food', 9.0)",
        )
        execSQL("INSERT INTO saved_places (placeId, savedAtEpochMillis) VALUES ('p2', 100)")
        execSQL(
            """INSERT INTO collections
                (id, userId, title, description, visibility, coverImage,
                 createdAtEpochMillis, updatedAtEpochMillis)
                VALUES ('c1', 'u1', 'Prototype', '', 'PRIVATE', '', 100, 100)""".trimIndent(),
        )
        execSQL("INSERT INTO collection_places (collectionId, placeId) VALUES ('c1', 'p1')")
    }

    private fun SupportSQLiteDatabase.insertCanonicalStateV2() {
        val visitId = "10000000-0000-4000-8000-000000000001"
        val userId = "20000000-0000-4000-8000-000000000002"
        val placeId = "30000000-0000-4000-8000-000000000003"
        val collectionId = "40000000-0000-4000-8000-000000000004"

        execSQL(
            """INSERT INTO visits
                (id, userId, placeId, visitedAtEpochDay, overallRating, publicReview, privateMemory,
                 visibility, verificationStatus, createdAtEpochMillis)
                VALUES (?, ?, ?, 21000, 9.0, 'Review', 'Memory',
                        'PUBLIC', 'UNVERIFIED', 200)""".trimIndent(),
            arrayOf<Any>(visitId, userId, placeId),
        )
        execSQL(
            "INSERT INTO visit_dimension_scores (visitId, dimensionKey, score) VALUES (?, 'Food', 9.0)",
            arrayOf<Any>(visitId),
        )
        execSQL(
            "INSERT INTO saved_places (placeId, savedAtEpochMillis) VALUES (?, 200)",
            arrayOf<Any>(placeId),
        )
        execSQL(
            """INSERT INTO collections
                (id, userId, title, description, visibility, coverImage,
                 createdAtEpochMillis, updatedAtEpochMillis)
                VALUES (?, ?, 'Canonical', '', 'PRIVATE', '', 200, 200)""".trimIndent(),
            arrayOf<Any>(collectionId, userId),
        )
        execSQL(
            "INSERT INTO collection_places (collectionId, placeId) VALUES (?, ?)",
            arrayOf<Any>(collectionId, placeId),
        )
    }

    private fun SupportSQLiteDatabase.insertCanonicalStateV4() {
        val visitId = "10000000-0000-4000-8000-000000000001"
        val userId = "20000000-0000-4000-8000-000000000002"
        val placeId = "30000000-0000-4000-8000-000000000003"
        val collectionId = "40000000-0000-4000-8000-000000000004"

        execSQL(
            """INSERT INTO visits
                (id, userId, placeId, visitedAtEpochDay, overallRating, publicReview, privateMemory,
                 visibility, verificationStatus, createdAtEpochMillis)
                VALUES (?, ?, ?, 21000, 9.0, 'Review', 'Memory',
                        'PUBLIC', 'UNVERIFIED', 200)""".trimIndent(),
            arrayOf<Any>(visitId, userId, placeId),
        )
        execSQL(
            "INSERT INTO visit_dimension_scores (visitId, dimensionKey, score) VALUES (?, 'SEA', 9.0)",
            arrayOf<Any>(visitId),
        )
        execSQL(
            "INSERT INTO saved_places (ownerUserId, placeId, savedAtEpochMillis) VALUES (?, ?, 200)",
            arrayOf<Any>(userId, placeId),
        )
        execSQL(
            """INSERT INTO collections
                (id, userId, title, description, visibility, coverImage,
                 createdAtEpochMillis, updatedAtEpochMillis)
                VALUES (?, ?, 'Canonical', '', 'PRIVATE', '', 200, 200)""".trimIndent(),
            arrayOf<Any>(collectionId, userId),
        )
        execSQL(
            "INSERT INTO collection_places (collectionId, placeId) VALUES (?, ?)",
            arrayOf<Any>(collectionId, placeId),
        )
        execSQL(
            """INSERT INTO cached_places
                (id, name, category, coverImage, city, region, country, latitude, longitude,
                 priceLevel, averageScore, ratingCount, updatedAtEpochMillis)
                VALUES (?, 'Cached place', 'BEACH', '', 'Bodrum', 'Muğla', 'Türkiye',
                        37.0, 27.4, 2, NULL, 0, 200)""".trimIndent(),
            arrayOf<Any>(placeId),
        )
    }

    private fun SupportSQLiteDatabase.rowCount(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean =
        query("PRAGMA table_info($table)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return true
            }
            false
        }

    companion object {
        private const val TEST_DATABASE = "travel-migration-test"
        private val USER_STATE_TABLES = listOf(
            "visit_dimension_scores",
            "visits",
            "saved_places",
            "collection_places",
            "collections",
        )
    }
}
