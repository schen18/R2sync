package com.dissonance.r2sync.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dissonance.r2sync.data.database.AppDatabase
import com.dissonance.r2sync.data.entity.ConflictEntity
import com.dissonance.r2sync.data.entity.FileMetadataEntity
import com.dissonance.r2sync.data.entity.SyncHistoryEntity
import com.dissonance.r2sync.data.entity.SyncedFolderEntity
import com.dissonance.r2sync.model.ConflictStrategy
import com.dissonance.r2sync.model.EnergySettings
import com.dissonance.r2sync.model.R2Config
import com.dissonance.r2sync.model.SyncDirection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SyncRepository(private val context: Context, private val database: AppDatabase) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("r2_sync_preferences", Context.MODE_PRIVATE)

    /**
     * The R2 secret access key is a full bucket credential: it lives in a
     * Keystore-encrypted prefs file (never the plaintext one, which is also
     * excluded from backups only as defense-in-depth).
     */
    private val securePrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "r2_secure_credentials",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Keystore unavailable (rare device corruption): fall back to the
            // plaintext prefs rather than hard-failing sync entirely.
            prefs
        }
    }

    private val _r2ConfigFlow = MutableStateFlow(loadR2Config())
    val r2ConfigFlow: Flow<R2Config> = _r2ConfigFlow.asStateFlow()

    private val _energySettingsFlow = MutableStateFlow(loadEnergySettings())
    val energySettingsFlow: Flow<EnergySettings> = _energySettingsFlow.asStateFlow()

    // Synced Folders
    val allFoldersFlow: Flow<List<SyncedFolderEntity>> = database.syncedFolderDao().getAllFoldersFlow()
    suspend fun getAllFolders(): List<SyncedFolderEntity> = database.syncedFolderDao().getAllFolders()
    suspend fun getFolderById(id: Long): SyncedFolderEntity? = database.syncedFolderDao().getFolderById(id)
    suspend fun insertFolder(folder: SyncedFolderEntity): Long = database.syncedFolderDao().insertFolder(folder)
    suspend fun updateFolder(folder: SyncedFolderEntity) = database.syncedFolderDao().updateFolder(folder)
    suspend fun deleteFolder(folder: SyncedFolderEntity) {
        // Atomic: partial deletion would leave metadata/conflict rows
        // ghosting through the dirty/delete-pending queues forever.
        database.withTransaction {
            database.fileMetadataDao().deleteAllForFolder(folder.id)
            database.conflictDao().deleteAllForFolder(folder.id)
            database.syncedFolderDao().deleteFolder(folder)
        }
    }
    suspend fun updateFolderStats(id: Long, timestamp: Long, status: String, fileCount: Int, totalSize: Long) {
        database.syncedFolderDao().updateSyncStats(id, timestamp, status, fileCount, totalSize)
    }
    suspend fun toggleAutoSync(id: Long, enabled: Boolean) {
        database.syncedFolderDao().toggleAutoSync(id, enabled)
    }

    // File Metadata
    fun getFilesForFolderFlow(folderId: Long): Flow<List<FileMetadataEntity>> =
        database.fileMetadataDao().getFilesForFolderFlow(folderId)
    suspend fun getFilesForFolder(folderId: Long): List<FileMetadataEntity> =
        database.fileMetadataDao().getFilesForFolder(folderId)
    suspend fun getFileMetadata(folderId: Long, relativePath: String): FileMetadataEntity? =
        database.fileMetadataDao().getFileMetadata(folderId, relativePath)
    suspend fun getFileById(id: Long): FileMetadataEntity? =
        database.fileMetadataDao().getFileById(id)
    fun getFilesForNamespaceFlow(namespace: String): Flow<List<FileMetadataEntity>> =
        database.fileMetadataDao().getFilesForNamespaceFlow(namespace)
    suspend fun getFilesForNamespace(namespace: String): List<FileMetadataEntity> =
        database.fileMetadataDao().getFilesForNamespace(namespace)
    suspend fun getFileByNamespaceAndPath(namespace: String, relativePath: String): FileMetadataEntity? =
        database.fileMetadataDao().getFileByNamespaceAndPath(namespace, relativePath)
    val dirtyFilesFlow: Flow<List<FileMetadataEntity>> =
        database.fileMetadataDao().getDirtyFilesFlow()
    suspend fun getDirtyFiles(): List<FileMetadataEntity> =
        database.fileMetadataDao().getDirtyFiles()
    suspend fun getHubFiles(): List<FileMetadataEntity> =
        database.fileMetadataDao().getHubFiles()
    suspend fun getDeletePendingFiles(): List<FileMetadataEntity> =
        database.fileMetadataDao().getDeletePendingFiles()
    suspend fun markFileDeletePending(id: Long) =
        database.fileMetadataDao().markFileDeletePending(id)
    val dirtyCountFlow: Flow<Int> =
        database.fileMetadataDao().getDirtyCountFlow()
    suspend fun saveFileMetadata(metadata: FileMetadataEntity): Long =
        database.fileMetadataDao().insertOrUpdate(metadata)
    suspend fun markFileDirty(id: Long, timestamp: Long, size: Long, hash: String) =
        database.fileMetadataDao().markFileDirty(id, timestamp, size, hash)
    suspend fun markFileClean(id: Long, etag: String, remoteModified: Long) =
        database.fileMetadataDao().markFileClean(id, etag, remoteModified)
    suspend fun deleteFileMetadata(folderId: Long, relativePath: String) =
        database.fileMetadataDao().deleteFileMetadata(folderId, relativePath)
    suspend fun deleteFileByNamespaceAndPath(namespace: String, relativePath: String) =
        database.fileMetadataDao().deleteByNamespaceAndPath(namespace, relativePath)
    suspend fun deleteFileById(id: Long) =
        database.fileMetadataDao().deleteById(id)

    // Client Apps
    val allClientsFlow: Flow<List<com.dissonance.r2sync.data.entity.ClientAppEntity>> =
        database.clientAppDao().getAllClientsFlow()
    suspend fun getAllClients(): List<com.dissonance.r2sync.data.entity.ClientAppEntity> =
        database.clientAppDao().getAllClients()
    suspend fun getClient(packageId: String): com.dissonance.r2sync.data.entity.ClientAppEntity? =
        database.clientAppDao().getClient(packageId)
    suspend fun upsertClient(client: com.dissonance.r2sync.data.entity.ClientAppEntity) =
        database.clientAppDao().insertOrUpdate(client)
    suspend fun updateClientStats(packageId: String, files: Int, bytes: Long, dirty: Int, lastSync: Long) =
        database.clientAppDao().updateStats(packageId, files, bytes, dirty, lastSync)

    // Sync History
    val allHistoryFlow: Flow<List<SyncHistoryEntity>> = database.syncHistoryDao().getAllHistoryFlow()
    fun getHistoryForFolderFlow(folderId: Long): Flow<List<SyncHistoryEntity>> =
        database.syncHistoryDao().getHistoryForFolderFlow(folderId)
    suspend fun insertHistoryLog(log: SyncHistoryEntity) {
        database.syncHistoryDao().insertLog(log)
        database.syncHistoryDao().pruneBeyond(1000)
    }
    suspend fun clearAllHistory() = database.syncHistoryDao().clearAllLogs()

    // Conflicts
    val pendingConflictsFlow: Flow<List<ConflictEntity>> = database.conflictDao().getPendingConflictsFlow()
    val pendingConflictCountFlow: Flow<Int> = database.conflictDao().getPendingConflictCountFlow()
    suspend fun getPendingConflicts(): List<ConflictEntity> = database.conflictDao().getPendingConflicts()
    suspend fun getPendingConflictForFile(folderId: Long, relativePath: String): ConflictEntity? =
        database.conflictDao().getPendingConflictForFile(folderId, relativePath)
    suspend fun insertConflict(conflict: ConflictEntity): Long = database.conflictDao().insertConflict(conflict)
    suspend fun markConflictResolved(id: Long, resolution: String) = database.conflictDao().markResolved(id, resolution)
    suspend fun deleteConflict(conflict: ConflictEntity) = database.conflictDao().deleteConflict(conflict)

    // Preferences & Config
    fun loadR2Config(): R2Config {
        // One-time migration: move a legacy plaintext secret into the
        // encrypted store and scrub the original.
        val legacySecret = prefs.getString("r2_secret_access_key", null)
        if (!legacySecret.isNullOrBlank() && securePrefs !== prefs) {
            securePrefs.edit().putString("r2_secret_access_key", legacySecret).apply()
            prefs.edit().remove("r2_secret_access_key").apply()
        }
        return R2Config(
            accountId = prefs.getString("r2_account_id", "") ?: "",
            accessKeyId = prefs.getString("r2_access_key_id", "") ?: "",
            secretAccessKey = securePrefs.getString("r2_secret_access_key", "") ?: "",
            bucketName = prefs.getString("r2_bucket_name", "") ?: "",
            customEndpoint = prefs.getString("r2_custom_endpoint", "") ?: "",
            region = prefs.getString("r2_region", "auto") ?: "auto"
        )
    }

    fun saveR2Config(config: R2Config) {
        prefs.edit().apply {
            putString("r2_account_id", config.accountId)
            putString("r2_access_key_id", config.accessKeyId)
            putString("r2_bucket_name", config.bucketName)
            putString("r2_custom_endpoint", config.customEndpoint)
            putString("r2_region", config.region)
            // Never (re)introduce the secret into the plaintext file, and drop
            // the removed sandbox-mode key if an old install still has it.
            remove("r2_secret_access_key")
            remove("r2_use_sandbox")
            apply()
        }
        if (config.secretAccessKey.isNotBlank()) {
            securePrefs.edit().putString("r2_secret_access_key", config.secretAccessKey).apply()
        } else if (securePrefs !== prefs) {
            securePrefs.edit().remove("r2_secret_access_key").apply()
        }
        _r2ConfigFlow.value = config
    }

    fun loadEnergySettings(): EnergySettings {
        val stratName = prefs.getString("energy_conflict_strategy", ConflictStrategy.MANUAL_REVIEW.name)
        val strat = try {
            ConflictStrategy.valueOf(stratName ?: ConflictStrategy.MANUAL_REVIEW.name)
        } catch (e: Exception) {
            ConflictStrategy.MANUAL_REVIEW
        }
        return EnergySettings(
            backgroundSyncEnabled = prefs.getBoolean("energy_background_sync", true),
            syncOnlyOnWifi = prefs.getBoolean("energy_sync_wifi_only", true),
            syncOnlyWhileCharging = prefs.getBoolean("energy_sync_charging_only", false),
            pauseOnLowBattery = prefs.getBoolean("energy_pause_low_battery", true),
            lowBatteryThreshold = prefs.getInt("energy_low_battery_thresh", 20),
            globalIntervalMinutes = prefs.getInt("energy_interval_mins", 15),
            globalConflictStrategy = strat
        )
    }

    fun saveEnergySettings(settings: EnergySettings) {
        prefs.edit().apply {
            putBoolean("energy_background_sync", settings.backgroundSyncEnabled)
            putBoolean("energy_sync_wifi_only", settings.syncOnlyOnWifi)
            putBoolean("energy_sync_charging_only", settings.syncOnlyWhileCharging)
            putBoolean("energy_pause_low_battery", settings.pauseOnLowBattery)
            putInt("energy_low_battery_thresh", settings.lowBatteryThreshold)
            putInt("energy_interval_mins", settings.globalIntervalMinutes)
            putString("energy_conflict_strategy", settings.globalConflictStrategy.name)
            apply()
        }
        _energySettingsFlow.value = settings
    }

    suspend fun flushAllDataAndSettings() {
        database.withTransaction {
            database.syncedFolderDao().deleteAllFolders()
            database.fileMetadataDao().deleteAllFiles()
            database.syncHistoryDao().clearAllLogs()
            database.conflictDao().deleteAllConflicts()
        }

        // Wipe the plaintext prefs AND the encrypted credential store.
        prefs.edit().clear().apply()
        if (securePrefs !== prefs) {
            try { securePrefs.edit().clear().apply() } catch (_: Exception) {}
        }
        _r2ConfigFlow.value = R2Config()
        _energySettingsFlow.value = EnergySettings()
    }
}
