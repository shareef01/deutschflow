package com.aus.deutschflow.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    arrayOf(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
