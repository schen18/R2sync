package com.dissonance.r2sync.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dissonance.r2sync.data.entity.SyncedFolderEntity
import com.dissonance.r2sync.model.ConflictStrategy
import com.dissonance.r2sync.model.SyncDirection
import com.dissonance.r2sync.ui.theme.CfOrangePrimary

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddEditFolderDialog(
    folder: SyncedFolderEntity?,
    onDismiss: () -> Unit,
    onSave: (
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
    ) -> Unit
) {
    var displayName by remember { mutableStateOf(folder?.displayName ?: "") }
    var localPath by remember { mutableStateOf(folder?.localPath ?: "") }
    var localUri by remember { mutableStateOf(folder?.localUri ?: "") }
    var remotePrefix by remember { mutableStateOf(folder?.remotePrefix ?: "devices/android/documents/") }
    var syncDirection by remember { mutableStateOf(folder?.syncDirection ?: SyncDirection.TWO_WAY) }
    var autoSyncEnabled by remember { mutableStateOf(folder?.autoSyncEnabled ?: true) }
    var syncIntervalMinutes by remember { mutableStateOf(folder?.syncIntervalMinutes ?: 15) }
    var filterExtensions by remember { mutableStateOf(folder?.filterExtensions ?: ".pdf,.md,.txt,.docx,.png") }
    var excludeHidden by remember { mutableStateOf(folder?.excludeHidden ?: true) }
    var conflictStrategy by remember { mutableStateOf(folder?.conflictStrategy ?: ConflictStrategy.MANUAL_REVIEW) }

    var isDirectionMenuExpanded by remember { mutableStateOf(false) }
    var isConflictMenuExpanded by remember { mutableStateOf(false) }

    val context = LocalContext.current

    // Folder access ONLY comes from the system picker: a tree URI is
    // meaningless without the persistable grant it issues.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Best effort — some providers don't offer persistable grants.
            }
            localUri = uri.toString()
            localPath = treeDisplayPath(uri)
            if (displayName.isBlank()) {
                displayName = localPath.substringAfterLast('/').ifBlank { localPath }
            }
        }
    }

    val hasAccess = localUri.isNotBlank() && hasPersistedGrant(context, localUri)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = CfOrangePrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (folder == null) "Add Device Folder" else "Configure Synced Folder",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Quick Presets (metadata only — folder access comes from the picker)
                Text(
                    text = "Quick Presets:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = displayName == "Documents",
                        onClick = {
                            displayName = "Documents"
                            remotePrefix = "devices/android/documents/"
                            filterExtensions = ".pdf,.docx,.txt,.md"
                        },
                        label = { Text("Documents", fontSize = 11.sp, maxLines = 1) }
                    )
                    FilterChip(
                        selected = displayName == "Obsidian Notes",
                        onClick = {
                            displayName = "Obsidian Notes"
                            remotePrefix = "devices/android/obsidian/"
                            filterExtensions = ".md,.canvas,.png"
                        },
                        label = { Text("Obsidian", fontSize = 11.sp, maxLines = 1) }
                    )
                    FilterChip(
                        selected = displayName == "Camera Backup",
                        onClick = {
                            displayName = "Camera Backup"
                            remotePrefix = "devices/android/photos/"
                            syncDirection = SyncDirection.UPLOAD_ONLY
                            filterExtensions = ".jpg,.jpeg,.png,.heic,.mp4"
                        },
                        label = { Text("Photos", fontSize = 11.sp, maxLines = 1) }
                    )
                }

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display Name") },
                    modifier = Modifier.fillMaxWidth().testTag("folder_name_input"),
                    singleLine = true
                )

                // Folder selection via the system picker (SAF grant)
                OutlinedButton(
                    onClick = { folderPicker.launch(null) },
                    modifier = Modifier.fillMaxWidth().testTag("folder_pick_button")
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (localUri.isBlank()) "Choose Folder" else "Change Folder")
                }
                Text(
                    text = when {
                        localUri.isBlank() -> "No folder selected — access is granted through the system folder picker."
                        else -> localPath.ifBlank { localUri }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
                if (localUri.isNotBlank() && !hasAccess) {
                    Text(
                        text = "Access to this folder is not granted (stored before the picker existed, or revoked). Re-pick it to restore syncing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = remotePrefix,
                    onValueChange = { remotePrefix = it },
                    label = { Text("Cloudflare R2 Bucket Prefix (Path)") },
                    placeholder = { Text("e.g. devices/android/documents/") },
                    modifier = Modifier.fillMaxWidth().testTag("folder_r2_prefix_input"),
                    singleLine = true
                )

                // Sync Direction Dropdown
                Text(
                    text = "Sync Direction",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isDirectionMenuExpanded = true },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(syncDirection.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = isDirectionMenuExpanded,
                        onDismissRequest = { isDirectionMenuExpanded = false }
                    ) {
                        SyncDirection.values().forEach { dir ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(dir.label, fontWeight = FontWeight.SemiBold)
                                        Text(dir.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = {
                                    syncDirection = dir
                                    isDirectionMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                // Conflict Resolution Option Dropdown
                Text(
                    text = "Conflict Resolution Behavior",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isConflictMenuExpanded = true },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(conflictStrategy.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = isConflictMenuExpanded,
                        onDismissRequest = { isConflictMenuExpanded = false }
                    ) {
                        ConflictStrategy.values().forEach { strat ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(strat.label, fontWeight = FontWeight.SemiBold)
                                        Text(strat.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = {
                                    conflictStrategy = strat
                                    isConflictMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = filterExtensions,
                    onValueChange = { filterExtensions = it },
                    label = { Text("Include File Extensions (comma separated)") },
                    placeholder = { Text("e.g. .pdf,.txt,.md (leave empty for all)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Switches
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Auto-Sync in Background", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Text("Automatically sync changes periodically", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = autoSyncEnabled,
                        onCheckedChange = { autoSyncEnabled = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = CfOrangePrimary)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Exclude Hidden Files", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Text("Ignore files starting with '.'", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = excludeHidden,
                        onCheckedChange = { excludeHidden = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = CfOrangePrimary)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        folder?.id ?: 0L,
                        displayName,
                        localUri,
                        localPath,
                        remotePrefix,
                        syncDirection,
                        autoSyncEnabled,
                        syncIntervalMinutes,
                        filterExtensions,
                        excludeHidden,
                        conflictStrategy
                    )
                },
                enabled = displayName.isNotBlank() && hasAccess,
                colors = ButtonDefaults.buttonColors(containerColor = CfOrangePrimary),
                modifier = Modifier.testTag("save_folder_button")
            ) {
                Text(if (folder == null) "Add Folder" else "Save Changes", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/** "primary:Documents" → "/storage/emulated/0/Documents" (display/fallback only). */
private fun treeDisplayPath(treeUri: Uri): String {
    return try {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val parts = docId.split(':', limit = 2)
        when {
            parts.size == 2 && parts[0] == "primary" -> "/storage/emulated/0/${parts[1]}"
            parts.size == 2 -> "/storage/${parts[0]}/${parts[1]}"
            else -> docId
        }
    } catch (e: Exception) {
        treeUri.toString()
    }
}

private fun hasPersistedGrant(context: Context, uriString: String): Boolean {
    return try {
        context.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == uriString && it.isReadPermission
        }
    } catch (e: Exception) {
        false
    }
}
