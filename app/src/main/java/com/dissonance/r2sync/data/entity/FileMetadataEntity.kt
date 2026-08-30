package com.dissonance.r2sync.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "file_metadata",
    indices = [
        // Unique per (folderId, namespace, relativePath): hub rows all share
        // folderId = 0 but differ by namespace, folder rows differ by folderId.
        // The previous unique (folderId, relativePath) let one namespace's row
        // REPLACE-delete another namespace's row for the same path.
        Index(value = ["folderId", "namespace", "relativePath"], unique = true),
        Index(value = ["namespace", "relativePath"]),
        Index(value = ["isDirty"])
    ]
)
data class FileMetadataEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val folderId: Long = 0L, // 0 for Hub Vault files not bound to a device folder
    val relativePath: String,
    val namespace: String = "default",
    val clientPackage: String = "",
    val mimeType: String = "application/octet-stream",
    val localSizeBytes: Long = 0L,
    val localLastModified: Long = 0L,
    val localHash: String = "",
    val remoteSizeBytes: Long = 0L,
    val remoteLastModified: Long = 0L,
    val remoteEtag: String = "",
    val state: String = "SYNCED", // SYNCED, DIRTY, LOCAL_MODIFIED, REMOTE_MODIFIED, CONFLICT
    val isDirty: Boolean = false,
    val dirtyTimestamp: Long = 0L,
    val cachedLocalPath: String = "",
    val lastChecked: Long = System.currentTimeMillis()
)
