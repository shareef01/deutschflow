package com.aus.deutschflow.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aus.deutschflow.data.local.dao.TranscriptDao
import com.aus.deutschflow.data.local.dao.UserStatsDao
import com.aus.deutschflow.data.local.dao.VocabularyDao
import com.aus.deutschflow.data.local.entities.TranscriptEntity
import com.aus.deutschflow.data.local.entities.UserStatsEntity
import com.aus.deutschflow.data.local.entities.VocabularyEntity

/**
 * Schemas are exported to app/schemas so that future versions can be migrated
 * rather than dropped, and so migrations can be tested.
 *
 * Construction lives in DatabaseModule: the hand-rolled companion singleton this
 * class used to carry duplicated Hilt's @Singleton and never re-checked the
 * instance inside its own synchronized block, so two threads racing the first
 * access could each build a database.
 */
/**
 * Bump this and add a Migration in the same change. Release builds have no
 * destructive fallback, so a version without a migration is a crash on launch;
 * AppDatabaseMigrationTest is what turns that into a failing test instead.
 */
const val DATABASE_VERSION = 4

@Database(
    entities = [VocabularyEntity::class, TranscriptEntity::class, UserStatsEntity::class],
    version = DATABASE_VERSION,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vocabularyDao(): VocabularyDao
    abstract fun transcriptDao(): TranscriptDao
    abstract fun userStatsDao(): UserStatsDao

    companion object {
        const val NAME = "deutschflow_database"
    }
}
