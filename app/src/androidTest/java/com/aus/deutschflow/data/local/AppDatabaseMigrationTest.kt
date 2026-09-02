package com.aus.deutschflow.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aus.deutschflow.data.local.entities.RoleplayMessageEntity
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import com.aus.deutschflow.data.local.entities.germanKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
     * The 7 -> 8 upgrade, which puts every existing word into the SRS.
     *
     * The defaults are the whole point: a library built before spaced repetition
     * existed has to arrive in the scheduler as a deck of new cards, not as rows the
     * due-query cannot see. `nextReview = 0` is "due now", and 2.5 is SM-2's starting
     * ease - the same state a word saved today gets.
     */
    @Test
    fun theSrsColumnsArriveWithEveryOldWordDueNow() {
        helper.createDatabase(TEST_DB, 7).use { db ->
            db.execSQL(
                "INSERT INTO vocabulary " +
                    "(germanText, englishTranslation, timestamp, exampleSentence, " +
                    "article, plural, conjugation) " +
                    "VALUES ('das Haus', 'the house', 1000, '', 'das', 'Häuser', '')"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8)

        val database = openAsReleaseWould()
        try {
            val saved = runBlocking { database.vocabularyDao().getAllVocabulary().first() }
            assertEquals(1, saved.size)
            assertEquals(0L, saved.first().nextReview)
            assertEquals(0, saved.first().interval)
            assertEquals(2.5f, saved.first().easeFactor, 0.0001f)
            assertEquals(0, saved.first().reviewCount)
            // The grammar it already had is untouched by the column addition.
            assertEquals("Häuser", saved.first().plural)

            // And the query Study actually runs finds it. A column that migrates in
            // correctly but stays invisible to the due-query would empty the deck
            // for every existing install without raising anything.
            val due = runBlocking {
                database.vocabularyDao().getDueVocabulary(System.currentTimeMillis()).first()
            }
            assertEquals(1, due.size)
        } finally {
            database.close()
        }
    }

    /**
     * The 8 -> 9 upgrade. Empty defaults, so a word that predates the synonym prompt
     * reads exactly as a word the user typed by hand does - the detail screen's two
     * boxes stay empty rather than showing something invented.
     */
    @Test
    fun theSynonymColumnsArriveEmpty() {
        helper.createDatabase(TEST_DB, 8).use { db ->
            db.execSQL(
                "INSERT INTO vocabulary " +
                    "(germanText, englishTranslation, timestamp, exampleSentence, " +
                    "article, plural, conjugation, nextReview, interval, easeFactor, reviewCount) " +
                    "VALUES ('schnell', 'fast', 1000, '', 'none', '', '', 0, 0, 2.5, 0)"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 9, true, MIGRATION_8_9)

        val database = openAsReleaseWould()
        try {
            val saved = runBlocking { database.vocabularyDao().getAllVocabulary().first() }
            assertEquals(1, saved.size)
            assertEquals("", saved.first().synonyms)
            assertEquals("", saved.first().antonyms)
            assertEquals("fast", saved.first().englishTranslation)
        } finally {
            database.close()
        }
    }

    /**
     * The 9 -> 10 upgrade, which adds the table the heatmap reads.
     *
     * A new table cannot lose data, so what is worth asserting is that it is usable:
     * `addXp` is a read-modify-write, and the date is the primary key, so a second
     * award on the same day has to add to the first rather than replace it.
     */
    @Test
    fun theActivityLogArrivesAndAccumulates() {
        helper.createDatabase(TEST_DB, 9).use { /* no fixture needed */ }

        helper.runMigrationsAndValidate(TEST_DB, 10, true, MIGRATION_9_10)

        val database = openAsReleaseWould()
        try {
            runBlocking {
                database.activityDao().addXp("2026-08-19", 10)
                database.activityDao().addXp("2026-08-19", 10)
                database.activityDao().addXp("2026-08-18", 30)
            }

            // Both fixture dates are inside any window; "0000-01-01" just means "all".
            val log = runBlocking { database.activityDao().getActivitySince(ALL_TIME).first() }
            assertEquals(2, log.size)
            assertEquals(20, log.first { it.date == "2026-08-19" }.xpGained)
            assertEquals(30, log.first { it.date == "2026-08-18" }.xpGained)
        } finally {
            database.close()
        }
    }

    /**
     * The 10 -> 11 upgrade, on both tables it touches.
     *
     * `remoteId` is the assertion that matters. The SQL default is the empty string,
     * so a migration that only adds the column leaves every record that predates it -
     * which is the user's entire library - with no cross-device identity, while
     * everything saved afterwards gets a UUID from the entity default. Each row must
     * come out with its own id, not one shared value.
     */
    @Test
    fun theSyncColumnsArriveWithAnIdentityForEveryExistingRow() {
        helper.createDatabase(TEST_DB, 10).use { db ->
            db.execSQL(
                "INSERT INTO vocabulary " +
                    "(germanText, englishTranslation, timestamp, exampleSentence, article, " +
                    "plural, conjugation, nextReview, interval, easeFactor, reviewCount, " +
                    "synonyms, antonyms) " +
                    "VALUES ('der Hund', 'the dog', 1000, '', 'der', 'Hunde', '', " +
                    "0, 0, 2.5, 0, '', '')"
            )
            db.execSQL(
                "INSERT INTO vocabulary " +
                    "(germanText, englishTranslation, timestamp, exampleSentence, article, " +
                    "plural, conjugation, nextReview, interval, easeFactor, reviewCount, " +
                    "synonyms, antonyms) " +
                    "VALUES ('die Katze', 'the cat', 2000, '', 'die', 'Katzen', '', " +
                    "0, 0, 2.5, 0, '', '')"
            )
            db.execSQL("INSERT INTO transcripts (fullText, timestamp) VALUES ('Guten Tag', 500)")
        }

        helper.runMigrationsAndValidate(TEST_DB, 11, true, MIGRATION_10_11)

        val database = openAsReleaseWould()
        try {
            val saved = runBlocking { database.vocabularyDao().getAllVocabulary().first() }
            assertEquals(2, saved.size)
            saved.forEach {
                assertEquals(UUID_LENGTH, it.remoteId.length)
                assertTrue("lastModifiedAt was not backfilled", it.lastModifiedAt > 0L)
            }
            // Per row, not per statement: one shared id would collide the moment two
            // devices synced the same library.
            assertNotEquals(saved[0].remoteId, saved[1].remoteId)

            val transcripts = runBlocking { database.transcriptDao().getAllTranscripts().first() }
            assertEquals(1, transcripts.size)
            assertEquals(UUID_LENGTH, transcripts.first().remoteId.length)
            assertTrue("lastModifiedAt was not backfilled", transcripts.first().lastModifiedAt > 0L)
            assertEquals("Guten Tag", transcripts.first().fullText)
        } finally {
            database.close()
        }
    }

    /**
     * The 11 -> 12 step, which exists only to let Room rewrite a stale identity hash.
     *
     * The interesting assertion is not that a column arrived - none did - but that a
     * database holding the *older* v11 opens at all. Before this migration it threw
     * "Room cannot verify the data integrity" on first access, in debug builds as
     * well as release, because the identity check runs in onOpen and ignores
     * fallbackToDestructiveMigration entirely.
     */
    @Test
    fun theOlderVersion11StillOpensAndKeepsItsRows() {
        helper.createDatabase(TEST_DB, 11).use { db ->
            db.execSQL(
                "INSERT INTO vocabulary " +
                    "(germanText, englishTranslation, timestamp, exampleSentence, article, " +
                    "plural, conjugation, nextReview, interval, easeFactor, reviewCount, " +
                    "synonyms, antonyms, remoteId, lastModifiedAt) " +
                    "VALUES ('der Baum', 'the tree', 1000, '', 'der', 'Bäume', '', " +
                    "0, 0, 2.5, 0, '', '', 'fixture-uuid', 1000)"
            )
            db.execSQL(
                "INSERT INTO transcripts (fullText, timestamp, remoteId, lastModifiedAt) " +
                    "VALUES ('Guten Morgen', 500, 'fixture-uuid-2', 500)"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 12, true, MIGRATION_11_12)

        val database = openAsReleaseWould()
        try {
            val saved = runBlocking { database.vocabularyDao().getAllVocabulary().first() }
            assertEquals(1, saved.size)
            assertEquals("der Baum", saved.first().germanText)
            // Nothing about the row changes; the version step is purely so the
            // recorded hash can be brought up to date.
            assertEquals("fixture-uuid", saved.first().remoteId)

            val transcripts = runBlocking { database.transcriptDao().getAllTranscripts().first() }
            assertEquals(1, transcripts.size)
            assertEquals("Guten Morgen", transcripts.first().fullText)
        } finally {
            database.close()
        }
    }

    /**
     * The whole run a real install takes, 7 to 12 in one go.
     *
     * The per-step tests each start from a hand-written fixture; this one is the only
     * check that the steps compose - that a row written by version 7 survives all four
     * and still reads correctly through the release configuration.
     */
    @Test
    fun aWordWrittenAtVersion7SurvivesEveryUpgradeToTheCurrentSchema() {
        helper.createDatabase(TEST_DB, 7).use { db ->
            db.execSQL(
                "INSERT INTO vocabulary " +
                    "(germanText, englishTranslation, timestamp, exampleSentence, " +
                    "article, plural, conjugation) " +
                    "VALUES ('die Übung', 'the exercise', 1000, 'Ich mache meine Übungen.', " +
                    "'die', 'Übungen', '')"
            )
        }

        helper.runMigrationsAndValidate(
            TEST_DB, 12, true,
            MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12
        )

        val database = openAsReleaseWould()
        try {
            val saved = runBlocking { database.vocabularyDao().getAllVocabulary().first() }
            assertEquals(1, saved.size)

            val word = saved.first()
            assertEquals("die Übung", word.germanText)
            assertEquals("the exercise", word.englishTranslation)
            assertEquals("Ich mache meine Übungen.", word.exampleSentence)
            assertEquals("Übungen", word.plural)
            assertEquals("", word.synonyms)
            assertEquals(2.5f, word.easeFactor, 0.0001f)
            assertEquals(UUID_LENGTH, word.remoteId.length)
        } finally {
            database.close()
        }
    }

    /**
     * MIGRATION_12_13: the fold key learns German, and the rows that now collide are
     * merged rather than dropped.
     *
     * The fixture is every case that changes behaviour. Uniqueness was NOCASE on
     * `germanText`, which folds ASCII A-Z and nothing else - so "Hund"/"hund" were
     * already one row, while "Übung"/"übung", "Öl"/"öl" and "Straße"/"Strasse" were
     * each two. This is the migration most able to lose a user's words, so it
     * asserts on what survives, not just on the count.
     */
    @Test
    fun theGermanFoldMergesUmlautDuplicatesWithoutLosingAnything() {
        helper.createDatabase(TEST_DB, 12).use { db ->
            fun insert(
                german: String,
                english: String,
                timestamp: Long,
                article: String = "",
                plural: String = "",
                example: String = "",
                nextReview: Long = 0,
                interval: Int = 0,
                reviewCount: Int = 0
            ) = db.execSQL(
                "INSERT INTO vocabulary (germanText, englishTranslation, timestamp, " +
                    "article, plural, exampleSentence, nextReview, interval, reviewCount) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                // Explicitly Any?, or Kotlin infers an intersection type for the
                // mixed String/Long/Int arguments and warns about reifying it.
                arrayOf<Any?>(
                    german, english, timestamp, article, plural, example,
                    nextReview, interval, reviewCount
                )
            )

            // A month of reviews on one spelling, a week on the other.
            insert("Übung", "exercise", 100, article = "die", plural = "Übungen",
                nextReview = 5_000, interval = 30, reviewCount = 8)
            insert("übung", "practice", 200, example = "Eine Übung.",
                nextReview = 100, interval = 1, reviewCount = 1)

            insert("Straße", "street", 300, article = "die", plural = "Straßen")
            insert("Strasse", "road", 400, example = "Eine Strasse.")

            insert("Öl", "oil", 500, article = "das")
            insert("öl", "petroleum", 600, plural = "Öle")

            // Untouched: no umlaut, no transliteration, nothing to fold together.
            insert("Hund", "dog", 700, article = "der")
            insert("gehen", "to go", 800)
        }

        val database = openAsReleaseWould()
        try {
            val saved = runBlocking { database.vocabularyDao().getAllVocabulary().first() }

            assertEquals("eight rows fold to five words", 5, saved.size)
            assertEquals(
                setOf("uebung", "strasse", "oel", "hund", "gehen"),
                saved.map { it.germanTextKey }.toSet()
            )

            val uebung = saved.single { it.germanTextKey == "uebung" }
            // Every field either copy knew, kept.
            assertEquals("die", uebung.article)
            assertEquals("Übungen", uebung.plural)
            assertEquals("Eine Übung.", uebung.exampleSentence)
            // Latest non-blank wins, and the group keeps its greatest timestamp.
            assertEquals("practice", uebung.englishTranslation)
            assertEquals(200L, uebung.timestamp)
            // The month of reviews, not the week: a merge must never cost history.
            assertEquals(8, uebung.reviewCount)
            assertEquals(30, uebung.interval)
            assertEquals(5_000L, uebung.nextReview)

            val strasse = saved.single { it.germanTextKey == "strasse" }
            assertEquals("die", strasse.article)
            assertEquals("Straßen", strasse.plural)
            assertEquals("Eine Strasse.", strasse.exampleSentence)

            val oel = saved.single { it.germanTextKey == "oel" }
            assertEquals("das", oel.article)
            assertEquals("Öle", oel.plural)

            // The words that were never duplicates come through untouched.
            assertEquals("dog", saved.single { it.germanTextKey == "hund" }.englishTranslation)
            assertEquals("to go", saved.single { it.germanTextKey == "gehen" }.englishTranslation)
        } finally {
            database.close()
        }
    }

    /**
     * The fold key the migration computes in SQL must equal the one the app computes
     * in Kotlin, or a word saved after the upgrade would not find the row the
     * upgrade made for it.
     */
    @Test
    fun theMigrationsKeyMatchesTheKotlinOne() {
        val words = listOf("Hund", "Übung", "übung", "Uebung", "Straße", "Strasse", "Öl", "Ärger")

        helper.createDatabase(TEST_DB, 12).use { db ->
            words.forEachIndexed { index, word ->
                db.execSQL(
                    "INSERT INTO vocabulary (germanText, englishTranslation, timestamp) VALUES (?, ?, ?)",
                    arrayOf<Any?>(word, "meaning $index", index.toLong())
                )
            }
        }

        val database = openAsReleaseWould()
        try {
            val saved = runBlocking { database.vocabularyDao().getAllVocabulary().first() }
            for (row in saved) {
                assertEquals(
                    "SQL and Kotlin disagree on the key for \"${row.germanText}\"",
                    germanKey(row.germanText),
                    row.germanTextKey
                )
            }
            // Every distinct word is still findable by any of its spellings.
            runBlocking {
                assertNotNull(database.vocabularyDao().findByGermanText("uebung"))
                assertNotNull(database.vocabularyDao().findByGermanText("ÜBUNG"))
                assertNotNull(database.vocabularyDao().findByGermanText("Strasse"))
            }
        } finally {
            database.close()
        }
    }

    /**
     * MIGRATION_13_14: roleplay_messages arrives, and everything already stored
     * comes through it untouched.
     *
     * Nothing is backfilled - the table starts empty either way - so what is worth
     * asserting is the other half: that adding it does not disturb the vocabulary
     * and transcripts an existing install already has, and that the new table is
     * actually usable through the DAO afterwards.
     */
    @Test
    fun theRoleplayTableArrivesEmptyAndDisturbsNothing() {
        helper.createDatabase(TEST_DB, 13).use { db ->
            db.execSQL(
                "INSERT INTO vocabulary (germanText, germanTextKey, englishTranslation, timestamp) " +
                    "VALUES ('die Übung', 'die uebung', 'the exercise', 100)"
            )
            db.execSQL("INSERT INTO transcripts (fullText, timestamp) VALUES ('Guten Tag', 200)")
        }

        val database = openAsReleaseWould()
        try {
            runBlocking {
                val words = database.vocabularyDao().getAllVocabulary().first()
                assertEquals(1, words.size)
                assertEquals("die Übung", words.first().germanText)
                assertEquals(1, database.transcriptDao().getAllTranscripts().first().size)

                val dao = database.roleplayDao()
                assertEquals(emptyList<RoleplayMessageEntity>(), dao.getConversation())

                dao.insert(
                    RoleplayMessageEntity(
                        position = 0,
                        scenario = "Ordering at a Berlin Bakery",
                        role = "assistant",
                        content = "Guten Morgen! Was darf es sein?",
                        translation = "Good morning! What would you like?",
                        timestamp = 300
                    )
                )
                dao.insert(
                    RoleplayMessageEntity(
                        position = 1,
                        scenario = "Ordering at a Berlin Bakery",
                        role = "user",
                        content = "Ein Brötchen, bitte.",
                        timestamp = 400
                    )
                )

                val saved = dao.getConversation()
                assertEquals(2, saved.size)
                // Oldest first: the order the chat renders in, and the order a
                // restored conversation has to come back in.
                assertEquals("assistant", saved.first().role)
                assertEquals("Ein Brötchen, bitte.", saved[1].content)
                // A user turn has no gloss, so the column has to be nullable.
                assertNull(saved[1].translation)

                dao.deleteAll()
                assertEquals(emptyList<RoleplayMessageEntity>(), dao.getConversation())
            }
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

        /** "8-4-4-4-12" plus four hyphens — the shape UUID.toString() produces. */
        const val UUID_LENGTH = 36

        /** A date bound low enough to mean "every row", for tests that want the lot. */
        const val ALL_TIME = "0000-01-01"
    }
}
