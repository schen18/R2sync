package com.dissonance.r2sync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dissonance.r2sync.data.entity.ConflictEntity
import com.dissonance.r2sync.data.entity.SyncHistoryEntity
import com.dissonance.r2sync.data.entity.SyncedFolderEntity
import com.dissonance.r2sync.model.ConflictStrategy
import com.dissonance.r2sync.model.EnergyState
import com.dissonance.r2sync.model.SyncProgress
import com.dissonance.r2sync.ui.AppTab
import com.dissonance.r2sync.ui.components.EnergyEfficiencyWidget
import com.dissonance.r2sync.ui.components.FolderCard
import com.dissonance.r2sync.ui.components.HistoryItemRow
import com.dissonance.r2sync.ui.components.SyncStatusHeroCard
import com.dissonance.r2sync.ui.components.formatBytes
import com.dissonance.r2sync.ui.components.formatRelativeTime
import com.dissonance.r2sync.ui.theme.CfAmber
import com.dissonance.r2sync.ui.theme.CfOrangePrimary
import com.dissonance.r2sync.ui.theme.CrimsonRed
import com.dissonance.r2sync.ui.theme.EmeraldGreen
import com.dissonance.r2sync.ui.theme.SkyBlue

@Composable
fun DashboardScreen(
    folders: List<SyncedFolderEntity>,
    historyLogs: List<SyncHistoryEntity>,
    pendingConflicts: List<ConflictEntity>,
    energyState: EnergyState,
    syncProgress: SyncProgress,
    onSyncAll: () -> Unit,
    onSyncFolder: (Long) -> Unit,
    onToggleAutoSync: (Long, Boolean) -> Unit,
    onNavigateTab: (AppTab) -> Unit,
    onAddFolderClick: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            SyncStatusHeroCard(
                progress = syncProgress,
                onSyncAllClick = onSyncAll
            )
        }

        // Pending Conflicts Alert Banner
        if (pendingConflicts.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, CfAmber.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                        .clickable { onNavigateTab(AppTab.CONFLICTS) },
                    colors = CardDefaults.cardColors(containerColor = CfAmber.copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(CfAmber),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SyncProblem,
                                    contentDescription = "Conflicts",
                                    tint = Color.Black,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "${pendingConflicts.size} File Conflict${if (pendingConflicts.size > 1) "s" else ""} Detected",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Tap to review differences & choose file versions",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Resolve",
                            tint = CfAmber,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // Energy & Network Policy Widget
        item {
            EnergyEfficiencyWidget(
                energyState = energyState,
                onConfigureClick = onOpenSettings
            )
        }

        // Hub Provider Quick Access Banner
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onNavigateTab(AppTab.HUB_PROVIDER) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(CfOrangePrimary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                tint = CfOrangePrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Hub Sync Provider & IPC",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "SAF DocumentsProvider & companion app sync",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Open Hub",
                        tint = CfOrangePrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Synced Folders Section Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 4.dp)
                ) {
                    Text(
                        text = "Synced Folders",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = CfOrangePrimary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "${folders.size}",
                            color = CfOrangePrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onAddFolderClick,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("dashboard_add_folder_btn")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Add", fontSize = 13.sp, maxLines = 1, softWrap = false)
                    }

                    TextButton(
                        onClick = { onNavigateTab(AppTab.FOLDERS) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("View All", fontSize = 13.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        // Synced Folders Cards (Preview first 2)
        if (folders.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No folders configured yet", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Add device folders to start synchronizing with Cloudflare R2",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onAddFolderClick,
                            colors = ButtonDefaults.buttonColors(containerColor = CfOrangePrimary)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add Device Folder")
                        }
                    }
                }
            }
        } else {
            items(folders.take(2)) { folder ->
                FolderCard(
                    folder = folder,
                    onSyncClick = { onSyncFolder(folder.id) },
                    onBrowseClick = { onNavigateTab(AppTab.FOLDERS) },
                    onEditClick = { onNavigateTab(AppTab.FOLDERS) },
                    onDeleteClick = { onNavigateTab(AppTab.FOLDERS) },
                    onToggleAutoSync = { enabled -> onToggleAutoSync(folder.id, enabled) }
                )
            }
        }

        // Recent Activity Feed Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Activity",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )

                TextButton(
                    onClick = { onNavigateTab(AppTab.HISTORY) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("View All", fontSize = 13.sp, maxLines = 1, softWrap = false)
                }
            }
        }

        // Recent History Items (last 4)
        if (historyLogs.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ) {
                    Text(
                        text = "No sync activity recorded yet.",
                        modifier = Modifier.padding(16.dp),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(historyLogs.take(4)) { log ->
                HistoryItemRow(item = log)
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
