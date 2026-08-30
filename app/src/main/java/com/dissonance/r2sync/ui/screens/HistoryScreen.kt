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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.dissonance.r2sync.data.entity.SyncHistoryEntity
import com.dissonance.r2sync.ui.HistoryFilter
import com.dissonance.r2sync.ui.components.HistoryItemRow
import com.dissonance.r2sync.ui.components.formatBytes
import com.dissonance.r2sync.ui.theme.CfOrangePrimary
import com.dissonance.r2sync.ui.theme.CrimsonRed
import com.dissonance.r2sync.ui.theme.EmeraldGreen
import com.dissonance.r2sync.ui.theme.SkyBlue

@Composable
fun HistoryScreen(
    historyLogs: List<SyncHistoryEntity>,
    activeFilter: HistoryFilter,
    onFilterSelect: (HistoryFilter) -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    val filteredLogs = historyLogs.filter { log ->
        val matchesFilter = when (activeFilter) {
            HistoryFilter.ALL -> true
            HistoryFilter.UPLOADS -> log.action == "UPLOAD"
            HistoryFilter.DOWNLOADS -> log.action == "DOWNLOAD"
            HistoryFilter.CONFLICTS -> log.action == "CONFLICT_RESOLVED" || log.status == "CONFLICT"
            HistoryFilter.ERRORS -> log.status == "FAILED" || log.status == "ERROR"
        }
        val matchesSearch = if (searchQuery.isBlank()) true else {
            log.relativePath.contains(searchQuery, ignoreCase = true) ||
                    log.folderName.contains(searchQuery, ignoreCase = true) ||
                    log.details.contains(searchQuery, ignoreCase = true)
        }
        matchesFilter && matchesSearch
    }

    val totalTransferredBytes = historyLogs.filter { it.status == "SUCCESS" }.sumOf { it.fileSizeBytes }
    val successRate = if (historyLogs.isNotEmpty()) {
        (historyLogs.count { it.status == "SUCCESS" } * 100 / historyLogs.size)
    } else 100

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        // Stats Card
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
                    Text(
                        text = "Sync Telemetry & History",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1
                    )
                    Text(
                        text = "${historyLogs.size} Events • Transferred ${formatBytes(totalTransferredBytes)} • ${successRate}% Success",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (historyLogs.isNotEmpty()) {
                    IconButton(
                        onClick = { showClearConfirmDialog = true },
                        modifier = Modifier.testTag("clear_history_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Clear logs",
                            tint = CrimsonRed
                        )
                    }
                }
            }
        }

        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("Filter logs by file name or action...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().testTag("history_search_input"),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        // Filter chips
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(HistoryFilter.values()) { filter ->
                FilterChip(
                    selected = activeFilter == filter,
                    onClick = { onFilterSelect(filter) },
                    label = { Text(filter.label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CfOrangePrimary.copy(alpha = 0.15f),
                        selectedLabelColor = CfOrangePrimary
                    )
                )
            }
        }

        // History Log items list
        if (filteredLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (searchQuery.isNotBlank() || activeFilter != HistoryFilter.ALL) "No matching history logs" else "No sync activity recorded",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "File transfers, delta updates, and conflict events will appear here.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredLogs, key = { it.id }) { log ->
                    HistoryItemRow(item = log)
                }
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("Clear History Logs?") },
            text = { Text("This will permanently clear all recorded transfer logs and conflict records from the local database.") },
            confirmButton = {
                Button(
                    onClick = {
                        onClearLogs()
                        showClearConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonRed)
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
