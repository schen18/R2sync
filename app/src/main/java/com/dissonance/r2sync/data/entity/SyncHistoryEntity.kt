package com.dissonance.r2sync.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_history",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["folderId"])
    ]
)
data class SyncHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val folderId: Long,
    val folderName: String,
    val relativePath: String,
    val action: String, // UPLOAD, DOWNLOAD, DELETE, CONFLICT_RESOLVED, ERROR
    val fileSizeBytes: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "SUCCESS", // SUCCESS, FAILED, CONFLICT
    val details: String = "",
    val durationMs: Long = 0L
)
