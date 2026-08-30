package com.dissonance.r2sync.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.dissonance.r2sync.model.ConflictStrategy
import com.dissonance.r2sync.model.SyncDirection

@Entity(tableName = "synced_folders")
data class SyncedFolderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val displayName: String,
    val localUri: String,
    val localPath: String,
    val remotePrefix: String,
    val syncDirection: SyncDirection = SyncDirection.TWO_WAY,
    val autoSyncEnabled: Boolean = true,
    val syncIntervalMinutes: Int = 15,
    val filterExtensions: String = "", // e.g. ".pdf,.jpg,.md,.png" or empty
    val excludeHidden: Boolean = true,
    val lastSyncTimestamp: Long = 0L,
    val fileCount: Int = 0,
    val totalSizeBytes: Long = 0L,
    val lastSyncStatus: String = "IDLE",
    val conflictStrategy: ConflictStrategy = ConflictStrategy.MANUAL_REVIEW
)
