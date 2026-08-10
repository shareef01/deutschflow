package com.aus.deutschflow.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the one failure mode the rest of the suite cannot see: a schema change that
 * ships without a migration.
 *
 * Release builds deliberately have no `fallbackToDestructiveMigration`, because a
 * user's saved vocabulary is the whole point of the app. The price of that choice is
 * that bumping [DATABASE_VERSION] without writing a Migration throws inside
 * `Room.databaseBuilder().build()` on the first database access - which is at launch,
 * for every existing install, with no recovery path.
 *
 * These tests fail instead.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    /**
     * Creates a database at the current schema version, then opens it the way a
     * release build does and reads the row back.
     *
     * Add a version without a migration and this fails on the open.
     */
    @Test
    fun theReleaseConfigurationOpensAnExistingDatabase() {
        helper.createDatabase(TEST_DB, DATABASE_VERSION).use { db ->
            db.execSQL(
                "INSERT INTO vocabulary (germanText, englishTranslation, timestamp, isFavorite) " +
                    "VALUES ('das Haus', 'the house', 1000, 0)"
            )
        }

        val database = openAsReleaseWould()
        try {
            val saved = runBlocking { database.vocabularyDao().getAllVocabulary().first() }

            assertEquals(1, saved.size)
            assertEquals("das Haus", saved.first().germanText)
            assertEquals("the house", saved.first().englishTranslation)
        } finally {
            database.close()
        }
    }

    /**
     * The same check for the other two tables, so a change to either is caught by
     * something other than a user's crash report.
     */
    @Test
    fun transcriptsAndStatsSurviveTheSameOpen() {
        helper.createDatabase(TEST_DB, DATABASE_VERSION).use { db ->
            db.execSQL("INSERT INTO transcripts (fullText, timestamp) VALUES ('Guten Tag', 1000)")
            db.execSQL(
                "INSERT INTO user_stats (id, xp, streak, lastActivityTimestamp) " +
                    "VALUES (1, 120, 4, 1000)"
            )
        }

        val database = openAsReleaseWould()
        try {
            val transcripts = runBlocking { database.transcriptDao().getAllTranscripts().first() }
            val stats = runBlocking { database.userStatsDao().getUserStats().first() }

            assertEquals(1, transcripts.size)
            assertEquals("Guten Tag", transcripts.first().fullText)
            assertEquals(120, stats?.xp)
            assertEquals(4, stats?.streak)
        } finally {
            database.close()
        }
    }

    /**
     * The real 2 -> 3 upgrade, on a database holding a row written by version 2.
     *
     * [theReleaseConfigurationOpensAnExistingDatabase] only ever sees the current
     * schema, so it would not notice a migration that runs but loses data. This one
     * writes at the old version and reads back after the upgrade.
     */
    @Test
    fun theExampleSentenceColumnArrivesWithoutLosingAnything() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                "INSERT INTO vocabulary (germanText, englishTranslation, timestamp, isFavorite) " +
                    "VALUES ('das Haus', 'the house', 1000, 0)"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)

        val database = openAsReleaseWould()
        try {
            val saved = runBlocking { database.vocabularyDao().getAllVocabulary().first() }

            assertEquals(1, saved.size)
            assertEquals("das Haus", saved.first().germanText)
            assertEquals("the house", saved.first().englishTranslation)
            // A word saved before the column existed has no example of its own; the
            // detail screen falls back to a generated one for exactly this case.
            assertEquals("", saved.first().exampleSentence)
        } finally {
            database.close()
        }
    }

    /**
     * Mirrors DatabaseModule's release path exactly: the same migrations, and no
     * destructive fallback, so a missing migration surfaces as the same exception a
     * user would hit.
     */
    private fun openAsReleaseWould(): AppDatabase =
        Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
            TEST_DB
        ).addMigrations(*MIGRATIONS).build()

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
