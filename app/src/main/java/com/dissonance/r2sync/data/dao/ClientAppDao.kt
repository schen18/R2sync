package com.dissonance.r2sync.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dissonance.r2sync.data.entity.ClientAppEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ClientAppDao {
    @Query("SELECT * FROM client_apps ORDER BY lastSyncTimestamp DESC")
    fun getAllClientsFlow(): Flow<List<ClientAppEntity>>

    @Query("SELECT * FROM client_apps ORDER BY lastSyncTimestamp DESC")
    suspend fun getAllClients(): List<ClientAppEntity>

    @Query("SELECT * FROM client_apps WHERE packageId = :packageId LIMIT 1")
    suspend fun getClient(packageId: String): ClientAppEntity?

    @Query("SELECT * FROM client_apps WHERE namespace = :namespace LIMIT 1")
    suspend fun getClientByNamespace(namespace: String): ClientAppEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(client: ClientAppEntity)

    @Query("UPDATE client_apps SET totalFiles = :files, totalBytes = :bytes, dirtyCount = :dirty, lastSyncTimestamp = :lastSync WHERE packageId = :packageId")
    suspend fun updateStats(packageId: String, files: Int, bytes: Long, dirty: Int, lastSync: Long)

    @Query("DELETE FROM client_apps WHERE packageId = :packageId")
    suspend fun deleteClient(packageId: String)
}
