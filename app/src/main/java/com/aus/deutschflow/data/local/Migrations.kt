package com.aus.deutschflow.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aus.deutschflow.data.local.entities.germanKey
import java.text.Normalizer

/**
 * Adds the example sentence to the vocabulary table.
 *
 * Gemini was already returning an example for every translation and the app was
 * throwing it away; the detail screen showed a randomly chosen canned template
 * instead. Existing rows have no example, which is what the empty default means -
 * the screen falls back to the generated sentence for them, exactly as it does for
 * words the user typed in by hand.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE vocabulary ADD COLUMN exampleSentence TEXT NOT NULL DEFAULT ''"
        )
    }
}

/**
 * Drops isFavorite, which nothing ever read or wrote.
 *
 * The whole table is rebuilt rather than altered: ALTER TABLE ... DROP COLUMN needs
 * SQLite 3.35, and minSdk 31 ships 3.32, so the column cannot simply be dropped on
 * the oldest devices this app supports. The CREATE below is Room's own generated DDL
 * for version 4, copied from the exported schema - if it drifts from that by so much
 * as a default, runMigrationsAndValidate fails.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `vocabulary_new` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`germanText` TEXT NOT NULL, " +
                "`englishTranslation` TEXT NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, " +
                "`exampleSentence` TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL(
            "INSERT INTO `vocabulary_new` " +
                "(`id`, `germanText`, `englishTranslation`, `timestamp`, `exampleSentence`) " +
                "SELECT `id`, `germanText`, `englishTranslation`, `timestamp`, `exampleSentence` " +
                "FROM `vocabulary`"
        )
        db.execSQL("DROP TABLE `vocabulary`")
        db.execSQL("ALTER TABLE `vocabulary_new` RENAME TO `vocabulary`")
    }
}

/**
 * Indexes the timestamp columns the list screens order by.
 *
 * History and Library both read whole tables ORDER BY timestamp DESC, and neither
 * column was indexed, so every emission re-sorted the table in full. The indexes turn
 * that sort into an index scan. Text search itself stays in memory - a `contains`
 * over the loaded list - because that is what preserves infix matching, which FTS
 * token queries would not.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_transcripts_timestamp` ON `transcripts` (`timestamp`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_vocabulary_timestamp` ON `vocabulary` (`timestamp`)"
        )
    }
}

/**
 * Adds the grammatical fields the single-word interrogation produces.
 *
 * Article, plural and conjugation were only ever shown in the detail sheet, then
 * dropped on save; the library now keeps them. Empty default means hand-typed words
 * and rows migrated from v5 behave exactly as before.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE vocabulary ADD COLUMN article TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE vocabulary ADD COLUMN plural TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE vocabulary ADD COLUMN conjugation TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * The latest non-blank [column] among the rows sharing the name of the row being
 * written, or the empty string when no copy of the word ever carried one.
 *
 * Correlated on `v`, so it is only meaningful inside MIGRATION_6_7's SELECT. Every
 * column it reads is NOT NULL, so the COALESCE guards an empty result set rather than
 * a null value.
 */
private fun latestNonBlank(column: String): String =
    "COALESCE((SELECT w.`$column` FROM `vocabulary` w " +
        "WHERE w.`germanText` = v.`germanText` COLLATE NOCASE AND w.`$column` <> '' " +
        "ORDER BY w.`timestamp` DESC, w.`id` DESC LIMIT 1), '')"

/**
 * Makes the word unique, and folds together the duplicates already out there.
 *
 * Saving a word the library already held minted a second row, which is easy to do now
 * that one tap on a chip saves. The copies then read as repeat cards in Study, inflated
 * the count in Settings, and gave that word extra weight in the daily rotation.
 *
 * A rebuild rather than an ALTER, for two reasons: the column gains a NOCASE collation,
 * which ALTER TABLE cannot change, and the existing rows have to be deduplicated before
 * a unique index over them can be created at all. This is the same shape as
 * MIGRATION_3_4 and carries the same risk - a mistake here loses saved words, not one
 * column - so the CREATE below is Room's own generated DDL for version 7, and
 * AppDatabaseMigrationTest walks it with duplicates in the fixture.
 *
 * The duplicates are merged rather than picked between, field by field, under exactly
 * the rule [VocabularyEntity.mergedWith] applies at runtime: for each field the latest
 * non-blank value in the group wins, and the row keeps the greatest timestamp. Choosing
 * one row wholesale would have been far less SQL, and would have thrown away a
 * translation the user had edited by hand whenever some other copy happened to carry
 * the grammar. The surviving row's id is the richest one's, so a word keeps the
 * identity most of the library's history points at.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `vocabulary_new` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`germanText` TEXT NOT NULL COLLATE NOCASE, " +
                "`englishTranslation` TEXT NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, " +
                "`exampleSentence` TEXT NOT NULL DEFAULT '', " +
                "`article` TEXT NOT NULL DEFAULT '', " +
                "`plural` TEXT NOT NULL DEFAULT '', " +
                "`conjugation` TEXT NOT NULL DEFAULT '')"
        )

        // One row per word, carrying the best of everything the group knew. The name is
        // compared with NOCASE explicitly: the *old* column has no collation of its own,
        // so the grouping has to say which one it means.
        db.execSQL(
            "INSERT INTO `vocabulary_new` " +
                "(`id`, `germanText`, `englishTranslation`, `timestamp`, " +
                "`exampleSentence`, `article`, `plural`, `conjugation`) " +
                "SELECT v.`id`, v.`germanText`, " +
                latestNonBlank("englishTranslation") + ", " +
                // The group's latest touch, so a word merged out of several surfaces
                // where the most recent of them put it.
                "(SELECT MAX(w.`timestamp`) FROM `vocabulary` w " +
                "WHERE w.`germanText` = v.`germanText` COLLATE NOCASE), " +
                latestNonBlank("exampleSentence") + ", " +
                latestNonBlank("article") + ", " +
                latestNonBlank("plural") + ", " +
                latestNonBlank("conjugation") + " " +
                "FROM `vocabulary` v WHERE v.`id` = (" +
                "SELECT w.`id` FROM `vocabulary` w " +
                "WHERE w.`germanText` = v.`germanText` COLLATE NOCASE " +
                "ORDER BY (CASE WHEN w.`article` <> '' THEN 1 ELSE 0 END) " +
                "+ (CASE WHEN w.`plural` <> '' THEN 1 ELSE 0 END) " +
                "+ (CASE WHEN w.`conjugation` <> '' THEN 1 ELSE 0 END) " +
                "+ (CASE WHEN w.`exampleSentence` <> '' THEN 1 ELSE 0 END) DESC, " +
                "w.`timestamp` DESC, w.`id` DESC LIMIT 1)"
        )

        db.execSQL("DROP TABLE `vocabulary`")
        db.execSQL("ALTER TABLE `vocabulary_new` RENAME TO `vocabulary`")

        // After the rename, or they would be created on a table that is about to be
        // renamed out from under them.
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_vocabulary_timestamp` ON `vocabulary` (`timestamp`)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_vocabulary_germanText` " +
                "ON `vocabulary` (`germanText`)"
        )
    }
}

/**
 * Adds Spaced Repetition (SRS) fields to the vocabulary table.
 *
 * This enables the "Ebbinghaus" engine, tracking nextReview (timestamp), interval
 * (days), easeFactor (float) and reviewCount (int).
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE vocabulary ADD COLUMN nextReview INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE vocabulary ADD COLUMN interval INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE vocabulary ADD COLUMN easeFactor REAL NOT NULL DEFAULT 2.5")
        db.execSQL("ALTER TABLE vocabulary ADD COLUMN reviewCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_vocabulary_nextReview` ON `vocabulary` (`nextReview`)")
    }
}

/**
 * Adds linguistic fields: synonyms and antonyms.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE vocabulary ADD COLUMN synonyms TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE vocabulary ADD COLUMN antonyms TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * Adds the activity_log table for the Mastery Dashboard.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `activity_log` (" +
                "`date` TEXT NOT NULL, " +
                "`xpGained` INTEGER NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, " +
                "PRIMARY KEY(`date`))"
        )
    }
}

/**
 * Adds stable record identity and modification metadata.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE vocabulary ADD COLUMN remoteId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE vocabulary ADD COLUMN lastModifiedAt INTEGER NOT NULL DEFAULT 0")
        
        db.execSQL("ALTER TABLE transcripts ADD COLUMN remoteId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE transcripts ADD COLUMN lastModifiedAt INTEGER NOT NULL DEFAULT 0")
        
        // Existing rows need both, not just the timestamp. Leaving remoteId at
        // its SQL default of '' would have given every record that predates this
        // migration - which is all of them - no durable identity at all, while
        // the entity's Kotlin default mints a
        // fresh UUID for everything saved afterwards.
        val now = System.currentTimeMillis()
        db.execSQL("UPDATE vocabulary SET lastModifiedAt = $now, remoteId = $UUID_V4")
        db.execSQL("UPDATE transcripts SET lastModifiedAt = $now, remoteId = $UUID_V4")
    }
}

/**
 * A version-4 UUID built out of SQLite's own randomness, as a SQL expression.
 *
 * `randomblob` is re-evaluated per row, so an UPDATE over the whole table gives
 * each row its own id rather than one shared value. The literal `4` and the
 * `[89ab]` pick are the version and variant nibbles the format requires; the rest
 * is random. Matches `UUID.randomUUID().toString()` in shape and case.
 */
private const val UUID_V4 =
    "lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4' || " +
        "substr(hex(randomblob(2)), 2) || '-' || " +
        // `& 3` rather than `abs(...) % 4`: SQLite's abs() raises an integer
        // overflow on exactly one input, random()'s most negative value, and a
        // migration is the last place worth carrying a one-in-2^64 crash.
        "substr('89ab', (random() & 3) + 1, 1) || " +
        "substr(hex(randomblob(2)), 2) || '-' || hex(randomblob(6)))"

/**
 * Reconciles the two shapes version 11 was briefly allowed to have.
 *
 * `transcripts.remoteId` and `lastModifiedAt` were declared NOT NULL with no SQL
 * default, while MIGRATION_10_11 adds them *with* one. A database that migrated into
 * v11 therefore carried `DEFAULT ''`, and one created fresh at v11 did not - the same
 * version number over two different tables. Declaring the defaults on the entity
 * fixed the divergence, but it also changed the DDL Room hashes, so a device already
 * holding the older v11 opens with a matching version, a mismatched identity hash,
 * and `IllegalStateException: Room cannot verify the data integrity` on launch.
 *
 * `fallbackToDestructiveMigration` does not catch that: the identity check runs in
 * onOpen and throws whatever the fallback says, so debug builds crash exactly like
 * release ones rather than quietly recreating.
 *
 * Nothing to do here. The columns exist on both shapes and the physical table on the
 * migrated side already carries the defaults; only the *recorded* hash is stale, and
 * stepping the version is what lets Room rewrite it. The migration exists so that
 * step happens without anyone losing a library over an unreleased version number.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Deliberately empty - see above.
    }
}

/**
 * The fold [germanKey] used to be, written out so shipped migrations keep the meaning
 * they were written and tested with.
 *
 * [MIGRATION_12_13] and [MIGRATION_14_15] both re-key every row through
 * [backfillGermanKeys], and they merge whatever collides. If they called the live
 * [germanKey], then the day that function changes, those two migrations silently
 * change too - re-keying to today's rules and merging on today's idea of which words
 * are equal. An install upgrading from v12 would then be rebuilt by rules from three
 * versions later, and a migration's behaviour would depend on when it happened to run
 * rather than on the schema it bridges.
 *
 * So the fold is spelled out in full rather than derived from the live one. It used to
 * read `germanKey(text).replace("ß", "ss")`, which reproduces the historical fold only
 * for as long as ß is the *sole* difference between the two: the normalisation and
 * umlaut rules were shared with the live function, so a later change to any of them
 * would have re-keyed these two migrations silently - exactly the failure the freeze
 * exists to prevent. ß→ss is kept deliberately: that *is* what v13 and v15 did, their
 * tests assert it, and re-running them must reproduce it exactly. MIGRATION_16_17 is
 * what moves the app to the stricter key, and it calls the live [germanKey] on purpose.
 */
internal fun legacyGermanKey(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFC)
        .trim()
        .lowercase()
        .replace("ä", "ae")
        .replace("ö", "oe")
        .replace("ü", "ue")
        .replace("ß", "ss")

/**
 * Backfills `germanTextKey` using the app's own fold, one row at a time.
 *
 * This was a SQL expression - uppercase umlauts replaced before `lower()`, the
 * lowercase ones after - carrying a comment that claimed it "matches germanKey
 * for every input the app can produce". It did not. SQLite's `lower()` is
 * ASCII-only, so it folded the four umlauts this expression hand-rolled and
 * nothing else: any *other* uppercase non-ASCII letter came through untouched,
 * and "CAFÉ" migrated to `cafÉ` where [germanKey] gives `café`. A row keyed that
 * way is invisible to every lookup the app makes, so it could never be found,
 * merged or deduplicated again.
 *
 * Calling the real function removes the whole class of divergence rather than
 * chasing accents one `replace()` at a time. The rows are read into memory first:
 * updating through an open cursor over the same table is undefined.
 *
 * Uses [legacyGermanKey], not [germanKey] - see there for why a shipped migration
 * must not track the live fold.
 */
private fun backfillGermanKeys(db: SupportSQLiteDatabase) {
    val keys = mutableListOf<Pair<Long, String>>()
    db.query("SELECT `id`, `germanText` FROM `vocabulary`").use { cursor ->
        while (cursor.moveToNext()) {
            keys += cursor.getLong(0) to legacyGermanKey(cursor.getString(1))
        }
    }
    for ((id, key) in keys) {
        db.execSQL(
            "UPDATE `vocabulary` SET `germanTextKey` = ? WHERE `id` = ?",
            arrayOf<Any?>(key, id)
        )
    }
}

/**
 * Makes duplicate detection understand German.
 *
 * Uniqueness was SQLite's NOCASE collation on `germanText`, which folds ASCII A-Z
 * and nothing else - so "Hund" and "hund" were one word while "Übung" and "übung"
 * were two. Every umlaut-initial noun escaped deduplication and quietly accumulated
 * copies, which then read as repeat cards in Study, inflated the library count and
 * took extra turns in the daily rotation: exactly the symptoms MIGRATION_6_7 was
 * written to cure for the ASCII case.
 *
 * No table rebuild this time. The collation on `germanText` is staying (search and
 * ordering still read it), so this only adds a column, fills it, folds together the
 * rows that now collide, and moves the unique index - and DROP INDEX works on every
 * SQLite this app supports, unlike the DROP COLUMN that forced the rebuild in
 * MIGRATION_3_4.
 *
 * The duplicates are merged field by field under the rule [VocabularyEntity.mergedWith]
 * applies at runtime: the richest row survives and keeps its id, each field takes the
 * latest non-blank value in its group, and the row keeps the greatest timestamp. The
 * surviving row also keeps the *furthest-along* SRS state in the group rather than
 * its own, because losing review history is the one thing a merge must not do - a
 * user who has been drilling "Übung" for a month and "übung" for a week should come
 * out of this with the month.
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE vocabulary ADD COLUMN germanTextKey TEXT NOT NULL DEFAULT ''")
        backfillGermanKeys(db)

        // The row each group collapses into: most grammar filled in, then most
        // recently touched, then highest id. Same ranking as MIGRATION_6_7.
        val winner =
            "SELECT w.`id` FROM `vocabulary` w WHERE w.`germanTextKey` = v.`germanTextKey` " +
                "ORDER BY (CASE WHEN w.`article` <> '' THEN 1 ELSE 0 END) " +
                "+ (CASE WHEN w.`plural` <> '' THEN 1 ELSE 0 END) " +
                "+ (CASE WHEN w.`conjugation` <> '' THEN 1 ELSE 0 END) " +
                "+ (CASE WHEN w.`exampleSentence` <> '' THEN 1 ELSE 0 END) " +
                "+ (CASE WHEN w.`synonyms` <> '' THEN 1 ELSE 0 END) " +
                "+ (CASE WHEN w.`antonyms` <> '' THEN 1 ELSE 0 END) DESC, " +
                "w.`timestamp` DESC, w.`id` DESC LIMIT 1"

        // Fold every group's knowledge into its winner before anything is deleted.
        db.execSQL(
            "UPDATE `vocabulary` SET " +
                latest("englishTranslation") + ", " +
                latest("exampleSentence") + ", " +
                latest("article") + ", " +
                latest("plural") + ", " +
                latest("conjugation") + ", " +
                latest("synonyms") + ", " +
                latest("antonyms") + ", " +
                "`timestamp` = (SELECT MAX(w.`timestamp`) FROM `vocabulary` w " +
                "WHERE w.`germanTextKey` = `vocabulary`.`germanTextKey`), " +
                // The furthest-along schedule in the group, taken as a set so the
                // four SRS fields stay consistent with each other.
                "`nextReview` = (SELECT w.`nextReview` FROM `vocabulary` w " +
                "WHERE w.`germanTextKey` = `vocabulary`.`germanTextKey` " +
                "ORDER BY w.`reviewCount` DESC, w.`interval` DESC, w.`id` ASC LIMIT 1), " +
                "`interval` = (SELECT w.`interval` FROM `vocabulary` w " +
                "WHERE w.`germanTextKey` = `vocabulary`.`germanTextKey` " +
                "ORDER BY w.`reviewCount` DESC, w.`interval` DESC, w.`id` ASC LIMIT 1), " +
                "`easeFactor` = (SELECT w.`easeFactor` FROM `vocabulary` w " +
                "WHERE w.`germanTextKey` = `vocabulary`.`germanTextKey` " +
                "ORDER BY w.`reviewCount` DESC, w.`interval` DESC, w.`id` ASC LIMIT 1), " +
                "`reviewCount` = (SELECT MAX(w.`reviewCount`) FROM `vocabulary` w " +
                "WHERE w.`germanTextKey` = `vocabulary`.`germanTextKey`) " +
                "WHERE `id` IN (SELECT v.`id` FROM `vocabulary` v WHERE v.`id` = ($winner))"
        )

        // Then drop the rows that lost.
        db.execSQL(
            "DELETE FROM `vocabulary` WHERE `id` NOT IN " +
                "(SELECT v.`id` FROM `vocabulary` v WHERE v.`id` = ($winner))"
        )

        db.execSQL("DROP INDEX IF EXISTS `index_vocabulary_germanText`")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_vocabulary_germanTextKey` " +
                "ON `vocabulary` (`germanTextKey`)"
        )
    }

    /** The latest non-blank value of [column] among the rows sharing this row's key. */
    private fun latest(column: String): String =
        "`$column` = COALESCE((SELECT w.`$column` FROM `vocabulary` w " +
            "WHERE w.`germanTextKey` = `vocabulary`.`germanTextKey` AND w.`$column` <> '' " +
            "ORDER BY w.`timestamp` DESC, w.`id` DESC LIMIT 1), `$column`)"
}

/**
 * Adds roleplay_messages, so a conversation survives the process.
 *
 * The chat was ViewModel state and nothing else. Leaving the app mid-scene - the
 * likeliest thing to happen on the one screen where the user stops to compose a
 * German sentence - lost every turn, including the model's replies, which are the
 * material.
 *
 * Nothing to backfill: the table starts empty on upgrade and on a fresh install
 * alike, which is the same state the app had before it existed.
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `roleplay_messages` (" +
                "`position` INTEGER NOT NULL, " +
                "`scenario` TEXT NOT NULL, " +
                "`role` TEXT NOT NULL, " +
                "`content` TEXT NOT NULL, " +
                "`translation` TEXT, " +
                "`timestamp` INTEGER NOT NULL, " +
                "PRIMARY KEY(`position`))"
        )
    }
}

/**
 * Normalizes all germanTextKey values using Unicode NFC normalization.
 *
 * Precomposed and decomposed Unicode forms (such as "Übung" vs "U\u0308bung")
 * previously produced divergent keys. This migration re-folds all keys using
 * the NFC-aware [germanKey] function and merges any resulting collisions.
 *
 * To avoid UNIQUE constraint violations while re-keying, the unique index is
 * dropped, keys are backfilled, colliding groups are merged into their richest/
 * latest survivor, losing rows are deleted, and the unique index is restored.
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP INDEX IF EXISTS `index_vocabulary_germanTextKey`")
        backfillGermanKeys(db)

        val winner =
            "SELECT w.`id` FROM `vocabulary` w WHERE w.`germanTextKey` = v.`germanTextKey` " +
                "ORDER BY (CASE WHEN w.`article` <> '' THEN 1 ELSE 0 END) " +
                "+ (CASE WHEN w.`plural` <> '' THEN 1 ELSE 0 END) " +
                "+ (CASE WHEN w.`conjugation` <> '' THEN 1 ELSE 0 END) " +
                "+ (CASE WHEN w.`exampleSentence` <> '' THEN 1 ELSE 0 END) " +
                "+ (CASE WHEN w.`synonyms` <> '' THEN 1 ELSE 0 END) " +
                "+ (CASE WHEN w.`antonyms` <> '' THEN 1 ELSE 0 END) DESC, " +
                "w.`timestamp` DESC, w.`id` DESC LIMIT 1"

        db.execSQL(
            "UPDATE `vocabulary` SET " +
                latest("englishTranslation") + ", " +
                latest("exampleSentence") + ", " +
                latest("article") + ", " +
                latest("plural") + ", " +
                latest("conjugation") + ", " +
                latest("synonyms") + ", " +
                latest("antonyms") + ", " +
                "`timestamp` = (SELECT MAX(w.`timestamp`) FROM `vocabulary` w " +
                "WHERE w.`germanTextKey` = `vocabulary`.`germanTextKey`), " +
                "`lastModifiedAt` = (SELECT MAX(w.`lastModifiedAt`) FROM `vocabulary` w " +
                "WHERE w.`germanTextKey` = `vocabulary`.`germanTextKey`), " +
                "`nextReview` = (SELECT w.`nextReview` FROM `vocabulary` w " +
                "WHERE w.`germanTextKey` = `vocabulary`.`germanTextKey` " +
                "ORDER BY w.`reviewCount` DESC, w.`interval` DESC, w.`id` ASC LIMIT 1), " +
                "`interval` = (SELECT w.`interval` FROM `vocabulary` w " +
                "WHERE w.`germanTextKey` = `vocabulary`.`germanTextKey` " +
                "ORDER BY w.`reviewCount` DESC, w.`interval` DESC, w.`id` ASC LIMIT 1), " +
                "`easeFactor` = (SELECT w.`easeFactor` FROM `vocabulary` w " +
                "WHERE w.`germanTextKey` = `vocabulary`.`germanTextKey` " +
                "ORDER BY w.`reviewCount` DESC, w.`interval` DESC, w.`id` ASC LIMIT 1), " +
                "`reviewCount` = (SELECT MAX(w.`reviewCount`) FROM `vocabulary` w " +
                "WHERE w.`germanTextKey` = `vocabulary`.`germanTextKey`) " +
                "WHERE `id` IN (SELECT v.`id` FROM `vocabulary` v WHERE v.`id` = ($winner))"
        )

        db.execSQL(
            "DELETE FROM `vocabulary` WHERE `id` NOT IN " +
                "(SELECT v.`id` FROM `vocabulary` v WHERE v.`id` = ($winner))"
        )

        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_vocabulary_germanTextKey` " +
                "ON `vocabulary` (`germanTextKey`)"
        )
    }

    private fun latest(column: String): String =
        "`$column` = COALESCE((SELECT w.`$column` FROM `vocabulary` w " +
            "WHERE w.`germanTextKey` = `vocabulary`.`germanTextKey` AND w.`$column` <> '' " +
            "ORDER BY w.`timestamp` DESC, w.`id` DESC LIMIT 1), `$column`)"
}

/**
 * Version 16:
 * - Creates the `review_events` table and indices for immutable review history.
 * - Adds `translation` and `analysisJson` caching columns to `transcripts`.
 */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `review_events` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`vocabularyId` INTEGER NOT NULL, " +
                "`rating` TEXT NOT NULL, " +
                "`scheduledDays` INTEGER NOT NULL, " +
                "`actualDays` INTEGER NOT NULL, " +
                "`reviewedAtTimestamp` INTEGER NOT NULL, " +
                "`isExtraPractice` INTEGER NOT NULL, " +
                "`remoteId` TEXT NOT NULL DEFAULT ''" +
                ")"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_review_events_vocabularyId` " +
                "ON `review_events` (`vocabularyId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_review_events_reviewedAtTimestamp` " +
                "ON `review_events` (`reviewedAtTimestamp`)"
        )
        db.execSQL("ALTER TABLE `transcripts` ADD COLUMN `translation` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `transcripts` ADD COLUMN `analysisJson` TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * Recomputes `germanTextKey` with the ß-preserving [germanKey].
 *
 * The key used to fold ß to ss, which merged words that are not the same word:
 * "Maße" (measurements) and "Masse" (mass) both became `masse`, as did "Buße" and
 * "Busse". Because uniqueness is enforced on this column, [VocabularyDao.save] would
 * take one of the pair, merge the other into it field by field, and delete the loser -
 * fusing two words' translations, grammar and SRS progress, irreversibly.
 *
 * ## Why this migration can only ever split
 *
 * The new key is strictly finer than the old one: if two words share a new key they
 * necessarily shared the old one, so dropping the ß→ss replacement never brings two
 * previously-distinct keys into the same group. Recomputing can therefore only
 * *separate* rows that were wrongly welded together by an earlier migration - it
 * cannot create a new collision, and so it needs no merge pass and cannot lose a
 * row to the unique index.
 *
 * Note what that does and does not recover. If MIGRATION_12_13 already merged "Maße"
 * into "Masse", the surviving row still has the winner's id, spelling and SRS
 * history, and the loser's translations were copied in only where they did not
 * conflict. Re-splitting cannot reconstruct a row whose contents are gone, and it
 * cannot know which of the two the user actually meant. Those historical merges are
 * not automatically recoverable; the user re-adds the word if they still want it,
 * and from this version forward the two stay apart.
 *
 * Rows that were already correct - "Hund"/"hund", "Übung"/"Uebung" - keep exactly the
 * key they had, so their ids, review history and timestamps are untouched.
 */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Same row-by-row approach as backfillGermanKeys: updating through an open
        // cursor over the same table is undefined, so read first, then write.
        val keys = mutableListOf<Pair<Long, String>>()
        db.query("SELECT `id`, `germanText` FROM `vocabulary`").use { cursor ->
            while (cursor.moveToNext()) {
                keys += cursor.getLong(0) to germanKey(cursor.getString(1))
            }
        }
        for ((id, key) in keys) {
            db.execSQL(
                "UPDATE `vocabulary` SET `germanTextKey` = ? WHERE `id` = ?",
                arrayOf<Any?>(key, id)
            )
        }
    }
}

/**
 * Re-keys saved roleplay conversations from the scenario's display title to its id.
 *
 * `roleplay_messages.scenario` recorded the human title ("Ordering at a Berlin
 * Bakery"), which made a saved conversation's identity a display string: renaming the
 * scenario - or translating it, the same edit - orphaned it and reset the screen to the
 * default scene. It now records the catalog id ("bakery"), so identity survives any
 * change to the wording.
 *
 * The mapping is written out rather than read from
 * [com.aus.deutschflow.data.model.RoleplayScenarioCatalog], for the reason
 * [legacyGermanKey] gives: a migration must re-encode titles as they were when it
 * shipped, not as the catalog reads years later. A row whose scenario matches no title
 * here is left alone - it is already an id, or a scene this build no longer ships, and
 * blanking it would throw away the only record of which conversation it was.
 */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for ((title, id) in SCENARIO_TITLE_TO_ID) {
            db.execSQL(
                "UPDATE `roleplay_messages` SET `scenario` = ? WHERE `scenario` = ?",
                arrayOf<Any?>(id, title)
            )
        }
    }
}

/** The catalog's titles as [MIGRATION_17_18] shipped them, keyed to their stable ids. */
private val SCENARIO_TITLE_TO_ID = mapOf(
    "Ordering at a Berlin Bakery" to "bakery",
    "Ordering at a Viennese Café" to "cafe",
    "Asking for Items at a Supermarket" to "supermarket",
    "Buying a Train Ticket at the Station" to "train_station",
    "Registering at the Bürgeramt" to "buergeramt",
    "Visiting a Doctor's Clinic" to "doctor",
    "Apartment Viewing in Munich" to "apartment",
    "Job Interview for a Professional Role" to "job_interview"
)

/**
 * Every migration the app has ever needed, in order. Declared last: top-level
 * properties initialise in file order, so it has to follow what it references.
 *
 * Release builds have no destructive fallback, so a gap here is a crash on launch
 * for every existing install. AppDatabaseMigrationTest walks this list.
 *
 * The list starts at 2 on purpose. Version 1 predates schema export - there is no
 * app/schemas/1.json to migrate from or validate against - and it was never
 * published: versionCode has been 1 since the first release build, and debug builds
 * keep fallbackToDestructiveMigration, so the only databases that ever reached
 * version 1 were developer ones that have since been recreated. A 1 -> 2 migration
 * would therefore be untestable and unreachable, not a missing safety net.
 */
val MIGRATIONS =
    arrayOf(
        MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
        MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
        MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
        MIGRATION_16_17, MIGRATION_17_18
    )
