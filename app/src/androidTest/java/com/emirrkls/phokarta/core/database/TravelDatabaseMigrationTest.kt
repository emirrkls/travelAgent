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

            insertCanonicalState()

            USER_STATE_TABLES.forEach { table ->
                assertEquals("$table should accept canonical rows", 1, rowCount(table))
            }
            close()
        }
    }

    @Test
    fun migration2To3PreservesUserStateAndAddsPlaceCache() {
        helper.createDatabase(TEST_DATABASE, 2).apply {
            insertCanonicalState()
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

    private fun SupportSQLiteDatabase.insertCanonicalState() {
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

    private fun SupportSQLiteDatabase.rowCount(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
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
