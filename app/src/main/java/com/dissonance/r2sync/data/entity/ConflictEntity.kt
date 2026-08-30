package com.dissonance.r2sync.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conflicts")
data class ConflictEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val folderId: Long,
    val folderName: String,
    val relativePath: String,
    val localSizeBytes: Long,
    val localLastModified: Long,
    val localHash: String,
    val remoteSizeBytes: Long,
    val remoteLastModified: Long,
    val remoteEtag: String,
    val detectedAt: Long = System.currentTimeMillis(),
    val status: String = "PENDING", // PENDING, RESOLVED
    val resolutionChoice: String? = null
)
