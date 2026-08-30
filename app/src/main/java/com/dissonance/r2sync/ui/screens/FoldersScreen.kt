package com.dissonance.r2sync.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dissonance.r2sync.data.entity.SyncedFolderEntity
import com.dissonance.r2sync.ui.components.FolderCard
import com.dissonance.r2sync.ui.components.formatBytes
import com.dissonance.r2sync.ui.theme.CfOrangePrimary

@Composable
fun FoldersScreen(
    folders: List<SyncedFolderEntity>,
    onAddFolderClick: () -> Unit,
    onSyncFolder: (Long) -> Unit,
    onBrowseFolder: (SyncedFolderEntity) -> Unit,
    onEditFolder: (SyncedFolderEntity) -> Unit,
    onDeleteFolder: (SyncedFolderEntity) -> Unit,
    onToggleAutoSync: (Long, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredFolders = folders.filter {
        it.displayName.contains(searchQuery, ignoreCase = true) ||
                it.localPath.contains(searchQuery, ignoreCase = true) ||
                it.remotePrefix.contains(searchQuery, ignoreCase = true)
    }

    val totalFiles = folders.sumOf { it.fileCount }
    val totalBytes = folders.sumOf { it.totalSizeBytes }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                // Summary bar
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Active Sync Pairings", fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                            Text("Cross-platform folder mappings", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Surface(
                            color = CfOrangePrimary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "${folders.size} Folders • $totalFiles Files",
                                color = CfOrangePrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search folders or cloud paths...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    modifier = Modifier.fillMaxWidth().testTag("folder_search_bar"),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            }

            if (filteredFolders.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (searchQuery.isNotBlank()) "No matching folders found" else "No synced folders yet",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Pair your Documents, Notes, or Camera folders with Cloudflare R2 bucket storage.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = onAddFolderClick,
                                colors = ButtonDefaults.buttonColors(containerColor = CfOrangePrimary),
                                modifier = Modifier.testTag("add_folder_empty_state_btn")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Add Device Folder")
                            }
                        }
                    }
                }
            } else {
                items(filteredFolders, key = { it.id }) { folder ->
                    FolderCard(
                        folder = folder,
                        onSyncClick = { onSyncFolder(folder.id) },
                        onBrowseClick = { onBrowseFolder(folder) },
                        onEditClick = { onEditFolder(folder) },
                        onDeleteClick = { onDeleteFolder(folder) },
                        onToggleAutoSync = { enabled -> onToggleAutoSync(folder.id, enabled) }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(80.dp)) // Extra padding for FAB
            }
        }

        FloatingActionButton(
            onClick = onAddFolderClick,
            containerColor = CfOrangePrimary,
            contentColor = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
                .testTag("add_folder_fab")
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Synced Folder")
        }
    }
}
