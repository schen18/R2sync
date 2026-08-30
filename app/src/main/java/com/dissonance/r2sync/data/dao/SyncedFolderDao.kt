package com.dissonance.r2sync.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.dissonance.r2sync.data.entity.SyncedFolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncedFolderDao {
    @Query("SELECT * FROM synced_folders ORDER BY id ASC")
    fun getAllFoldersFlow(): Flow<List<SyncedFolderEntity>>

    @Query("SELECT * FROM synced_folders ORDER BY id ASC")
    suspend fun getAllFolders(): List<SyncedFolderEntity>

    @Query("SELECT * FROM synced_folders WHERE id = :id")
    suspend fun getFolderById(id: Long): SyncedFolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: SyncedFolderEntity): Long

    @Update
    suspend fun updateFolder(folder: SyncedFolderEntity)

    @Delete
    suspend fun deleteFolder(folder: SyncedFolderEntity)

    @Query("DELETE FROM synced_folders WHERE id = :id")
    suspend fun deleteFolderById(id: Long)

    @Query("DELETE FROM synced_folders")
    suspend fun deleteAllFolders()

    @Query("UPDATE synced_folders SET lastSyncTimestamp = :timestamp, lastSyncStatus = :status, fileCount = :fileCount, totalSizeBytes = :totalSize WHERE id = :id")
    suspend fun updateSyncStats(id: Long, timestamp: Long, status: String, fileCount: Int, totalSize: Long)

    @Query("UPDATE synced_folders SET autoSyncEnabled = :enabled WHERE id = :id")
    suspend fun toggleAutoSync(id: Long, enabled: Boolean)
}
