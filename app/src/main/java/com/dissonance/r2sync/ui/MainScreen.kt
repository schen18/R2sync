package com.dissonance.r2sync.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dissonance.r2sync.model.ConflictStrategy
import com.dissonance.r2sync.model.SyncEngineStatus
import com.dissonance.r2sync.ui.components.AddEditFolderDialog
import com.dissonance.r2sync.ui.components.FolderExplorerDialog
import com.dissonance.r2sync.ui.components.R2ConfigDialog
import com.dissonance.r2sync.ui.screens.ConflictsScreen
import com.dissonance.r2sync.ui.screens.DashboardScreen
import com.dissonance.r2sync.ui.screens.FoldersScreen
import com.dissonance.r2sync.ui.screens.HistoryScreen
import com.dissonance.r2sync.ui.screens.HubProviderScreen
import com.dissonance.r2sync.ui.screens.SettingsScreen
import com.dissonance.r2sync.ui.theme.CfAmber
import com.dissonance.r2sync.ui.theme.CfOrangePrimary
import com.dissonance.r2sync.ui.theme.EmeraldGreen
import com.dissonance.r2sync.ui.theme.SkyBlue

data class NavItem(
    val tab: AppTab,
    val icon: ImageVector,
    val label: String,
    val testTag: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: SyncViewModel,
    modifier: Modifier = Modifier
) {
    val currentTab by viewModel.currentTab.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val historyLogs by viewModel.historyLogs.collectAsState()
    val pendingConflicts by viewModel.pendingConflicts.collectAsState()
    val conflictCount by viewModel.pendingConflictCount.collectAsState()
    val dirtyCount by viewModel.dirtyCount.collectAsState()
    val r2Config by viewModel.r2Config.collectAsState()
    val energySettings by viewModel.energySettings.collectAsState()
    val energyState by viewModel.energyState.collectAsState()
    val syncProgress by viewModel.syncProgress.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val isAddFolderDialogOpen by viewModel.isAddFolderDialogOpen.collectAsState()
    val selectedFolderForEdit by viewModel.selectedFolderForEdit.collectAsState()
    val selectedFolderForExplorer by viewModel.selectedFolderForExplorer.collectAsState()
    val explorerFiles by viewModel.explorerFiles.collectAsState()
    val historyFilter by viewModel.historyFilter.collectAsState()
    val historySearchQuery by viewModel.historySearchQuery.collectAsState()

    var isR2ConfigDialogOpen by remember { mutableStateOf(false) }

    val navItems = listOf(
        NavItem(AppTab.DASHBOARD, Icons.Default.Dashboard, "Dashboard", "nav_tab_dashboard"),
        NavItem(AppTab.HUB_PROVIDER, Icons.Default.Share, "Hub", "nav_tab_hub"),
        NavItem(AppTab.FOLDERS, Icons.Default.Folder, "Folders", "nav_tab_folders"),
        NavItem(AppTab.CONFLICTS, Icons.Default.SyncProblem, "Conflicts", "nav_tab_conflicts"),
        NavItem(AppTab.HISTORY, Icons.Default.History, "Logs", "nav_tab_history"),
        NavItem(AppTab.SETTINGS, Icons.Default.Settings, "Settings", "nav_tab_settings")
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(CfOrangePrimary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cloud,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "R2 Folder Sync",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = if (r2Config.bucketName.isNotBlank()) "R2: ${r2Config.bucketName}" else "R2 Not Configured",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                    }
                },
                actions = {
                    // Fast R2 Status indicator chip — reflects real state:
                    // unconfigured or live credentials.
                    val chipColor = when {
                        !r2Config.isConfigured -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> EmeraldGreen
                    }
                    val chipLabel = when {
                        !r2Config.isConfigured -> "Not Configured"
                        else -> "Live R2"
                    }
                    Surface(
                        color = chipColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable { isR2ConfigDialogOpen = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(chipColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = chipLabel,
                                color = chipColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    IconButton(
                        onClick = { viewModel.syncAll() },
                        modifier = Modifier.testTag("top_bar_sync_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = "Sync All",
                            tint = CfOrangePrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                navItems.forEach { item ->
                    val isSelected = currentTab == item.tab
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { viewModel.setTab(item.tab) },
                        alwaysShowLabel = false,
                        icon = {
                            if (item.tab == AppTab.CONFLICTS && conflictCount > 0) {
                                BadgedBox(
                                    badge = {
                                        Badge(containerColor = CfAmber) {
                                            Text(
                                                text = "$conflictCount",
                                                color = Color.Black,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                ) {
                                    Icon(item.icon, contentDescription = item.label)
                                }
                            } else if (item.tab == AppTab.HUB_PROVIDER && dirtyCount > 0) {
                                BadgedBox(
                                    badge = {
                                        Badge(containerColor = CfOrangePrimary) {
                                            Text(
                                                text = "$dirtyCount",
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                ) {
                                    Icon(item.icon, contentDescription = item.label)
                                }
                            } else {
                                Icon(item.icon, contentDescription = item.label)
                            }
                        },
                        label = {
                            Text(
                                text = item.label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CfOrangePrimary,
                            selectedTextColor = CfOrangePrimary,
                            indicatorColor = CfOrangePrimary.copy(alpha = 0.15f)
                        ),
                        modifier = Modifier.testTag(item.testTag)
                    )
                }
            }
        },
        modifier = modifier
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = {
                    (fadeIn(animationSpec = androidx.compose.animation.core.tween(200)))
                        .togetherWith(fadeOut(animationSpec = androidx.compose.animation.core.tween(200)))
                },
                label = "tab_transition"
            ) { tab ->
                when (tab) {
                    AppTab.DASHBOARD -> DashboardScreen(
                        folders = folders,
                        historyLogs = historyLogs,
                        pendingConflicts = pendingConflicts,
                        energyState = energyState,
                        syncProgress = syncProgress,
                        onSyncAll = { viewModel.syncAll() },
                        onSyncFolder = { folderId -> viewModel.syncSingleFolder(folderId) },
                        onToggleAutoSync = { folderId, enabled -> viewModel.toggleFolderAutoSync(folderId, enabled) },
                        onNavigateTab = { targetTab -> viewModel.setTab(targetTab) },
                        onAddFolderClick = { viewModel.openAddFolderDialog() },
                        onOpenSettings = { viewModel.setTab(AppTab.SETTINGS) }
                    )

                    AppTab.HUB_PROVIDER -> HubProviderScreen(
                        viewModel = viewModel
                    )

                    AppTab.FOLDERS -> FoldersScreen(
                        folders = folders,
                        onAddFolderClick = { viewModel.openAddFolderDialog() },
                        onSyncFolder = { folderId -> viewModel.syncSingleFolder(folderId) },
                        onBrowseFolder = { folder -> viewModel.openFolderExplorer(folder) },
                        onEditFolder = { folder -> viewModel.openEditFolderDialog(folder) },
                        onDeleteFolder = { folder -> viewModel.deleteFolder(folder) },
                        onToggleAutoSync = { folderId, enabled -> viewModel.toggleFolderAutoSync(folderId, enabled) }
                    )

                    AppTab.CONFLICTS -> ConflictsScreen(
                        conflicts = pendingConflicts,
                        onResolveConflict = { conflict, strategy -> viewModel.resolveConflict(conflict, strategy) },
                        onBatchResolve = { strategy -> viewModel.resolveAllConflictsWithStrategy(strategy) }
                    )

                    AppTab.HISTORY -> HistoryScreen(
                        historyLogs = historyLogs,
                        activeFilter = historyFilter,
                        onFilterSelect = { filter -> viewModel.setHistoryFilter(filter) },
                        searchQuery = historySearchQuery,
                        onSearchChange = { query -> viewModel.setHistorySearchQuery(query) },
                        onClearLogs = { viewModel.clearAllHistory() }
                    )

                    AppTab.SETTINGS -> SettingsScreen(
                        r2Config = r2Config,
                        energySettings = energySettings,
                        connectionStatus = connectionStatus,
                        onOpenR2ConfigDialog = { isR2ConfigDialogOpen = true },
                        onSaveEnergySettings = { newSettings -> viewModel.saveEnergySettings(newSettings) },
                        onFlushAllData = { viewModel.flushAllDataAndSettings() }
                    )
                }
            }
        }
    }

    // Modal Dialogs
    if (isAddFolderDialogOpen) {
        AddEditFolderDialog(
            folder = selectedFolderForEdit,
            onDismiss = { viewModel.closeAddEditFolderDialog() },
            onSave = { id, displayName, localUri, localPath, remotePrefix, syncDirection, autoSyncEnabled, syncIntervalMinutes, filterExtensions, excludeHidden, conflictStrategy ->
                viewModel.saveFolder(
                    id = id,
                    displayName = displayName,
                    localUri = localUri,
                    localPath = localPath,
                    remotePrefix = remotePrefix,
                    syncDirection = syncDirection,
                    autoSyncEnabled = autoSyncEnabled,
                    syncIntervalMinutes = syncIntervalMinutes,
                    filterExtensions = filterExtensions,
                    excludeHidden = excludeHidden,
                    conflictStrategy = conflictStrategy
                )
            }
        )
    }

    selectedFolderForExplorer?.let { folder ->
        FolderExplorerDialog(
            folder = folder,
            files = explorerFiles,
            onDismiss = { viewModel.closeFolderExplorer() }
        )
    }

    if (isR2ConfigDialogOpen) {
        R2ConfigDialog(
            config = r2Config,
            connectionStatus = connectionStatus,
            onDismiss = { isR2ConfigDialogOpen = false },
            onSave = { newConfig ->
                viewModel.saveR2Config(newConfig)
                isR2ConfigDialogOpen = false
            },
            onTestConnection = { configToTest ->
                viewModel.testR2Connection(configToTest)
            }
        )
    }
}
