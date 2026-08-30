package com.dissonance.r2sync.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dissonance.r2sync.model.ConflictStrategy
import com.dissonance.r2sync.model.EnergySettings
import com.dissonance.r2sync.model.R2Config
import com.dissonance.r2sync.ui.ConnectionStatus
import com.dissonance.r2sync.ui.components.formatBytes
import com.dissonance.r2sync.ui.theme.CfOrangePrimary
import com.dissonance.r2sync.ui.theme.EmeraldGreen
import com.dissonance.r2sync.ui.theme.SkyBlue

@Composable
fun SettingsScreen(
    r2Config: R2Config,
    energySettings: EnergySettings,
    connectionStatus: ConnectionStatus,
    onOpenR2ConfigDialog: () -> Unit,
    onSaveEnergySettings: (EnergySettings) -> Unit,
    onFlushAllData: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var settings by remember(energySettings) { mutableStateOf(energySettings) }
    var isIntervalMenuOpen by remember { mutableStateOf(false) }
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        // 1. Cloudflare R2 Connection Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(CfOrangePrimary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cloud,
                                contentDescription = null,
                                tint = CfOrangePrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Cloudflare R2 Storage", fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                            Text(
                                text = if (r2Config.isConfigured) "Bucket: ${r2Config.bucketName}" else "Not Configured",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onOpenR2ConfigDialog,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("configure_r2_btn")
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Configure", fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                }

                if (r2Config.isConfigured) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Endpoint: ${r2Config.endpointUrl}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text("Account: ${r2Config.accountId.take(8)}***", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        // 2. High Energy Efficiency & Background Sync Controls
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(EmeraldGreen.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = EmeraldGreen,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("Energy Efficiency & Sync Policies", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("Minimize battery and cellular data usage", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Master background sync switch (User requirement: "can be disabled")
                SettingSwitchRow(
                    title = "Automatic Background Sync",
                    subtitle = "Periodically check and sync changes without keeping the app open",
                    checked = settings.backgroundSyncEnabled,
                    onCheckedChange = {
                        val newSettings = settings.copy(backgroundSyncEnabled = it)
                        settings = newSettings
                        onSaveEnergySettings(newSettings)
                    },
                    testTag = "background_sync_master_toggle"
                )

                // Sync only on Wi-Fi
                SettingSwitchRow(
                    title = "Sync Only on Wi-Fi",
                    subtitle = "Prevents heavy cellular battery draw and data usage",
                    checked = settings.syncOnlyOnWifi,
                    onCheckedChange = {
                        val newSettings = settings.copy(syncOnlyOnWifi = it)
                        settings = newSettings
                        onSaveEnergySettings(newSettings)
                    },
                    testTag = "sync_only_wifi_toggle"
                )

                // Sync only while charging
                SettingSwitchRow(
                    title = "Sync Only While Charging",
                    subtitle = "Eliminates all battery impact by syncing only on AC / USB power",
                    checked = settings.syncOnlyWhileCharging,
                    onCheckedChange = {
                        val newSettings = settings.copy(syncOnlyWhileCharging = it)
                        settings = newSettings
                        onSaveEnergySettings(newSettings)
                    },
                    testTag = "sync_only_charging_toggle"
                )

                // Pause on Low Battery
                SettingSwitchRow(
                    title = "Pause on Low Battery",
                    subtitle = "Automatically halts background work when battery is low",
                    checked = settings.pauseOnLowBattery,
                    onCheckedChange = {
                        val newSettings = settings.copy(pauseOnLowBattery = it)
                        settings = newSettings
                        onSaveEnergySettings(newSettings)
                    }
                )

                if (settings.pauseOnLowBattery) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Low Battery Threshold:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${settings.lowBatteryThreshold}%", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = settings.lowBatteryThreshold.toFloat(),
                            onValueChange = {
                                val newSettings = settings.copy(lowBatteryThreshold = it.toInt())
                                settings = newSettings
                                onSaveEnergySettings(newSettings)
                            },
                            valueRange = 10f..40f,
                            steps = 5,
                            colors = SliderDefaults.colors(thumbColor = CfOrangePrimary, activeTrackColor = CfOrangePrimary)
                        )
                    }
                }

                // Interval Selection
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Global Sync Frequency", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Text("Interval between automatic delta checks", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Box {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable { isIntervalMenuOpen = true }
                        ) {
                            Text(
                                text = "${settings.globalIntervalMinutes} min",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        DropdownMenu(
                            expanded = isIntervalMenuOpen,
                            onDismissRequest = { isIntervalMenuOpen = false }
                        ) {
                            listOf(5, 15, 30, 60, 120).forEach { mins ->
                                DropdownMenuItem(
                                    text = { Text("$mins minutes") },
                                    onClick = {
                                        val newSettings = settings.copy(globalIntervalMinutes = mins)
                                        settings = newSettings
                                        onSaveEnergySettings(newSettings)
                                        isIntervalMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Reset / Flush App Data Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Reset & Fresh Start",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = "Flush all database folders, file sync metadata, logs, and reset credentials so you can start completely fresh.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { showResetConfirmDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("settings_flush_all_btn")
                ) {
                    Icon(imageVector = Icons.Default.SyncProblem, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Flush All Data & Reset", color = MaterialTheme.colorScheme.onError)
                }
            }
        }

        // 4. System and Architecture Details
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("About R2 Folder Sync", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("• Zero Egress Fees with Cloudflare R2 Object Storage", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("• AWS SigV4 signed authentication", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("• Intelligent delta hashing with timestamp deduplication", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("• Multi-folder two-way & one-way synchronization", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("• Room on-device database persistence", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showResetConfirmDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = { Text("Flush All App Data?") },
            text = { Text("This will permanently remove all synced folder definitions, cached metadata, history logs, conflicts, and reset your R2 credentials. Are you sure you want a fresh start?") },
            confirmButton = {
                Button(
                    onClick = {
                        onFlushAllData()
                        showResetConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Flush Everything")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showResetConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String = ""
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CfOrangePrimary,
                checkedTrackColor = CfOrangePrimary.copy(alpha = 0.4f)
            ),
            modifier = if (testTag.isNotBlank()) Modifier.testTag(testTag) else Modifier
        )
    }
}
