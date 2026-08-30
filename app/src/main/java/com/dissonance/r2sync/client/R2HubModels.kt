package com.dissonance.r2sync.client

import android.net.Uri

data class R2HubFileEntry(
    val id: Long,
    val namespace: String,
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val hash: String,
    val state: String,
    val isDirty: Boolean,
    val dirtyTimestamp: Long,
    val clientPackage: String,
    val remoteEtag: String
) {
    val fileName: String
        get() = relativePath.substringAfterLast('/')
}

data class R2HubSyncStatus(
    val status: String,
    val dirtyCount: Int,
    val totalFiles: Int,
    val totalBytes: Long,
    val isCharging: Boolean,
    val isWifi: Boolean,
    val batteryPct: Int,
    val lastSyncTime: Long,
    val bucketName: String
)

data class R2HubClientApp(
    val packageId: String,
    val appName: String,
    val namespace: String,
    val totalFiles: Int,
    val totalBytes: Long,
    val dirtyCount: Int,
    val lastSync: Long,
    val isAuthorized: Boolean
)
