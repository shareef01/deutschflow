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
 * Every migration the app has ever needed, in order. Declared last: top-level
 * properties initialise in file order, so it has to follow what it references.
 *
 * Release builds have no destructive fallback, so a gap here is a crash on launch
 * for every existing install. AppDatabaseMigrationTest walks this list.
 */
val MIGRATIONS = arrayOf(MIGRATION_2_3)
