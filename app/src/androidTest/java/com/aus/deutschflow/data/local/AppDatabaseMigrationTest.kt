package com.aus.deutschflow.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aus.deutschflow.data.local.entities.VocabularyEntity
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
                "INSERT INTO vocabulary (germanText, englishTranslation, timestamp) " +
                    "VALUES ('das Haus', 'the house', 1000)"
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
            // isFavorite is NOT NULL with no default at version 2, so a row written
            // as that version has to supply it - which is the point of writing the
            // fixture in the old schema's own terms.
            db.execSQL(
                "INSERT INTO vocabulary " +
                    "(germanText, englishTranslation, timestamp, isFavorite) " +
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
     * The 3 -> 4 rebuild, which is the risky shape of migration: the table is
     * recreated and copied rather than altered, so a mistake loses every saved word
     * rather than one column.
     */
    @Test
    fun droppingTheFavouriteColumnKeepsEveryWord() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL(
                "INSERT INTO vocabulary " +
                    "(germanText, englishTranslation, timestamp, isFavorite, exampleSentence) " +
                    "VALUES ('das Haus', 'the house', 1000, 1, 'Das Haus ist gross.')"
            )
            db.execSQL(
                "INSERT INTO vocabulary " +
                    "(germanText, englishTranslation, timestamp, isFavorite, exampleSentence) " +
                    "VALUES ('lernen', 'to learn', 2000, 0, '')"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        val database = openAsReleaseWould()
        try {
            val saved = runBlocking { database.vocabularyDao().getAllVocabulary().first() }

            assertEquals(2, saved.size)
            // Ordered by timestamp descending, so the newer word comes first.
            assertEquals("lernen", saved.first().germanText)

            val house = saved.first { it.germanText == "das Haus" }
            assertEquals("the house", house.englishTranslation)
            assertEquals(1000L, house.timestamp)
            // The example survives the rebuild; only the unused column goes.
            assertEquals("Das Haus ist gross.", house.exampleSentence)
            // Ids are carried across rather than reassigned by the new table.
            assertEquals(1, house.id)
        } finally {
            database.close()
        }
    }

    /**
     * The 4 -> 5 upgrade, which adds the two timestamp indexes.
     *
     * The statements are `CREATE INDEX IF NOT EXISTS` and so cannot fail on their own
     * terms - the value is entirely in `runMigrationsAndValidate`, which compares the
     * result against the exported 5.json. An index Room expects and the migration does
     * not create is a mismatch that otherwise surfaces at launch, on a user's device.
     */
    @Test
    fun theTimestampIndexesArriveWithoutLosingAnything() {
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL(
                "INSERT INTO vocabulary " +
                    "(germanText, englishTranslation, timestamp, exampleSentence) " +
                    "VALUES ('das Haus', 'the house', 1000, 'Das Haus ist gross.')"
            )
            db.execSQL("INSERT INTO transcripts (fullText, timestamp) VALUES ('Guten Tag', 1000)")
        }

        helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5)

        val database = openAsReleaseWould()
        try {
            val saved = runBlocking { database.vocabularyDao().getAllVocabulary().first() }
            val history = runBlocking { database.transcriptDao().getAllTranscripts().first() }

            // Indexing a column must not disturb what is in it.
            assertEquals(1, saved.size)
            assertEquals("das Haus", saved.first().germanText)
            assertEquals("Das Haus ist gross.", saved.first().exampleSentence)
            assertEquals(1, history.size)
            assertEquals("Guten Tag", history.first().fullText)
        } finally {
            database.close()
        }
    }

    /**
     * The 5 -> 6 upgrade: the three grammatical fields the single-word interrogation
     * produces.
     *
     * Each is added NOT NULL with a SQL default, which is the only shape of ADD COLUMN
     * SQLite accepts on a populated table - get the default wrong and the migration
     * throws on any database that already holds a word. A row written at version 5 has
     * none of these, and the empty string is what the detail screen reads as "this word
     * was never interrogated".
     */
    @Test
    fun theGrammarColumnsArriveWithoutLosingAnything() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.execSQL(
                "INSERT INTO vocabulary " +
                    "(germanText, englishTranslation, timestamp, exampleSentence) " +
                    "VALUES ('das Haus', 'the house', 1000, 'Das Haus ist gross.')"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATION_5_6)

        val database = openAsReleaseWould()
        try {
            val saved = runBlocking { database.vocabularyDao().getAllVocabulary().first() }

            assertEquals(1, saved.size)
            val house = saved.first()
            assertEquals("das Haus", house.germanText)
            assertEquals("the house", house.englishTranslation)
            assertEquals("Das Haus ist gross.", house.exampleSentence)
            // Empty, not null: the columns are NOT NULL, and the detail screen treats
            // blank as "nothing to show" rather than rendering a stray separator.
            assertEquals("", house.article)
            assertEquals("", house.plural)
            assertEquals("", house.conjugation)
        } finally {
            database.close()
        }
    }

    /**
     * The 6 -> 7 upgrade, which is where the duplicates people already have get folded
     * together so the word can be made unique.
     *
     * The riskiest migration in the app: it rebuilds the table, and it is the only one
     * that deliberately writes back fewer rows than it read. The fixture is the case
     * that motivated it - a word typed by hand, then interrogated - plus a hand-edited
     * translation on the poorer of the two, because picking one row wholesale would
     * have silently dropped exactly that.
     */
    @Test
    fun duplicateWordsAreFoldedIntoOneWithoutLosingAField() {
        helper.createDatabase(TEST_DB, 6).use { db ->
            // Typed by hand, then the translation edited. No grammar.
            db.execSQL(
                "INSERT INTO vocabulary " +
                    "(id, germanText, englishTranslation, timestamp, exampleSentence, " +
                    "article, plural, conjugation) " +
                    "VALUES (1, 'Hund', 'hound, edited by hand', 1000, '', '', '', '')"
            )
            // The same word interrogated later, in a different case, carrying grammar
            // but only the model's plainer translation.
            db.execSQL(
                "INSERT INTO vocabulary " +
                    "(id, germanText, englishTranslation, timestamp, exampleSentence, " +
                    "article, plural, conjugation) " +
                    "VALUES (2, 'hund', 'dog', 2000, 'Der Hund schläft.', " +
                    "'der', 'Hunde', '')"
            )
            // An unrelated word, which must come through untouched.
            db.execSQL(
                "INSERT INTO vocabulary " +
                    "(id, germanText, englishTranslation, timestamp, exampleSentence, " +
                    "article, plural, conjugation) " +
                    "VALUES (3, 'laufen', 'to run', 3000, '', 'none', '', 'laufen')"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)

        val database = openAsReleaseWould()
        try {
            val saved = runBlocking { database.vocabularyDao().getAllVocabulary().first() }

            // Three rows in, two words out: the two spellings of the dog are one word.
            assertEquals(2, saved.size)

            val hund = saved.first { it.germanText.equals("Hund", ignoreCase = true) }
            // The grammar the interrogation paid for survives...
            assertEquals("der", hund.article)
            assertEquals("Hunde", hund.plural)
            assertEquals("Der Hund schläft.", hund.exampleSentence)
            // ...and so does the translation the user typed, which is the field a
            // whole-row winner would have thrown away: it is the *later* non-blank
            // value that wins per field, and row 2 is the later row.
            assertEquals("dog", hund.englishTranslation)
            // The greatest timestamp in the group, so the merged word sorts where the
            // most recent of its copies did.
            assertEquals(2000L, hund.timestamp)

            val laufen = saved.first { it.germanText == "laufen" }
            assertEquals("to run", laufen.englishTranslation)
            assertEquals("laufen", laufen.conjugation)
            assertEquals(3, laufen.id)
        } finally {
            database.close()
        }
    }

    /**
     * The other half of the same migration: with the duplicates gone, the unique index
     * must actually be in force, or the next save puts one straight back.
     */
    @Test
    fun theWordIsUniqueOnceTheMigrationHasRun() {
        helper.createDatabase(TEST_DB, 6).use { db ->
            db.execSQL(
                "INSERT INTO vocabulary " +
                    "(germanText, englishTranslation, timestamp, exampleSentence, " +
                    "article, plural, conjugation) " +
                    "VALUES ('Hund', 'dog', 1000, '', '', '', '')"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)

        val database = openAsReleaseWould()
        try {
            // Same word, different case: the index is NOCASE, so this is the same word.
            runBlocking {
                database.vocabularyDao().save(
                    VocabularyEntity(
                        germanText = "hund",
                        englishTranslation = "dog",
                        article = "der"
                    )
                )
            }

            val saved = runBlocking { database.vocabularyDao().getAllVocabulary().first() }
            assertEquals(1, saved.size)
            // Merged into the row that was already there, keeping its spelling.
            assertEquals("Hund", saved.first().germanText)
            assertEquals("der", saved.first().article)
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
