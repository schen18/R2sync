package com.dissonance.r2sync.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dissonance.r2sync.data.database.AppDatabase
import com.dissonance.r2sync.data.entity.ConflictEntity
import com.dissonance.r2sync.data.entity.FileMetadataEntity
import com.dissonance.r2sync.data.entity.SyncHistoryEntity
import com.dissonance.r2sync.data.entity.SyncedFolderEntity
import com.dissonance.r2sync.data.repository.SyncRepository
import com.dissonance.r2sync.energy.EnergyManager
import com.dissonance.r2sync.model.ConflictStrategy
import com.dissonance.r2sync.model.EnergySettings
import com.dissonance.r2sync.model.EnergyState
import com.dissonance.r2sync.model.R2Config
import com.dissonance.r2sync.model.SyncDirection
import com.dissonance.r2sync.model.SyncProgress
import com.dissonance.r2sync.r2.R2Client
import com.dissonance.r2sync.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppTab(val title: String) {
    DASHBOARD("Dashboard"),
    HUB_PROVIDER("Hub Provider"),
    FOLDERS("Folders"),
    CONFLICTS("Conflicts"),
    HISTORY("Logs"),
    SETTINGS("Settings")
}

enum class HistoryFilter(val label: String) {
    ALL("All Events"),
    UPLOADS("Uploads"),
    DOWNLOADS("Downloads"),
    CONFLICTS("Conflicts"),
    ERRORS("Errors")
}

sealed class ConnectionStatus {
    object Idle : ConnectionStatus()
    object Testing : ConnectionStatus()
    data class Success(val latencyMs: Long) : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
}

class SyncViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    val repository = SyncRepository(application, database)
    val energyManager = EnergyManager(application)
    val syncEngine = SyncEngine(application, repository, energyManager, viewModelScope)
    val vaultManager = com.dissonance.r2sync.provider.vault.HubVaultManager(application)

    val folders: StateFlow<List<SyncedFolderEntity>> = repository.allFoldersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val historyLogs: StateFlow<List<SyncHistoryEntity>> = repository.allHistoryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingConflicts: StateFlow<List<ConflictEntity>> = repository.pendingConflictsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingConflictCount: StateFlow<Int> = repository.pendingConflictCountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val r2Config: StateFlow<R2Config> = repository.r2ConfigFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), repository.loadR2Config())

    val energySettings: StateFlow<EnergySettings> = repository.energySettingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), repository.loadEnergySettings())

    val energyState: StateFlow<EnergyState> = energyManager.energyState

    val syncProgress: StateFlow<SyncProgress> = syncEngine.syncProgress

    // Hub Provider StateFlows
    val dirtyFiles: StateFlow<List<FileMetadataEntity>> = repository.dirtyFilesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dirtyCount: StateFlow<Int> = repository.dirtyCountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val clientApps: StateFlow<List<com.dissonance.r2sync.data.entity.ClientAppEntity>> = repository.allClientsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _hubVaultSizeBytes = MutableStateFlow(0L)
    val hubVaultSizeBytes: StateFlow<Long> = _hubVaultSizeBytes.asStateFlow()

    private val _hubVaultFileCount = MutableStateFlow(0)
    val hubVaultFileCount: StateFlow<Int> = _hubVaultFileCount.asStateFlow()

    init {
        refreshHubVaultStats()
        viewModelScope.launch(Dispatchers.IO) {
            val sharedPrefs = getApplication<Application>().getSharedPreferences("r2_sync_preferences", android.content.Context.MODE_PRIVATE)
            val alreadyFlushed = sharedPrefs.getBoolean("has_flushed_dummy_v1", false)
            if (!alreadyFlushed) {
                repository.flushAllDataAndSettings()
                vaultManager.clearAllVaultData()
                sharedPrefs.edit().putBoolean("has_flushed_dummy_v1", true).apply()
                refreshHubVaultStats()
            }
        }
    }

    fun refreshHubVaultStats() {
        viewModelScope.launch(Dispatchers.IO) {
            _hubVaultSizeBytes.value = vaultManager.getVaultTotalSizeBytes()
            _hubVaultFileCount.value = vaultManager.getVaultTotalFileCount()
        }
    }

    fun triggerWorkManagerSync() {
        com.dissonance.r2sync.work.R2SyncWorkScheduler.scheduleImmediateDirtySync(getApplication())
    }

    // UI state for navigation and dialogs
    private val _currentTab = MutableStateFlow(AppTab.DASHBOARD)
    val currentTab: StateFlow<AppTab> = _currentTab.asStateFlow()

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _selectedFolderForEdit = MutableStateFlow<SyncedFolderEntity?>(null)
    val selectedFolderForEdit: StateFlow<SyncedFolderEntity?> = _selectedFolderForEdit.asStateFlow()

    private val _isAddFolderDialogOpen = MutableStateFlow(false)
    val isAddFolderDialogOpen: StateFlow<Boolean> = _isAddFolderDialogOpen.asStateFlow()

    private val _selectedFolderForExplorer = MutableStateFlow<SyncedFolderEntity?>(null)
    val selectedFolderForExplorer: StateFlow<SyncedFolderEntity?> = _selectedFolderForExplorer.asStateFlow()

    private val _explorerFiles = MutableStateFlow<List<FileMetadataEntity>>(emptyList())
    val explorerFiles: StateFlow<List<FileMetadataEntity>> = _explorerFiles.asStateFlow()

    private val _historyFilter = MutableStateFlow(HistoryFilter.ALL)
    val historyFilter: StateFlow<HistoryFilter> = _historyFilter.asStateFlow()

    private val _historySearchQuery = MutableStateFlow("")
    val historySearchQuery: StateFlow<String> = _historySearchQuery.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            energyManager.updateSettings(repository.loadEnergySettings())
        }
    }

    fun setTab(tab: AppTab) {
        _currentTab.value = tab
    }

    fun setHistoryFilter(filter: HistoryFilter) {
        _historyFilter.value = filter
    }

    fun setHistorySearchQuery(query: String) {
        _historySearchQuery.value = query
    }

    fun openAddFolderDialog() {
        _selectedFolderForEdit.value = null
        _isAddFolderDialogOpen.value = true
    }

    fun openEditFolderDialog(folder: SyncedFolderEntity) {
        _selectedFolderForEdit.value = folder
        _isAddFolderDialogOpen.value = true
    }

    fun closeAddEditFolderDialog() {
        _selectedFolderForEdit.value = null
        _isAddFolderDialogOpen.value = false
    }

    fun openFolderExplorer(folder: SyncedFolderEntity) {
        _selectedFolderForExplorer.value = folder
        viewModelScope.launch(Dispatchers.IO) {
            val files = repository.getFilesForFolder(folder.id)
            _explorerFiles.value = files
        }
    }

    fun closeFolderExplorer() {
        _selectedFolderForExplorer.value = null
        _explorerFiles.value = emptyList()
    }

    fun saveFolder(
        id: Long,
        displayName: String,
        localUri: String,
        localPath: String,
        remotePrefix: String,
        syncDirection: SyncDirection,
        autoSyncEnabled: Boolean,
        syncIntervalMinutes: Int,
        filterExtensions: String,
        excludeHidden: Boolean,
        conflictStrategy: ConflictStrategy
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val cleanPrefix = if (remotePrefix.isNotBlank()) {
                remotePrefix.trim().trimStart('/').let { if (it.endsWith('/')) it else "$it/" }
            } else {
                "devices/android/${displayName.lowercase().replace(" ", "_")}/"
            }

            if (id == 0L) {
                val newFolder = SyncedFolderEntity(
                    displayName = displayName,
                    localUri = localUri,
                    localPath = localPath,
                    remotePrefix = cleanPrefix,
                    syncDirection = syncDirection,
                    autoSyncEnabled = autoSyncEnabled,
                    syncIntervalMinutes = syncIntervalMinutes,
                    filterExtensions = filterExtensions,
                    excludeHidden = excludeHidden,
                    conflictStrategy = conflictStrategy,
                    lastSyncStatus = "READY"
                )
                repository.insertFolder(newFolder)
            } else {
                val existing = repository.getFolderById(id)
                if (existing != null) {
                    val updated = existing.copy(
                        displayName = displayName,
                        localUri = localUri,
                        localPath = localPath,
                        remotePrefix = cleanPrefix,
                        syncDirection = syncDirection,
                        autoSyncEnabled = autoSyncEnabled,
                        syncIntervalMinutes = syncIntervalMinutes,
                        filterExtensions = filterExtensions,
                        excludeHidden = excludeHidden,
                        conflictStrategy = conflictStrategy
                    )
                    repository.updateFolder(updated)
                }
            }
            closeAddEditFolderDialog()
        }
    }

    fun deleteFolder(folder: SyncedFolderEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteFolder(folder)
        }
    }

    fun toggleFolderAutoSync(folderId: Long, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.toggleAutoSync(folderId, enabled)
        }
    }

    fun syncAll() {
        syncEngine.syncAllFolders()
    }

    fun syncSingleFolder(folderId: Long) {
        syncEngine.syncFolder(folderId)
    }

    fun resolveConflict(conflict: ConflictEntity, strategy: ConflictStrategy) {
        viewModelScope.launch(Dispatchers.IO) {
            syncEngine.resolveConflictExplicitly(conflict, strategy)
        }
    }

    fun resolveAllConflictsWithStrategy(strategy: ConflictStrategy) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = repository.getPendingConflicts()
            for (c in list) {
                syncEngine.resolveConflictExplicitly(c, strategy)
            }
        }
    }

    fun saveR2Config(config: R2Config) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveR2Config(config)
            testR2Connection(config)
        }
    }

    fun testR2Connection(config: R2Config = r2Config.value) {
        viewModelScope.launch(Dispatchers.IO) {
            _connectionStatus.value = ConnectionStatus.Testing
            val client = R2Client(config)
            val res = client.testConnection()
            if (res.isSuccess) {
                val pair = res.getOrNull()!!
                _connectionStatus.value = ConnectionStatus.Success(pair.second)
            } else {
                _connectionStatus.value = ConnectionStatus.Error(res.exceptionOrNull()?.message ?: "Unknown error")
            }
        }
    }

    fun saveEnergySettings(settings: EnergySettings) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveEnergySettings(settings)
            energyManager.updateSettings(settings)
            // Background work is WorkManager-driven now: apply the settings to
            // the real periodic jobs (UPDATE policy swaps interval/constraints)
            // or cancel folder sync entirely when disabled.
            val app = getApplication<android.app.Application>()
            if (settings.backgroundSyncEnabled) {
                com.dissonance.r2sync.work.R2SyncWorkScheduler.scheduleFolderSync(
                    app,
                    settings.globalIntervalMinutes.toLong(),
                    settings.syncOnlyOnWifi,
                    settings.syncOnlyWhileCharging,
                    settings.pauseOnLowBattery
                )
                com.dissonance.r2sync.work.R2SyncWorkScheduler.schedulePeriodicSync(app)
            } else {
                com.dissonance.r2sync.work.R2SyncWorkScheduler.cancelFolderSync(app)
            }
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearAllHistory()
        }
    }

    fun flushAllDataAndSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.flushAllDataAndSettings()
            vaultManager.clearAllVaultData()
            val sharedPrefs = getApplication<Application>().getSharedPreferences("r2_sync_preferences", android.content.Context.MODE_PRIVATE)
            sharedPrefs.edit().putBoolean("has_flushed_dummy_v1", true).apply()
            _connectionStatus.value = ConnectionStatus.Idle
            refreshHubVaultStats()
        }
    }

    override fun onCleared() {
        super.onCleared()
        energyManager.cleanUp()
    }
}
