package com.dissonance.r2sync.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "client_apps")
data class ClientAppEntity(
    @PrimaryKey
    val packageId: String,
    val appName: String,
    val namespace: String,
    val totalFiles: Int = 0,
    val totalBytes: Long = 0L,
    val dirtyCount: Int = 0,
    val lastSyncTimestamp: Long = System.currentTimeMillis(),
    val isAuthorized: Boolean = true,
    val firstConnectedTimestamp: Long = System.currentTimeMillis()
)
