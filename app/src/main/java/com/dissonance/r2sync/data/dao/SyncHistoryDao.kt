package com.dissonance.r2sync.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dissonance.r2sync.data.entity.SyncHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncHistoryDao {
    @Query("SELECT * FROM sync_history ORDER BY timestamp DESC LIMIT 300")
    fun getAllHistoryFlow(): Flow<List<SyncHistoryEntity>>

    @Query("SELECT * FROM sync_history WHERE folderId = :folderId ORDER BY timestamp DESC LIMIT 100")
    fun getHistoryForFolderFlow(folderId: Long): Flow<List<SyncHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: SyncHistoryEntity)

    @Query("DELETE FROM sync_history WHERE id NOT IN (SELECT id FROM sync_history ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun pruneBeyond(keep: Int)

    @Query("DELETE FROM sync_history")
    suspend fun clearAllLogs()

    @Query("DELETE FROM sync_history WHERE folderId = :folderId")
    suspend fun clearLogsForFolder(folderId: Long)
}
