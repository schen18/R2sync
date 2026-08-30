package com.dissonance.r2sync.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.dissonance.r2sync.data.entity.ConflictEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConflictDao {
    @Query("SELECT * FROM conflicts WHERE status = 'PENDING' ORDER BY detectedAt DESC")
    fun getPendingConflictsFlow(): Flow<List<ConflictEntity>>

    @Query("SELECT * FROM conflicts WHERE status = 'PENDING' ORDER BY detectedAt DESC")
    suspend fun getPendingConflicts(): List<ConflictEntity>

    @Query("SELECT COUNT(*) FROM conflicts WHERE status = 'PENDING'")
    fun getPendingConflictCountFlow(): Flow<Int>

    @Query("SELECT * FROM conflicts WHERE id = :id")
    suspend fun getConflictById(id: Long): ConflictEntity?

    @Query("SELECT * FROM conflicts WHERE folderId = :folderId AND relativePath = :relativePath AND status = 'PENDING' LIMIT 1")
    suspend fun getPendingConflictForFile(folderId: Long, relativePath: String): ConflictEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConflict(conflict: ConflictEntity): Long

    @Update
    suspend fun updateConflict(conflict: ConflictEntity)

    @Delete
    suspend fun deleteConflict(conflict: ConflictEntity)

    @Query("UPDATE conflicts SET status = 'RESOLVED', resolutionChoice = :choice WHERE id = :id")
    suspend fun markResolved(id: Long, choice: String)

    @Query("DELETE FROM conflicts WHERE folderId = :folderId")
    suspend fun deleteAllForFolder(folderId: Long)

    @Query("DELETE FROM conflicts")
    suspend fun deleteAllConflicts()
}
