package com.dissonance.r2sync.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dissonance.r2sync.data.entity.FileMetadataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FileMetadataDao {
    @Query("SELECT * FROM file_metadata WHERE folderId = :folderId ORDER BY relativePath ASC")
    fun getFilesForFolderFlow(folderId: Long): Flow<List<FileMetadataEntity>>

    @Query("SELECT * FROM file_metadata WHERE folderId = :folderId ORDER BY relativePath ASC")
    suspend fun getFilesForFolder(folderId: Long): List<FileMetadataEntity>

    @Query("SELECT * FROM file_metadata WHERE folderId = :folderId AND relativePath = :relativePath LIMIT 1")
    suspend fun getFileMetadata(folderId: Long, relativePath: String): FileMetadataEntity?

    @Query("SELECT * FROM file_metadata WHERE id = :id LIMIT 1")
    suspend fun getFileById(id: Long): FileMetadataEntity?

    @Query("SELECT * FROM file_metadata WHERE namespace = :namespace ORDER BY relativePath ASC")
    fun getFilesForNamespaceFlow(namespace: String): Flow<List<FileMetadataEntity>>

    @Query("SELECT * FROM file_metadata WHERE namespace = :namespace ORDER BY relativePath ASC")
    suspend fun getFilesForNamespace(namespace: String): List<FileMetadataEntity>

    @Query("SELECT * FROM file_metadata WHERE namespace = :namespace AND relativePath = :relativePath LIMIT 1")
    suspend fun getFileByNamespaceAndPath(namespace: String, relativePath: String): FileMetadataEntity?

    @Query("SELECT * FROM file_metadata WHERE isDirty = 1 ORDER BY dirtyTimestamp ASC")
    fun getDirtyFilesFlow(): Flow<List<FileMetadataEntity>>

    @Query("SELECT * FROM file_metadata WHERE isDirty = 1 ORDER BY dirtyTimestamp ASC")
    suspend fun getDirtyFiles(): List<FileMetadataEntity>

    @Query("SELECT * FROM file_metadata WHERE folderId = 0")
    suspend fun getHubFiles(): List<FileMetadataEntity>

    @Query("SELECT * FROM file_metadata WHERE state = 'DELETE_PENDING'")
    suspend fun getDeletePendingFiles(): List<FileMetadataEntity>

    @Query("UPDATE file_metadata SET isDirty = 0, dirtyTimestamp = 0, state = 'DELETE_PENDING' WHERE id = :id")
    suspend fun markFileDeletePending(id: Long)

    @Query("SELECT COUNT(*) FROM file_metadata WHERE isDirty = 1")
    fun getDirtyCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM file_metadata WHERE isDirty = 1")
    suspend fun getDirtyCount(): Int

    @Query("SELECT * FROM file_metadata ORDER BY lastChecked DESC")
    fun getAllFilesFlow(): Flow<List<FileMetadataEntity>>

    @Query("SELECT * FROM file_metadata ORDER BY lastChecked DESC")
    suspend fun getAllFiles(): List<FileMetadataEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(metadata: FileMetadataEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(metadataList: List<FileMetadataEntity>)

    @Query("UPDATE file_metadata SET isDirty = 1, state = 'DIRTY', dirtyTimestamp = :timestamp, localSizeBytes = :size, localLastModified = :timestamp, localHash = :hash WHERE id = :id")
    suspend fun markFileDirty(id: Long, timestamp: Long, size: Long, hash: String)

    @Query("UPDATE file_metadata SET isDirty = 0, state = 'SYNCED', remoteEtag = :etag, remoteLastModified = :remoteModified, remoteSizeBytes = localSizeBytes WHERE id = :id")
    suspend fun markFileClean(id: Long, etag: String, remoteModified: Long)

    @Query("DELETE FROM file_metadata WHERE folderId = :folderId AND relativePath = :relativePath")
    suspend fun deleteFileMetadata(folderId: Long, relativePath: String)

    @Query("DELETE FROM file_metadata WHERE namespace = :namespace AND relativePath = :relativePath")
    suspend fun deleteByNamespaceAndPath(namespace: String, relativePath: String)

    @Query("DELETE FROM file_metadata WHERE folderId = :folderId")
    suspend fun deleteAllForFolder(folderId: Long)

    @Query("DELETE FROM file_metadata")
    suspend fun deleteAllFiles()

    @Query("DELETE FROM file_metadata WHERE id = :id")
    suspend fun deleteById(id: Long)
}
