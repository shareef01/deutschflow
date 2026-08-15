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
val MIGRATIONS = arrayOf(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
