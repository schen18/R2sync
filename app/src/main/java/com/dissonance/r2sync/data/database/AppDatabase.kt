package com.dissonance.r2sync.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dissonance.r2sync.data.dao.ClientAppDao
import com.dissonance.r2sync.data.dao.ConflictDao
import com.dissonance.r2sync.data.dao.FileMetadataDao
import com.dissonance.r2sync.data.dao.SyncHistoryDao
import com.dissonance.r2sync.data.dao.SyncedFolderDao
import com.dissonance.r2sync.data.entity.ClientAppEntity
import com.dissonance.r2sync.data.entity.ConflictEntity
import com.dissonance.r2sync.data.entity.FileMetadataEntity
import com.dissonance.r2sync.data.entity.SyncHistoryEntity
import com.dissonance.r2sync.data.entity.SyncedFolderEntity

@Database(
    entities = [
        SyncedFolderEntity::class,
        FileMetadataEntity::class,
        SyncHistoryEntity::class,
        ConflictEntity::class,
        ClientAppEntity::class
    ],
    version = 4,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun syncedFolderDao(): SyncedFolderDao
    abstract fun fileMetadataDao(): FileMetadataDao
    abstract fun syncHistoryDao(): SyncHistoryDao
    abstract fun conflictDao(): ConflictDao
    abstract fun clientAppDao(): ClientAppDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // file_metadata: unique identity is now
                // (folderId, namespace, relativePath) so hub rows from
                // different namespaces no longer collide/REPLACE each other.
                db.execSQL("DROP INDEX IF EXISTS index_file_metadata_folderId_relativePath")
                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_file_metadata_folderId_namespace_relativePath
                    ON file_metadata(folderId, namespace, relativePath)
                """)
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // sync_history: both hot queries sort by timestamp; folder
                // queries filter by folderId. Rows are inserted per file op.
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_history_timestamp ON sync_history(timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_history_folderId ON sync_history(folderId)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            // Re-check inside the lock: without it, two threads (binder +
            // worker) can both build a Room instance against the same file,
            // splitting InvalidationTracker state.
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "r2_sync_database.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    // v1 predates exported schemas and cannot be migrated
                    // faithfully; only that (dev-era) starting point may
                    // destructively fall back. Everything from v2 on has a
                    // real migration path.
                    .fallbackToDestructiveMigrationFrom(1)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
